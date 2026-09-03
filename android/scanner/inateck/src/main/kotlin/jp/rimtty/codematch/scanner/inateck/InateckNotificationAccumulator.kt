package jp.rimtty.codematch.scanner.inateck

/** Result visible to the transport after one raw FF01 notification chunk. */
internal sealed interface InateckNotificationOutcome {
    /** Native parser needs another raw chunk. */
    object Incomplete : InateckNotificationOutcome

    /**
     * A complete notification received while the vendor command queue is idle.
     *
     * The documented Android parser uses type 0 for scans, while the official
     * iOS SDK and the physical BCST-36 return scan data as type 1. Type 1 still
     * contains an Inateck checksum/header frame, so the accumulator removes it
     * before the bytes reach the strict scan decoder.
     */
    data class Scan(
        val bytes: ByteArray,
        val notifyType: Int,
    ) : InateckNotificationOutcome

    /** Invalid native data, native error status, or buffer overflow. */
    object Error : InateckNotificationOutcome
}

/** Payload-free observation used only by deterministic and physical tests. */
internal enum class InateckNotificationKind {
    INCOMPLETE,
    SCAN,
    ERROR,
}

/**
 * Accumulates raw FF01 chunks according to Inateck's official parser contract.
 *
 * Every invocation sends the current accumulated bytes to the native parser.
 * When native returns status 0, only its returned {@code notify_data} is kept
 * for the next invocation, exactly as prescribed by the SDK documentation.
 * The gateway invokes this accumulator only when no vendor SDK command is
 * active. Both documented type 0 and the BCST-36/iOS-compatible type 1 are
 * therefore retained as scan candidates. Type 1 is decoded with the same
 * checksum and payload slice used by the official iOS SDK. The downstream
 * decoder rejects delayed command JSON and invalid UTF-8.
 * No payload is logged or included in an error message.
 */
internal class InateckNotificationAccumulator(
    private val nativeParser: InateckNotificationNativeParser,
    private val maxBufferBytes: Int = DEFAULT_MAX_BUFFER_BYTES,
) {
    private var pending = ByteArray(0)

    init {
        require(maxBufferBytes > 0) { "maxBufferBytes must be positive" }
    }

    /** Number of bytes retained for the next native parser invocation. */
    val bufferedBytes: Int
        @Synchronized get() = pending.size

    /**
     * Consumes one raw callback chunk and returns only a safe typed outcome.
     * The input is copied before it crosses the injected parser boundary.
     */
    @Synchronized
    fun append(rawChunk: ByteArray): InateckNotificationOutcome {
        if (rawChunk.isEmpty()) return InateckNotificationOutcome.Incomplete
        if (rawChunk.size > maxBufferBytes || pending.size > maxBufferBytes - rawChunk.size) {
            resetLocked()
            return InateckNotificationOutcome.Error
        }

        val input = ByteArray(pending.size + rawChunk.size)
        pending.copyInto(input)
        rawChunk.copyInto(input, destinationOffset = pending.size)

        val json = runCatching { nativeParser.parse(input.clone()) }.getOrNull()
        val result = InateckNotificationResultDecoder.decode(json)
        if (result == null || result.notifyData.size > maxBufferBytes) {
            resetLocked()
            return InateckNotificationOutcome.Error
        }

        return when (result.notifyStatus) {
            NOTIFY_STATUS_INCOMPLETE -> {
                // This is intentionally native-returned data, not `input`:
                // the official parser may remove an already-consumed prefix.
                pending = result.notifyData.clone()
                InateckNotificationOutcome.Incomplete
            }

            NOTIFY_STATUS_COMPLETE -> {
                val scanBytes = when (result.notifyType) {
                    NOTIFY_TYPE_SCAN -> result.notifyData.clone()
                    NOTIFY_TYPE_CODE_FRAME ->
                        InateckNotifyCodeFrameDecoder.decode(result.notifyData)
                    else -> null
                }
                val outcome = if (scanBytes == null || scanBytes.isEmpty()) {
                    InateckNotificationOutcome.Error
                } else {
                    InateckNotificationOutcome.Scan(
                        bytes = scanBytes,
                        notifyType = result.notifyType,
                    )
                }
                resetLocked()
                outcome
            }

            NOTIFY_STATUS_ERROR -> {
                resetLocked()
                InateckNotificationOutcome.Error
            }

            // InateckNotificationResultDecoder rejects unknown statuses. Keep
            // this branch defensive if that decoder changes independently.
            else -> {
                resetLocked()
                InateckNotificationOutcome.Error
            }
        }
    }

    @Synchronized
    fun reset() {
        resetLocked()
    }

    private fun resetLocked() {
        pending = ByteArray(0)
    }

    private companion object {
        const val NOTIFY_TYPE_SCAN = 0
        const val NOTIFY_TYPE_CODE_FRAME = 1
        const val NOTIFY_STATUS_INCOMPLETE = 0
        const val NOTIFY_STATUS_COMPLETE = 1
        const val NOTIFY_STATUS_ERROR = 2
        const val DEFAULT_MAX_BUFFER_BYTES = 4_096
    }
}
