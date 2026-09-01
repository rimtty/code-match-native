package jp.rimtty.codematch.feature.scan

/**
 * Result of one camera Code 128 callback.
 *
 * [Pending] means that a value is being held as the first observation,
 * [Accepted] means that the same value was observed again inside the strict
 * confirmation window, and [Locked] means that an accepted value is still in
 * the short post-acceptance lock. [Rejected] is used for an empty callback.
 */
sealed interface ScanStabilizationResult {
    data object Pending : ScanStabilizationResult
    data object Locked : ScanStabilizationResult
    data object Rejected : ScanStabilizationResult
    data class Accepted(val value: String) : ScanStabilizationResult
}
typealias StabilizationResult = ScanStabilizationResult

/**
 * A deterministic lock used immediately after accepting a camera scan.
 *
 * The boundary is deliberately strict: elapsed time `< lockDurationMillis`
 * is locked, while elapsed time `== lockDurationMillis` is available again.
 */
class ScanAcceptanceLock(
    val lockDurationMillis: Long = DEFAULT_LOCK_DURATION_MILLIS,
) {
    init {
        require(lockDurationMillis >= 0L) { "lockDurationMillis must be non-negative" }
    }

    private var acceptedAtMillis: Long? = null

    val acceptedAt: Long?
        get() = acceptedAtMillis

    fun isLocked(timestampMillis: Long): Boolean {
        val acceptedAt = acceptedAtMillis ?: return false
        val elapsed = timestampMillis - acceptedAt
        return elapsed in 0 until lockDurationMillis
    }

    /** Acquire the lock unless the previous acceptance is still locked. */
    fun acquire(timestampMillis: Long): Boolean {
        if (isLocked(timestampMillis)) return false
        acceptedAtMillis = timestampMillis
        return true
    }

    /** Compatibility spelling for callers that model this as an acceptance. */
    fun accept(timestampMillis: Long): Boolean = acquire(timestampMillis)

    fun reset() {
        acceptedAtMillis = null
    }

    companion object {
        const val DEFAULT_LOCK_DURATION_MILLIS: Long = 250L
    }
}

/**
 * Camera Code 128 stabilizer used before dispatching a payload to the scan
 * reducer. Two identical values are required inside a strict 1.5 second
 * window. At exactly 1,500 ms the old candidate is discarded and the value
 * becomes a new first observation.
 */
class ScanStabilizer(
    val windowMillis: Long = DEFAULT_CONFIRMATION_WINDOW_MILLIS,
    val lockMillis: Long = ScanAcceptanceLock.DEFAULT_LOCK_DURATION_MILLIS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /** Alternate constructor for tests that name their injected clock `clock`. */
    constructor(
        clock: () -> Long,
        windowMillis: Long = DEFAULT_CONFIRMATION_WINDOW_MILLIS,
        lockMillis: Long = ScanAcceptanceLock.DEFAULT_LOCK_DURATION_MILLIS,
    ) : this(windowMillis, lockMillis, clock)

    init {
        require(windowMillis > 0L) { "windowMillis must be positive" }
        require(lockMillis >= 0L) { "lockMillis must be non-negative" }
    }

    private val acceptanceLock = ScanAcceptanceLock(lockMillis)
    private var candidateValue: String? = null
    private var candidateAtMillis: Long? = null

    val confirmationWindowMillis: Long
        get() = windowMillis

    val acceptanceLockMillis: Long
        get() = lockMillis

    val candidate: String?
        get() = candidateValue

    val candidateAt: Long?
        get() = candidateAtMillis

    val isLocked: Boolean
        get() = acceptanceLock.isLocked(now())

    /**
     * Submit one callback. A caller may pass an explicit timestamp to avoid
     * wall-clock sleeps in tests; omitted timestamps use the injected clock.
     */
    fun submit(value: String, timestampMillis: Long = now()): ScanStabilizationResult {
        val normalized = normalizeTransportTerminators(value)
        if (normalized.isBlank()) return ScanStabilizationResult.Rejected
        if (acceptanceLock.isLocked(timestampMillis)) return ScanStabilizationResult.Locked

        val previousValue = candidateValue
        val previousAt = candidateAtMillis
        if (previousValue == normalized && previousAt != null) {
            val elapsed = timestampMillis - previousAt
            if (elapsed in 0 until windowMillis) {
                candidateValue = null
                candidateAtMillis = null
                acceptanceLock.acquire(timestampMillis)
                return ScanStabilizationResult.Accepted(normalized)
            }
        }

        // A different value, a backwards timestamp, or an elapsed time at or
        // beyond the boundary replaces the old candidate.
        candidateValue = normalized
        candidateAtMillis = timestampMillis
        return ScanStabilizationResult.Pending
    }

    fun offer(value: String, timestampMillis: Long = now()): ScanStabilizationResult =
        submit(value, timestampMillis)

    fun accept(value: String, timestampMillis: Long = now()): ScanStabilizationResult =
        submit(value, timestampMillis)

    fun reset() {
        candidateValue = null
        candidateAtMillis = null
        acceptanceLock.reset()
    }

    companion object {
        const val DEFAULT_CONFIRMATION_WINDOW_MILLIS: Long = 1_500L

        fun normalizeTransportTerminators(rawValue: String): String {
            var value = rawValue
            while (value.isNotEmpty() &&
                (value.last() == '\r' || value.last() == '\n' || value.last() == '\u0000')
            ) {
                value = value.dropLast(1)
            }
            return value
        }
    }
}
