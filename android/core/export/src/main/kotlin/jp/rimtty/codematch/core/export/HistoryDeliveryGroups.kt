package jp.rimtty.codematch.core.export

import jp.rimtty.codematch.core.matching.CodeMatcher
import jp.rimtty.codematch.core.matching.DensoKanbanQrRecord
import jp.rimtty.codematch.core.matching.KanbanQrRecord
import jp.rimtty.codematch.core.matching.MoltenQrRecord
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession

/**
 * The destination to display for a session.
 *
 * A session recorded before destinations existed carries no [MatchSession.destination],
 * so it is derived from the first entry that kept its QR payload. A session whose
 * entries hold no recognizable record stays null and is displayed without a
 * destination rather than defaulting to one.
 *
 * This mirrors Swift's `MatchSession.resolvedDestination`.
 */
fun MatchSession.resolvedDestination(): Destination? =
    destination ?: entries.firstOrNull { it.qrPayload != null }
        ?.qrPayload
        ?.let(CodeMatcher::detectDestination)

/**
 * The boxes of one Molten delivery number.
 *
 * A Molten slip repeats for every box of the same part, so the delivery number
 * — not the part number — is what an operator counts against. One recorded
 * entry is one box, which makes [boxCount] the number of entries and
 * [cumulativeQuantity] the sum of their pack quantities. Completion is
 * deliberately not judged here; the report only states the running total.
 *
 * This mirrors Swift's `DeliveryGroup`.
 */
data class MoltenDeliveryGroup(
    val deliveryNumber: String,
    /**
     * The record of the first box scanned for this delivery number. The fields
     * shared by every box of the delivery (part, destination, instruction date)
     * are displayed from it.
     */
    val record: MoltenQrRecord,
    /** The entries of this delivery number in scan order. */
    val entries: List<MatchEntry>,
) {
    val boxCount: Int
        get() = entries.size

    val cumulativeQuantity: Int
        get() = entries.sumOf { it.moltenRecord()?.packQuantity ?: 0 }
}

/**
 * Groups entries by their Molten delivery number in first-seen order.
 *
 * Only entries whose QR payload is a Molten record contribute; a Sawai entry
 * and one saved without its payload are skipped, so a Sawai group returns an
 * empty list.
 */
fun List<MatchEntry>.moltenDeliveryGroups(): List<MoltenDeliveryGroup> {
    val records = LinkedHashMap<String, MoltenQrRecord>()
    val buckets = LinkedHashMap<String, MutableList<MatchEntry>>()
    forEach { entry ->
        val record = entry.moltenRecord() ?: return@forEach
        records.putIfAbsent(record.deliveryNumber, record)
        buckets.getOrPut(record.deliveryNumber) { mutableListOf() }.add(entry)
    }
    return buckets.map { (deliveryNumber, entries) ->
        MoltenDeliveryGroup(
            deliveryNumber = deliveryNumber,
            record = records.getValue(deliveryNumber),
            entries = entries.toList(),
        )
    }
}

/**
 * Formats a Molten `MMDD` instruction date as `MM/DD`.
 * A value that is not four digits is returned unchanged so an unexpected
 * payload is still shown rather than silently reformatted.
 */
fun formatMoltenDate(mmdd: String): String =
    if (FOUR_DIGITS.matches(mmdd)) "${mmdd.substring(0, 2)}/${mmdd.substring(2, 4)}" else mmdd

/**
 * Formats a Molten `HHMM` instruction time as `HH:MM`, keeping null for the
 * blank field. A value that is not four digits is returned unchanged.
 */
fun formatMoltenTime(hhmm: String?): String? {
    val value = hhmm ?: return null
    return if (FOUR_DIGITS.matches(value)) "${value.substring(0, 2)}:${value.substring(2, 4)}" else value
}

/**
 * Parses an entry's QR payload as a Sawai slip.
 * The destination is detected first because [KanbanQrRecord.parse] is lenient:
 * a Denso `JAMA...` payload satisfies its card-number rule and a Molten record
 * would be read at the wrong positions.
 */
internal fun MatchEntry.sawaiRecord(): KanbanQrRecord? {
    val payload = qrPayload ?: return null
    if (CodeMatcher.detectDestination(payload) != Destination.SAWAI) return null
    return KanbanQrRecord.parse(payload)
}

/**
 * Parses an entry's QR payload as a Molten record.
 * The destination is detected first so a Sawai payload can never be read at
 * Molten field positions.
 */
internal fun MatchEntry.moltenRecord(): MoltenQrRecord? {
    val payload = qrPayload ?: return null
    if (CodeMatcher.detectDestination(payload) != Destination.MOLTEN) return null
    return MoltenQrRecord.parse(payload)
}

/**
 * Parses an entry's QR payload as a Denso kanban.
 * The destination is detected first because [jp.rimtty.codematch.core.matching.KanbanQrRecord]
 * parses leniently: a `JAMA...` payload satisfies its card-number rule, so a
 * Denso kanban would otherwise be rendered as a Sawai slip.
 */
internal fun MatchEntry.densoRecord(): DensoKanbanQrRecord? {
    val payload = qrPayload ?: return null
    if (CodeMatcher.detectDestination(payload) != Destination.DENSO) return null
    return DensoKanbanQrRecord.parse(payload)
}

private val FOUR_DIGITS = Regex("[0-9]{4}")
