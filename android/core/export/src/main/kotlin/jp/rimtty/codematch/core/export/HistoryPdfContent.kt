package jp.rimtty.codematch.core.export

import jp.rimtty.codematch.core.matching.CodeMatcher
import jp.rimtty.codematch.core.matching.KanbanQrRecord
import jp.rimtty.codematch.core.matching.TagBarcodeRecord
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.GroupedMatchEntry
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession
import java.time.ZoneId

/** Visual intent for a platform PDF renderer. */
enum class PdfTextStyle {
    TITLE,
    SECTION,
    BODY,
    MUTED,
    MONOSPACE,
    MONOSPACE_BOLD,
    FOOTER,
    DIVIDER,
}

/** One logical PDF block before it is wrapped to the physical page width. */
data class HistoryPdfBlock(
    val text: String,
    val style: PdfTextStyle = PdfTextStyle.BODY,
    val spacingAfter: Float = 4f,
)

/**
 * Pure history report content. It contains every stored group and entry and
 * is intentionally independent from [android.graphics.pdf.PdfDocument], so
 * content parity can be tested on the local JVM.
 */
object HistoryPdfContent {
    fun build(
        session: MatchSession,
        language: AppLanguage = AppLanguage.JAPANESE,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<HistoryPdfBlock> {
        val labels = HistoryExportTextFormatter.labels(language)
        val blocks = mutableListOf<HistoryPdfBlock>()

        blocks += HistoryPdfBlock(labels.reportTitle, PdfTextStyle.TITLE, spacingAfter = 8f)
        if (session.displayName.isNotEmpty()) {
            blocks += HistoryPdfBlock(
                text = "${labels.sessionName}: ${session.displayName}",
                style = PdfTextStyle.SECTION,
                spacingAfter = 4f,
            )
        }
        blocks += HistoryPdfBlock(
            text = "${labels.start}: ${HistoryExportTextFormatter.dateTime(session.startedAt, language, zoneId)}",
            style = PdfTextStyle.MUTED,
            spacingAfter = 2f,
        )
        val endedAt = session.endedAt
        if (endedAt != null) {
            blocks += HistoryPdfBlock(
                text = "${labels.end}: ${HistoryExportTextFormatter.dateTime(endedAt, language, zoneId)}",
                style = PdfTextStyle.MUTED,
                spacingAfter = 2f,
            )
        } else {
            blocks += HistoryPdfBlock(
                text = "${labels.status}: ${labels.inProgress}",
                style = PdfTextStyle.MUTED,
                spacingAfter = 2f,
            )
        }
        val destination = session.resolvedDestination()
        if (destination != null) {
            blocks += HistoryPdfBlock(
                text = "${labels.destination}: ${labels.destinationName(destination)}",
                style = PdfTextStyle.MUTED,
                spacingAfter = 2f,
            )
        }
        // Sawai slips are counted per part number, so only a Moltec report adds
        // the delivery-number total; the Sawai header stays exactly as before.
        val deliveryNumberCount = if (destination == Destination.MOLTEC) {
            session.entries.moltecDeliveryGroups().size
        } else {
            null
        }
        blocks += HistoryPdfBlock(
            text = sessionSummary(session, language, labels),
            style = PdfTextStyle.MUTED,
            spacingAfter = if (deliveryNumberCount == null) 8f else 2f,
        )
        if (deliveryNumberCount != null) {
            blocks += HistoryPdfBlock(
                text = "${labels.deliveryNumberCount}: " +
                    HistoryExportTextFormatter.integer(deliveryNumberCount, language),
                style = PdfTextStyle.MUTED,
                spacingAfter = 8f,
            )
        }
        blocks += HistoryPdfBlock("", PdfTextStyle.DIVIDER, spacingAfter = 6f)

        if (session.entries.isEmpty()) {
            blocks += HistoryPdfBlock(labels.noMatches, PdfTextStyle.MUTED, spacingAfter = 8f)
        } else {
            session.groupedEntries.forEachIndexed { groupIndex, group ->
                appendGroup(blocks, group, groupIndex, language, zoneId, labels)
                blocks += HistoryPdfBlock("", PdfTextStyle.DIVIDER, spacingAfter = 6f)
            }
        }

        blocks += HistoryPdfBlock(labels.generatedNote, PdfTextStyle.FOOTER, spacingAfter = 0f)
        return blocks
    }

    private fun appendGroup(
        blocks: MutableList<HistoryPdfBlock>,
        group: GroupedMatchEntry,
        groupIndex: Int,
        language: AppLanguage,
        zoneId: ZoneId,
        labels: HistoryExportLabels,
    ) {
        val number = HistoryExportTextFormatter.integer(groupIndex + 1, language)
        val count = HistoryExportTextFormatter.boxCount(group.boxCount, language)
        blocks += HistoryPdfBlock(
            text = "#$number ${group.code} ($count)",
            style = PdfTextStyle.MONOSPACE_BOLD,
            spacingAfter = 2f,
        )

        val first = HistoryExportTextFormatter.dateTime(group.firstMatchedAt, language, zoneId)
        val timeText = if (group.boxCount > 1) {
            val last = HistoryExportTextFormatter.dateTime(group.lastMatchedAt, language, zoneId)
            if (language == AppLanguage.JAPANESE) "$first 〜 $last" else "$first – $last"
        } else {
            first
        }
        blocks += HistoryPdfBlock(
            text = "${labels.matchTime}: $timeText",
            style = PdfTextStyle.MUTED,
            spacingAfter = 4f,
        )

        // The group is a part number, but a Moltec part repeats across delivery
        // numbers, so its boxes are reported per delivery number instead.
        val destination = group.entries.asSequence()
            .mapNotNull { it.qrPayload }
            .firstOrNull()
            ?.let(CodeMatcher::detectDestination)
        if (destination == Destination.MOLTEC) {
            appendMoltecDeliveries(blocks, group, language, zoneId, labels)
        } else {
            appendSawaiDelivery(blocks, group, language, labels)
            blocks += HistoryPdfBlock(labels.boxRecords, PdfTextStyle.SECTION, spacingAfter = 2f)
            group.entries.forEachIndexed { boxIndex, entry ->
                appendBoxRecord(blocks, entry, boxIndex + 1, null, language, zoneId, labels)
            }
        }
    }

    private fun appendSawaiDelivery(
        blocks: MutableList<HistoryPdfBlock>,
        group: GroupedMatchEntry,
        language: AppLanguage,
        labels: HistoryExportLabels,
    ) {
        val qr = group.entries.asSequence()
            .mapNotNull { it.qrPayload?.let(KanbanQrRecord::parse) }
            .firstOrNull()
            ?: return

        blocks += HistoryPdfBlock(labels.deliveryInformation, PdfTextStyle.SECTION, spacingAfter = 2f)
        val suffix = qr.partSuffix?.let { " (${labels.suffix} $it)" }.orEmpty()
        blocks += HistoryPdfBlock(
            text = "${labels.itemNumber}: ${CodeMatcher.formatPartNumber(qr.partNumber)}$suffix; " +
                "${labels.cardNumber}: ${qr.cardNumber}",
            style = PdfTextStyle.BODY,
            spacingAfter = 2f,
        )
        blocks += HistoryPdfBlock(
            text = "${labels.deliveryQuantity}: " +
                "${HistoryExportTextFormatter.quantity(qr.deliveryQuantity, language)}; " +
                "${labels.instructedQuantity}: " +
                HistoryExportTextFormatter.quantity(qr.instructedQuantity, language),
            style = PdfTextStyle.BODY,
            spacingAfter = 2f,
        )
        blocks += HistoryPdfBlock(
            text = "${labels.factory}: ${qr.factoryCode ?: "-"}; " +
                "${labels.warehouse}: ${qr.warehouseCode ?: "-"}; " +
                "${labels.supplyPoint}: ${qr.supplyPointCode ?: "-"}",
            style = PdfTextStyle.BODY,
            spacingAfter = 4f,
        )
    }

    /**
     * One delivery block per delivery number, each followed by its own boxes.
     * The box index restarts inside a delivery number because that is the unit
     * an operator counts against the slip.
     */
    private fun appendMoltecDeliveries(
        blocks: MutableList<HistoryPdfBlock>,
        group: GroupedMatchEntry,
        language: AppLanguage,
        zoneId: ZoneId,
        labels: HistoryExportLabels,
    ) {
        val deliveries = group.entries.moltecDeliveryGroups()
        deliveries.forEach { delivery ->
            val record = delivery.record
            blocks += HistoryPdfBlock(labels.deliveryInformation, PdfTextStyle.SECTION, spacingAfter = 2f)
            blocks += HistoryPdfBlock(
                text = "${labels.deliveryNumber}: ${delivery.deliveryNumber}" +
                    deliverySummary(delivery, language, labels),
                style = PdfTextStyle.BODY,
                spacingAfter = 2f,
            )
            blocks += HistoryPdfBlock(
                text = "${labels.moltecPartNumber}: ${CodeMatcher.formatPartNumber(record.partNumber)}; " +
                    "${labels.ordererCode}: ${record.ordererCode}",
                style = PdfTextStyle.BODY,
                spacingAfter = 2f,
            )
            blocks += HistoryPdfBlock(
                text = "${labels.deliveryDestination}: ${record.deliveryDestination}; " +
                    "${labels.tyLocation}: ${record.tyLocation ?: "-"}; " +
                    "${labels.supplyPoint}: ${record.supplyPoint}",
                style = PdfTextStyle.BODY,
                spacingAfter = 2f,
            )
            blocks += HistoryPdfBlock(
                text = "${labels.instructionDate}: ${formatMoltecDate(record.instructionDate)}; " +
                    "${labels.instructionTime}: ${formatMoltecTime(record.instructionTime) ?: "-"}",
                style = PdfTextStyle.BODY,
                spacingAfter = 4f,
            )
            delivery.entries.forEachIndexed { boxIndex, entry ->
                appendBoxRecord(
                    blocks,
                    entry,
                    boxIndex + 1,
                    entry.moltecRecord()?.packQuantity,
                    language,
                    zoneId,
                    labels,
                )
            }
        }

        // A box saved without its QR payload belongs to no delivery number, but
        // the report must never drop a recorded box.
        val reported = deliveries.flatMapTo(mutableSetOf()) { delivery ->
            delivery.entries.map(MatchEntry::id)
        }
        val remaining = group.entries.filterNot { it.id in reported }
        if (remaining.isNotEmpty()) {
            blocks += HistoryPdfBlock(labels.boxRecords, PdfTextStyle.SECTION, spacingAfter = 2f)
            remaining.forEachIndexed { boxIndex, entry ->
                appendBoxRecord(blocks, entry, boxIndex + 1, null, language, zoneId, labels)
            }
        }
    }

    /**
     * One box: its match time, optional pack quantity, management code, and
     * both raw payloads. [packQuantity] is null for a Sawai box, whose slip
     * carries the quantity in the delivery block instead.
     */
    private fun appendBoxRecord(
        blocks: MutableList<HistoryPdfBlock>,
        entry: MatchEntry,
        boxIndex: Int,
        packQuantity: Int?,
        language: AppLanguage,
        zoneId: ZoneId,
        labels: HistoryExportLabels,
    ) {
        val boxNumber = HistoryExportTextFormatter.integer(boxIndex, language)
        val managementCode = entry.barcodePayload
            ?.let(TagBarcodeRecord::parse)
            ?.managementCode
            ?: "-"
        val quantity = packQuantity
            ?.let { "${labels.packQuantity}: ${HistoryExportTextFormatter.integer(it, language)}; " }
            .orEmpty()
        blocks += HistoryPdfBlock(
            text = "${boxLabel(boxNumber, language, labels)}  ${labels.matchTime}: " +
                "${HistoryExportTextFormatter.dateTime(entry.matchedAt, language, zoneId)}; " +
                quantity +
                "${labels.managementCode}: $managementCode",
            style = PdfTextStyle.BODY,
            spacingAfter = 2f,
        )
        blocks += HistoryPdfBlock(
            text = "${labels.qrFullText}: ${entry.qrPayload ?: labels.legacyPayload}",
            style = PdfTextStyle.MONOSPACE,
            spacingAfter = 2f,
        )
        blocks += HistoryPdfBlock(
            text = "${labels.code128FullText}: ${entry.barcodePayload ?: labels.legacyPayload}",
            style = PdfTextStyle.MONOSPACE,
            spacingAfter = 5f,
        )
    }

    /** `（2箱、累計 240 個）`, following the language's parenthesis convention. */
    private fun deliverySummary(
        delivery: MoltecDeliveryGroup,
        language: AppLanguage,
        labels: HistoryExportLabels,
    ): String {
        val boxes = HistoryExportTextFormatter.boxCount(delivery.boxCount, language)
        val total = "${labels.cumulativeQuantity} " +
            "${HistoryExportTextFormatter.integer(delivery.cumulativeQuantity, language)} " +
            labels.pieceUnit
        return if (language == AppLanguage.JAPANESE) "（$boxes、$total）" else " ($boxes, $total)"
    }

    private fun sessionSummary(
        session: MatchSession,
        language: AppLanguage,
        labels: HistoryExportLabels,
    ): String = if (language == AppLanguage.JAPANESE) {
        "${labels.inspectionBoxCount}: ${HistoryExportTextFormatter.boxCount(session.matchedCount, language)}" +
            "（${labels.partCount}: " +
            HistoryExportTextFormatter.integer(session.groupedEntries.size, language) + "）"
    } else {
        "${labels.inspectionBoxCount}: ${HistoryExportTextFormatter.boxCount(session.matchedCount, language)} " +
            "(${labels.partCount}: " +
            HistoryExportTextFormatter.integer(session.groupedEntries.size, language) + ")"
    }

    private fun boxLabel(
        number: String,
        language: AppLanguage,
        labels: HistoryExportLabels,
    ): String = if (language == AppLanguage.JAPANESE) "$number${labels.box}" else "${labels.box} $number"

}
