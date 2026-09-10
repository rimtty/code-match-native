package jp.rimtty.codematch.core.export

import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchSession
import java.time.ZoneId

/**
 * Pure 検品レポート content: a short header and one table row per
 * [InspectionReportRow]. It is independent of `android.graphics.pdf` so the
 * cells can be pinned by JVM tests, exactly like [HistoryPdfContent].
 */
object InspectionPdfContent {
    /** Column widths in points of the 507 pt content width, shared with iOS. */
    private const val CONTENT_WIDTH = 507f

    fun build(
        session: MatchSession,
        language: AppLanguage = AppLanguage.JAPANESE,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): InspectionPdfDocument {
        val labels = HistoryExportTextFormatter.labels(language)
        val report = InspectionReportContent.build(session)
        val blocks = mutableListOf<InspectionPdfBlock>()

        fun text(text: String, style: PdfTextStyle, spacingAfter: Float) {
            blocks += InspectionPdfBlock.Text(HistoryPdfBlock(text, style, spacingAfter))
        }

        text(labels.inspectionTitle, PdfTextStyle.TITLE, 8f)
        if (session.displayName.isNotEmpty()) {
            text("${labels.sessionName}: ${session.displayName}", PdfTextStyle.SECTION, 4f)
        }
        text(
            "${labels.start}: ${HistoryExportTextFormatter.dateTime(session.startedAt, language, zoneId)}",
            PdfTextStyle.MUTED,
            2f,
        )
        val endedAt = session.endedAt
        if (endedAt != null) {
            text(
                "${labels.end}: ${HistoryExportTextFormatter.dateTime(endedAt, language, zoneId)}",
                PdfTextStyle.MUTED,
                2f,
            )
        } else {
            text("${labels.status}: ${labels.inProgress}", PdfTextStyle.MUTED, 2f)
        }
        val destination = session.resolvedDestination()
        if (destination != null) {
            text("${labels.destination}: ${labels.destinationName(destination)}", PdfTextStyle.MUTED, 2f)
        }
        text(
            "${labels.inspectionBoxCount}: ${HistoryExportTextFormatter.boxCount(session.matchedCount, language)}",
            PdfTextStyle.MUTED,
            2f,
        )
        val countLabel = when (report.layout) {
            InspectionLayout.SAWAI -> labels.inspectionPartCountBySuffix
            InspectionLayout.MOLTEN -> labels.deliveryNumberCount
            InspectionLayout.DENSO -> labels.inspectionPartCount
        }
        text(
            "$countLabel: ${HistoryExportTextFormatter.integer(report.rowCount, language)}",
            PdfTextStyle.MUTED,
            8f,
        )
        blocks += InspectionPdfBlock.Text(HistoryPdfBlock("", PdfTextStyle.DIVIDER, spacingAfter = 6f))

        if (report.rows.isEmpty()) {
            text(labels.noMatches, PdfTextStyle.MUTED, 8f)
        } else {
            val columns = columns(report.layout, labels)
            val checkboxColumn = columns.lastIndex
            blocks += InspectionPdfBlock.TableHeader(columns)
            report.rows.forEachIndexed { index, row ->
                blocks += InspectionPdfBlock.TableRow(
                    cells = cells(row, index, report.layout, language),
                    columns = columns,
                    checkboxColumn = checkboxColumn,
                )
            }
        }

        return InspectionPdfDocument(
            blocks = blocks,
            footerNote = labels.inspectionFooterNote,
            generatedNote = labels.generatedNote,
        )
    }

    /** The column set of one layout; the 確認 column is always last. */
    fun columns(layout: InspectionLayout, labels: HistoryExportLabels): List<PdfColumn> {
        fun column(title: String, points: Float, align: PdfAlign, style: PdfTextStyle = PdfTextStyle.BODY) =
            PdfColumn(title, points / CONTENT_WIDTH, align, style)
        return when (layout) {
            InspectionLayout.SAWAI -> listOf(
                column(labels.columnNumber, 28f, PdfAlign.RIGHT),
                column(labels.columnPartNumber, 170f, PdfAlign.LEFT, PdfTextStyle.MONOSPACE_BOLD),
                column(labels.columnBoxes, 52f, PdfAlign.RIGHT, PdfTextStyle.MONOSPACE_BOLD),
                column(labels.columnDeliveryQuantityPerBox, 90f, PdfAlign.RIGHT),
                column(labels.columnTotalQuantity, 90f, PdfAlign.RIGHT),
                column(labels.columnCheck, 77f, PdfAlign.CENTER),
            )

            InspectionLayout.DENSO -> listOf(
                column(labels.columnNumber, 28f, PdfAlign.RIGHT),
                column(labels.columnPartNumber, 170f, PdfAlign.LEFT, PdfTextStyle.MONOSPACE_BOLD),
                column(labels.columnBoxes, 52f, PdfAlign.RIGHT, PdfTextStyle.MONOSPACE_BOLD),
                column(labels.columnPackQuantityPerBox, 90f, PdfAlign.RIGHT),
                column(labels.columnTotalQuantity, 90f, PdfAlign.RIGHT),
                column(labels.columnCheck, 77f, PdfAlign.CENTER),
            )

            InspectionLayout.MOLTEN -> listOf(
                column(labels.columnNumber, 28f, PdfAlign.RIGHT),
                column(labels.columnDeliveryNumber, 82f, PdfAlign.LEFT, PdfTextStyle.MONOSPACE_BOLD),
                column(labels.columnPartNumber, 96f, PdfAlign.LEFT, PdfTextStyle.MONOSPACE_BOLD),
                column(labels.columnDeliveryDestination, 52f, PdfAlign.LEFT),
                column(labels.columnBoxes, 44f, PdfAlign.RIGHT, PdfTextStyle.MONOSPACE_BOLD),
                column(labels.columnPackQuantityPerBox, 72f, PdfAlign.RIGHT),
                column(labels.columnCumulativeQuantity, 72f, PdfAlign.RIGHT),
                column(labels.columnCheck, 61f, PdfAlign.CENTER),
            )
        }
    }

    private fun cells(
        row: InspectionReportRow,
        index: Int,
        layout: InspectionLayout,
        language: AppLanguage,
    ): List<String> {
        val number = HistoryExportTextFormatter.integer(index + 1, language)
        val boxes = HistoryExportTextFormatter.integer(row.boxCount, language)
        val perBox = HistoryExportTextFormatter.quantity(row.quantityPerBox, language)
        val total = HistoryExportTextFormatter.quantity(row.totalQuantity, language)
        return when (layout) {
            InspectionLayout.SAWAI,
            InspectionLayout.DENSO,
            -> listOf(number, row.keyText, boxes, perBox, total, "")

            InspectionLayout.MOLTEN -> listOf(
                number,
                row.keyText,
                row.partNumber ?: "-",
                row.deliveryDestination ?: "-",
                boxes,
                perBox,
                total,
                "",
            )
        }
    }
}
