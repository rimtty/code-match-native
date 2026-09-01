package jp.rimtty.codematch.core.model

import java.util.UUID

/**
 * One successful comparison recorded as one inspected box.
 *
 * IDs are UUID strings and timestamps are UTC epoch milliseconds so the model
 * can be persisted by Room without coupling it to Android date/time classes.
 */
data class MatchEntry(
    val id: String = UUID.randomUUID().toString(),
    val code: String,
    val matchedAt: Long = System.currentTimeMillis(),
    val qrPayload: String? = null,
    val barcodePayload: String? = null,
    /** Monotonic position in the parent session, starting at zero. */
    val sequence: Long = 0L,
)

/** A comparison session, including only successful match entries. */
data class MatchSession(
    val id: String = UUID.randomUUID().toString(),
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val entries: List<MatchEntry> = emptyList(),
    val name: String? = null,
) {
    val isActive: Boolean
        get() = endedAt == null

    val matchedCount: Int
        get() = entries.size

    /** A blank or whitespace-only name is displayed as no name. */
    val displayName: String
        get() = name?.trim().orEmpty()

    /** Number of boxes whose recorded part number equals [code]. */
    fun matchCount(code: String): Int = entries.count { it.code == code }

    /**
     * Groups duplicate part numbers in first-seen order. Each entry remains in
     * scan order so the group represents individual boxes rather than a set of
     * unique part numbers.
     */
    val groupedEntries: List<GroupedMatchEntry>
        get() {
            val buckets = LinkedHashMap<String, MutableList<MatchEntry>>()
            entries.forEach { entry ->
                buckets.getOrPut(entry.code) { mutableListOf() }.add(entry)
            }
            return buckets.map { (code, grouped) ->
                GroupedMatchEntry(code = code, entries = grouped.toList())
            }
        }
}

/**
 * Result of attempting to finish a session.
 *
 * Empty sessions are intentionally removed from history, so callers can show
 * a different confirmation for that case than for a persisted ended session.
 */
sealed interface EndSessionOutcome {
    /** The session had no boxes and was discarded. */
    data class DeletedEmpty(val sessionId: String) : EndSessionOutcome

    /** The session had at least one box and now has an end timestamp. */
    data class Ended(val sessionId: String, val endedAt: Long) : EndSessionOutcome

    /** No session with the requested id (or no active session) exists. */
    data object NotFound : EndSessionOutcome

    /** The requested session had already been ended. */
    data class AlreadyEnded(val sessionId: String, val endedAt: Long) : EndSessionOutcome
}

/** A first-seen-order group of boxes carrying the same part number. */
data class GroupedMatchEntry(
    val code: String,
    val entries: List<MatchEntry>,
) {
    val id: String
        get() = code

    val boxCount: Int
        get() = entries.size

    val firstMatchedAt: Long
        get() = entries.firstOrNull()?.matchedAt ?: Long.MIN_VALUE

    val lastMatchedAt: Long
        get() = entries.lastOrNull()?.matchedAt ?: Long.MIN_VALUE
}
