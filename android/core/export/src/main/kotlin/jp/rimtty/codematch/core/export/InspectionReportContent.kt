package jp.rimtty.codematch.core.export

import jp.rimtty.codematch.core.matching.CodeMatcher
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchSession

/** Which table the inspection report prints; one per destination. */
enum class InspectionLayout {
    SAWAI,
    MOLTEN,
    DENSO,
}

/**
 * One line of the inspection report.
 *
 * The row is the unit the operator ticks off on the paper inspection sheet:
 * a part number (with its suffix) for Sawai, a delivery number for Molten and
 * a part number for Denso. [keyText] is printed exactly as the customer's paper
 * writes it — raw Sawai/Molten part numbers without hyphens, Denso as `6-4`.
 */
data class InspectionReportRow(
    val keyText: String,
    /** Molten only: the raw part number of the delivery number. */
    val partNumber: String? = null,
    /** Molten only: the delivery point, which distinguishes two deliveries of one part. */
    val deliveryDestination: String? = null,
    val boxCount: Int,
    /** Quantity printed per box; null when the boxes disagree or a box has no quantity. */
    val quantityPerBox: Double?,
    /** Sum over the boxes; null when any box has no quantity. */
    val totalQuantity: Double?,
    /** True for a box whose QR could not be parsed for the session's destination. */
    val isUnparsed: Boolean = false,
)

data class InspectionReport(
    val layout: InspectionLayout,
    val rows: List<InspectionReportRow>,
) {
    val rowCount: Int
        get() = rows.size
}

/**
 * Builds the inspection report rows of a session.
 *
 * Rows are sorted by part number, then by suffix (Sawai) or delivery number (Molten),
 * so the operator can find each line of the paper sheet quickly; scan order is
 * deliberately not offered. Boxes whose QR cannot be parsed for the locked
 * destination are appended as trailing rows keyed by the recorded code, so
 * the box count of the report always equals the session's box count.
 *
 * This mirrors Swift's `InspectionReport.make(session:)`.
 */
object InspectionReportContent {
    fun build(session: MatchSession): InspectionReport {
        val destination = session.resolvedDestination()
        val layout = when (destination) {
            Destination.MOLTEN -> InspectionLayout.MOLTEN
            Destination.DENSO -> InspectionLayout.DENSO
            Destination.SAWAI, null -> InspectionLayout.SAWAI
        }
        val parsed = LinkedHashMap<SortKey, Bucket>()
        val unparsed = LinkedHashMap<SortKey, Bucket>()

        session.entries.forEach { entry ->
            val placed = when (destination) {
                Destination.SAWAI -> entry.sawaiRecord()?.let { record ->
                    parsed.add(
                        key = SortKey(record.partNumber, record.partSuffix.orEmpty()),
                        keyText = record.partSuffix?.let { "${record.partNumber} ($it)" } ?: record.partNumber,
                        quantity = record.deliveryQuantity,
                    )
                }

                // A Molten sheet lists parts, so rows sort by part number first and
                // the delivery numbers of one part stay together.
                Destination.MOLTEN -> entry.moltenRecord()?.let { record ->
                    parsed.add(
                        key = SortKey(record.partNumber, record.deliveryNumber),
                        keyText = record.deliveryNumber,
                        quantity = record.packQuantity.toDouble(),
                        partNumber = record.partNumber,
                        deliveryDestination = record.deliveryDestination,
                    )
                }

                Destination.DENSO -> entry.densoRecord()?.let { record ->
                    parsed.add(
                        key = SortKey(record.partNumber, ""),
                        keyText = CodeMatcher.formatPartNumber(record.partNumber, Destination.DENSO),
                        quantity = record.packQuantity.toDouble(),
                    )
                }

                null -> null
            }
            if (placed == null) {
                unparsed.add(key = SortKey(entry.code, ""), keyText = entry.code, quantity = null)
            }
        }

        val rows = parsed.entries.sortedWith(compareBy({ it.key.primary }, { it.key.secondary }))
            .map { it.value.toRow(isUnparsed = false) } +
            unparsed.entries.sortedBy { it.key.primary }
                .map { it.value.toRow(isUnparsed = true) }
        return InspectionReport(layout = layout, rows = rows)
    }

    private data class SortKey(val primary: String, val secondary: String)

    private class Bucket(
        val keyText: String,
        val partNumber: String?,
        val deliveryDestination: String?,
    ) {
        val quantities = mutableListOf<Double?>()

        fun toRow(isUnparsed: Boolean): InspectionReportRow {
            val allPresent = quantities.none { it == null }
            val perBox = quantities.firstOrNull()
            return InspectionReportRow(
                keyText = keyText,
                partNumber = partNumber,
                deliveryDestination = deliveryDestination,
                boxCount = quantities.size,
                quantityPerBox = perBox?.takeIf { allPresent && quantities.all { it == perBox } },
                totalQuantity = if (allPresent) quantities.sumOf { it ?: 0.0 } else null,
                isUnparsed = isUnparsed,
            )
        }
    }

    private fun LinkedHashMap<SortKey, Bucket>.add(
        key: SortKey,
        keyText: String,
        quantity: Double?,
        partNumber: String? = null,
        deliveryDestination: String? = null,
    ) {
        getOrPut(key) { Bucket(keyText, partNumber, deliveryDestination) }.quantities += quantity
    }
}
