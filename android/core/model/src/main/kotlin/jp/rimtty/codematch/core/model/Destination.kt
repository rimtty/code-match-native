package jp.rimtty.codematch.core.model

/**
 * The delivery destination a slip QR belongs to.
 *
 * The two destinations print incompatible QR records, so the matching rules,
 * the accepted payload length, and the box identity all depend on which one a
 * payload came from:
 *
 * - [SAWAI] (澤井製作所): a 66-character kanban record carrying a card number.
 * - [MOLTEN] (モルテン): a 61-character delivery record whose trailing spaces
 *   are significant data.
 *
 * [id] is the persisted representation. Keep it identical to the Swift
 * `Destination` raw value so saved settings and history stay portable.
 */
enum class Destination(val id: String) {
    SAWAI("sawai"),
    MOLTEN("molten"),
    ;

    companion object {
        /** Resolve a persisted id, returning null for unknown or missing values. */
        fun fromId(id: String?): Destination? = entries.firstOrNull { it.id == id }
    }
}
