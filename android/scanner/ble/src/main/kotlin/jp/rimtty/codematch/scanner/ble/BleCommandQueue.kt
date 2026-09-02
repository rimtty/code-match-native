package jp.rimtty.codematch.scanner.ble

/** One serialized GATT write. UUIDs/bytes are supplied by the platform adapter. */
data class BleCommand(
    val id: String,
    val characteristicUuid: String,
    val payload: ByteArray,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(characteristicUuid.isNotBlank()) { "characteristicUuid must not be blank" }
        require(payload.isNotEmpty()) { "payload must not be empty" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    override fun equals(other: Any?): Boolean =
        other is BleCommand &&
            id == other.id &&
            characteristicUuid == other.characteristicUuid &&
            payload.contentEquals(other.payload) &&
            timeoutMillis == other.timeoutMillis

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + characteristicUuid.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + timeoutMillis.hashCode()
        return result
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L
    }
}

sealed interface BleCommandOutcome {
    data object Succeeded : BleCommandOutcome
    data class Failed(val reason: String) : BleCommandOutcome
    data object TimedOut : BleCommandOutcome
    data class Cancelled(val reason: String) : BleCommandOutcome
}

sealed interface BleCommandSubmitResult {
    data object Started : BleCommandSubmitResult
    data class Rejected(val reason: RejectionReason) : BleCommandSubmitResult
}

enum class RejectionReason {
    ALREADY_IN_FLIGHT,
    BLOCKED_AFTER_TIMEOUT,
    DISPATCH_FAILED,
}

sealed interface BleCommandTickResult {
    data object Noop : BleCommandTickResult
    data class TimedOut(val command: BleCommand) : BleCommandTickResult
}

/**
 * Exactly-one-in-flight command serializer.
 *
 * A timeout intentionally enters [blockedAfterTimeout]. No later command is
 * dispatched until the transport link has been torn down and the owner calls
 * [resetAfterTransportReset]. This prevents a late callback from an old GATT
 * link racing a new settings request.
 */
class BleCommandQueue(
    private val dispatch: (BleCommand, (BleCommandOutcome) -> Unit) -> Boolean,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private data class InFlight(
        val command: BleCommand,
        val generation: Long,
        val startedAtMillis: Long,
        val completion: (BleCommandOutcome) -> Unit,
    )

    private var inFlight: InFlight? = null
    private var nextGeneration = 0L
    private var timedOutGeneration: Long? = null

    val isInFlight: Boolean get() = inFlight != null
    val blockedAfterTimeout: Boolean get() = timedOutGeneration != null
    val currentCommand: BleCommand? get() = inFlight?.command

    fun submit(
        command: BleCommand,
        completion: (BleCommandOutcome) -> Unit = {},
    ): BleCommandSubmitResult {
        if (blockedAfterTimeout) {
            return BleCommandSubmitResult.Rejected(RejectionReason.BLOCKED_AFTER_TIMEOUT)
        }
        if (inFlight != null) {
            return BleCommandSubmitResult.Rejected(RejectionReason.ALREADY_IN_FLIGHT)
        }

        val generation = nextGeneration++
        inFlight = InFlight(command, generation, nowMillis(), completion)
        val accepted = try {
            dispatch(command) { outcome ->
                complete(command.id, generation, outcome)
            }
        } catch (_: Exception) {
            // A platform adapter may throw before it can report a dispatch
            // result. Do not leave a phantom in-flight command behind.
            if (inFlight?.generation == generation) {
                inFlight = null
                completion(BleCommandOutcome.Failed("BLE command dispatch failed"))
            }
            return BleCommandSubmitResult.Rejected(RejectionReason.DISPATCH_FAILED)
        }
        if (!accepted) {
            // A synchronous adapter failure must not leave a phantom command
            // occupying the queue.
            if (inFlight?.generation == generation) {
                inFlight = null
                completion(BleCommandOutcome.Failed("BLE command dispatch failed"))
            }
            return BleCommandSubmitResult.Rejected(RejectionReason.DISPATCH_FAILED)
        }
        return BleCommandSubmitResult.Started
    }

    /** Completes only the current generation; late callbacks are ignored. */
    fun complete(
        commandId: String,
        generation: Long? = inFlight?.generation,
        outcome: BleCommandOutcome,
    ): Boolean {
        val pending = inFlight ?: return false
        if (pending.command.id != commandId || pending.generation != generation) return false
        inFlight = null
        pending.completion(outcome)
        return true
    }

    /**
     * Advances timeout handling without creating a timer dependency. Call from
     * the owner's coroutine/ticker or deterministic test clock.
     */
    fun tick(atMillis: Long = nowMillis()): BleCommandTickResult {
        val pending = inFlight ?: return BleCommandTickResult.Noop
        if (atMillis - pending.startedAtMillis < pending.command.timeoutMillis) {
            return BleCommandTickResult.Noop
        }

        inFlight = null
        timedOutGeneration = pending.generation
        pending.completion(BleCommandOutcome.TimedOut)
        return BleCommandTickResult.TimedOut(pending.command)
    }

    /** Cancels a command without opening the queue to the next one. */
    fun cancel(reason: String = "BLE command cancelled"): Boolean {
        val pending = inFlight ?: return false
        inFlight = null
        // Cancellation does not prove that the transport stopped its write.
        // Keep the queue closed until the owner tears down/resets the link so
        // a late callback cannot race a replacement command.
        timedOutGeneration = pending.generation
        pending.completion(BleCommandOutcome.Cancelled(reason))
        return true
    }

    /**
     * Clears a timed-out generation only after the owner has closed/recreated
     * the transport link. This is the sole path that permits another command.
     */
    fun resetAfterTransportReset() {
        inFlight = null
        timedOutGeneration = null
    }
}
