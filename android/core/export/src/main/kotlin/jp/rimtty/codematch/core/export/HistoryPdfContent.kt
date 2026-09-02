package jp.rimtty.codematch.core.export

import jp.rimtty.codematch.core.matching.CodeMatcher
import jp.rimtty.codematch.core.matching.KanbanQrRecord
import jp.rimtty.codematch.core.matching.TagBarcodeRecord
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.GroupedMatchEntry
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
        blocks += HistoryPdfBlock(
            text = sessionSummary(session, language, labels),
            style = PdfTextStyle.MUTED,
            spacingAfter = 8f,
        )
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

        val qr = group.entries.asSequence()
            .mapNotNull { it.qrPayload?.let(KanbanQrRecord::parse) }
            .firstOrNull()
        if (qr != null) {
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

        blocks += HistoryPdfBlock(labels.boxRecords, PdfTextStyle.SECTION, spacingAfter = 2f)
        group.entries.forEachIndexed { boxIndex, entry ->
            val boxNumber = HistoryExportTextFormatter.integer(boxIndex + 1, language)
            val managementCode = entry.barcodePayload
                ?.let(TagBarcodeRecord::parse)
                ?.managementCode
                ?: "-"
            blocks += HistoryPdfBlock(
                text = "${boxLabel(boxNumber, language, labels)}  ${labels.matchTime}: " +
                    "${HistoryExportTextFormatter.dateTime(entry.matchedAt, language, zoneId)}; " +
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
