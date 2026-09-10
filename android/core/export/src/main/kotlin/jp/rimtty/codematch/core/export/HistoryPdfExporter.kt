package jp.rimtty.codematch.core.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfDocument.Page
import android.text.TextPaint
import android.text.TextUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchSession

/** The two PDFs a session can be exported as. */
enum class HistoryReportKind {
    /** 照合履歴レポート: every box with its raw payloads. */
    MATCH_HISTORY,

    /** 検品レポート: one table row per part number / delivery number. */
    INSPECTION,
}

/** Android PdfDocument renderer for the history and inspection reports. */
object HistoryPdfExporter {
    /** A4 at 72 dpi, matching the iOS renderer's point dimensions. */
    const val PAGE_WIDTH: Int = 595
    const val PAGE_HEIGHT: Int = 842
    const val MARGIN: Float = 44f

    /** Generate an A4 portrait PDF, creating as many pages as the content needs. */
    fun generate(
        session: MatchSession,
        language: AppLanguage = AppLanguage.JAPANESE,
        zoneId: ZoneId = ZoneId.systemDefault(),
        kind: HistoryReportKind = HistoryReportKind.MATCH_HISTORY,
    ): ByteArray = when (kind) {
        HistoryReportKind.MATCH_HISTORY -> generateHistory(session, language, zoneId)
        HistoryReportKind.INSPECTION -> generateInspection(session, language, zoneId)
    }

    /** Filename suitable for CreateDocument and the cache/share helper. */
    fun fileName(
        session: MatchSession,
        language: AppLanguage = AppLanguage.JAPANESE,
        zoneId: ZoneId = ZoneId.systemDefault(),
        kind: HistoryReportKind = HistoryReportKind.MATCH_HISTORY,
    ): String = HistoryExportTextFormatter.fileName(
        session = session,
        language = language,
        zoneId = zoneId,
        prefix = when (kind) {
            HistoryReportKind.MATCH_HISTORY -> null
            HistoryReportKind.INSPECTION -> HistoryExportTextFormatter.labels(language).inspectionFilePrefix
        },
    )

    /** Name of the only cache directory used for a shareable history report. */
    const val CACHE_DIRECTORY: String = "codematch-pdf"

    /**
     * Write one generated report below app-private cache storage.
     *
     * FileProvider URI creation and Activity Result ownership stay in the app
     * layer; callers only need the returned file to build either integration.
     */
    fun writeToCache(
        context: Context,
        session: MatchSession,
        language: AppLanguage = AppLanguage.JAPANESE,
        zoneId: ZoneId = ZoneId.systemDefault(),
        kind: HistoryReportKind = HistoryReportKind.MATCH_HISTORY,
    ): File {
        val directory = File(context.cacheDir, CACHE_DIRECTORY)
        check(directory.isDirectory || directory.mkdirs()) {
            "History PDF cache directory could not be created"
        }
        val output = File(directory, fileName(session, language, zoneId, kind))
        check(output.canonicalFile.parentFile == directory.canonicalFile) {
            "History PDF filename escaped its private cache directory"
        }
        output.outputStream().use { stream ->
            stream.write(generate(session, language, zoneId, kind))
        }
        return output
    }

    private fun generateHistory(
        session: MatchSession,
        language: AppLanguage,
        zoneId: ZoneId,
    ): ByteArray = render(footerHeight = 0f) { cursor ->
        HistoryPdfContent.build(session, language, zoneId).forEach { block ->
            cursor.drawBlock(block)
        }
    }

    /**
     * The inspection report prints `n / N` on every page, and a PdfDocument
     * page cannot be revisited once finished. The report is therefore
     * rendered twice: the first pass only counts pages, the second prints the
     * total. Every row has a fixed height, so both passes paginate identically.
     */
    private fun generateInspection(
        session: MatchSession,
        language: AppLanguage,
        zoneId: ZoneId,
    ): ByteArray {
        val document = InspectionPdfContent.build(session, language, zoneId)
        var pageCount = 0
        render(footerHeight = FOOTER_HEIGHT) { cursor ->
            renderInspection(cursor, document, totalPages = null)
            pageCount = cursor.pageNumber
        }
        return render(footerHeight = FOOTER_HEIGHT) { cursor ->
            renderInspection(cursor, document, totalPages = pageCount)
        }
    }

    private fun renderInspection(
        cursor: PageCursor,
        document: InspectionPdfDocument,
        totalPages: Int?,
    ) {
        var currentHeader: List<PdfColumn>? = null
        cursor.onPageStart = { currentHeader?.let(cursor::drawTableHeader) }
        cursor.onPageEnd = { canvas, pageNumber ->
            drawFooter(canvas, document, pageNumber, totalPages)
        }
        document.blocks.forEach { block ->
            when (block) {
                is InspectionPdfBlock.Text -> cursor.drawBlock(block.block)
                is InspectionPdfBlock.TableHeader -> {
                    currentHeader = block.columns
                    cursor.drawTableHeader(block.columns)
                }
                is InspectionPdfBlock.TableRow -> cursor.drawTableRow(block)
            }
        }
    }

    private fun drawFooter(
        canvas: Canvas,
        document: InspectionPdfDocument,
        pageNumber: Int,
        totalPages: Int?,
    ) {
        val paint = paintFor(PdfTextStyle.FOOTER)
        val contentWidth = PAGE_WIDTH - MARGIN * 2f
        val top = PAGE_HEIGHT - MARGIN - FOOTER_HEIGHT
        val lineHeight = paint.textSize * LINE_HEIGHT_MULTIPLIER
        val pageText = "$pageNumber / ${totalPages ?: pageNumber}"
        val pageWidth = paint.measureText(pageText)
        val noteWidth = contentWidth - pageWidth - PAGE_NUMBER_GAP
        val firstBaseline = top + FOOTER_TOP_PADDING - paint.ascent()
        canvas.drawText(ellipsize(document.footerNote, paint, noteWidth), MARGIN, firstBaseline, paint)
        canvas.drawText(
            ellipsize(document.generatedNote, paint, contentWidth),
            MARGIN,
            firstBaseline + lineHeight,
            paint,
        )
        val pagePaint = Paint(paint).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(pageText, PAGE_WIDTH - MARGIN, firstBaseline, pagePaint)
    }

    private fun render(footerHeight: Float, body: (PageCursor) -> Unit): ByteArray {
        val document = PdfDocument()
        val output = ByteArrayOutputStream()
        val cursor = PageCursor(document, footerHeight)
        try {
            cursor.beginPage()
            body(cursor)
            cursor.finishCurrentPage()
            document.writeTo(output)
            return output.toByteArray()
        } finally {
            // PdfDocument.close is idempotent and also releases native state if
            // rendering or writing fails before the normal finish path.
            cursor.finishCurrentPage()
            document.close()
        }
    }

    /**
     * Page state of one rendering pass: the current page and canvas, the
     * vertical cursor, page breaks, and the hooks a report uses to repeat a
     * table header or print a footer on every page.
     */
    private class PageCursor(
        private val document: PdfDocument,
        private val footerHeight: Float,
    ) {
        private var page: Page? = null
        private var canvas: Canvas? = null
        var cursor = MARGIN
            private set
        var pageNumber = 0
            private set
        var onPageStart: (() -> Unit)? = null
        var onPageEnd: ((Canvas, Int) -> Unit)? = null

        val contentWidth = PAGE_WIDTH - MARGIN * 2f
        private val contentBottom = PAGE_HEIGHT - MARGIN - footerHeight

        fun beginPage() {
            finishCurrentPage()
            pageNumber += 1
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            val newPage = document.startPage(info)
            page = newPage
            canvas = newPage.canvas
            cursor = MARGIN
            onPageStart?.invoke()
        }

        fun finishCurrentPage() {
            val current = page ?: return
            onPageEnd?.invoke(current.canvas, pageNumber)
            document.finishPage(current)
            page = null
            canvas = null
        }

        fun ensureSpace(height: Float) {
            if (cursor + height > contentBottom) beginPage()
        }

        fun drawBlock(block: HistoryPdfBlock) {
            if (block.style == PdfTextStyle.DIVIDER) {
                ensureSpace(DIVIDER_HEIGHT)
                val dividerY = cursor + DIVIDER_OFFSET
                canvas?.drawLine(MARGIN, dividerY, PAGE_WIDTH - MARGIN, dividerY, dividerPaint)
                cursor += DIVIDER_HEIGHT + block.spacingAfter
                return
            }

            val paint = paintFor(block.style)
            val lineHeight = paint.textSize * LINE_HEIGHT_MULTIPLIER
            wrap(block.text, paint, contentWidth).forEach { line ->
                ensureSpace(lineHeight)
                val baseline = cursor - paint.ascent()
                canvas?.drawText(line, MARGIN, baseline, paint)
                cursor += lineHeight
            }
            cursor += block.spacingAfter
        }

        fun drawTableHeader(columns: List<PdfColumn>) {
            ensureSpace(TABLE_HEADER_HEIGHT)
            val canvas = canvas ?: return
            canvas.drawRect(MARGIN, cursor, PAGE_WIDTH - MARGIN, cursor + TABLE_HEADER_HEIGHT, headerFillPaint)
            columns.forEachIndexed { index, column ->
                drawCell(canvas, column.title, columns, index, tableHeaderPaint, TABLE_HEADER_HEIGHT)
            }
            val ruleY = cursor + TABLE_HEADER_HEIGHT
            canvas.drawLine(MARGIN, ruleY, PAGE_WIDTH - MARGIN, ruleY, dividerPaint)
            cursor += TABLE_HEADER_HEIGHT
        }

        fun drawTableRow(row: InspectionPdfBlock.TableRow) {
            ensureSpace(TABLE_ROW_HEIGHT)
            val canvas = canvas ?: return
            row.columns.forEachIndexed { index, column ->
                if (index == row.checkboxColumn) {
                    drawCheckbox(canvas, row.columns, index)
                } else {
                    drawCell(canvas, row.cells[index], row.columns, index, paintFor(column.style), TABLE_ROW_HEIGHT)
                }
            }
            val ruleY = cursor + TABLE_ROW_HEIGHT
            canvas.drawLine(MARGIN, ruleY, PAGE_WIDTH - MARGIN, ruleY, rowRulePaint)
            cursor += TABLE_ROW_HEIGHT
        }

        private fun drawCell(
            canvas: Canvas,
            text: String,
            columns: List<PdfColumn>,
            index: Int,
            basePaint: Paint,
            rowHeight: Float,
        ) {
            val left = MARGIN + columns.take(index).sumOf { it.weight.toDouble() }.toFloat() * contentWidth
            val width = columns[index].weight * contentWidth
            val paint = Paint(basePaint)
            val visible = ellipsize(text, paint, width - CELL_PADDING * 2)
            val baseline = cursor + (rowHeight - (paint.descent() - paint.ascent())) / 2f - paint.ascent()
            val x = when (columns[index].align) {
                PdfAlign.LEFT -> left + CELL_PADDING
                PdfAlign.RIGHT -> left + width - CELL_PADDING
                PdfAlign.CENTER -> left + width / 2f
            }
            paint.textAlign = when (columns[index].align) {
                PdfAlign.LEFT -> Paint.Align.LEFT
                PdfAlign.RIGHT -> Paint.Align.RIGHT
                PdfAlign.CENTER -> Paint.Align.CENTER
            }
            canvas.drawText(visible, x, baseline, paint)
        }

        private fun drawCheckbox(canvas: Canvas, columns: List<PdfColumn>, index: Int) {
            val left = MARGIN + columns.take(index).sumOf { it.weight.toDouble() }.toFloat() * contentWidth
            val width = columns[index].weight * contentWidth
            val centerX = left + width / 2f
            val centerY = cursor + TABLE_ROW_HEIGHT / 2f
            val half = CHECKBOX_SIZE / 2f
            canvas.drawRect(centerX - half, centerY - half, centerX + half, centerY + half, checkboxPaint)
        }
    }

    private fun paintFor(style: PdfTextStyle): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when (style) {
            PdfTextStyle.MUTED -> Color.rgb(97, 97, 97)
            PdfTextStyle.FOOTER -> Color.rgb(128, 128, 128)
            else -> Color.BLACK
        }
        textSize = when (style) {
            PdfTextStyle.TITLE -> 20f
            PdfTextStyle.SECTION -> 12f
            PdfTextStyle.BODY -> 11f
            PdfTextStyle.MUTED -> 11f
            PdfTextStyle.MONOSPACE -> 9.5f
            PdfTextStyle.MONOSPACE_BOLD -> 12f
            PdfTextStyle.FOOTER -> 8.5f
            PdfTextStyle.DIVIDER -> 1f
        }
        typeface = when (style) {
            PdfTextStyle.TITLE,
            PdfTextStyle.SECTION,
            PdfTextStyle.MONOSPACE_BOLD,
            -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

            PdfTextStyle.MONOSPACE -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
    }

    /** Wraps both localized text and long raw payloads without dropping data. */
    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        text.replace("\r\n", "\n").split('\n').forEach { paragraph ->
            if (paragraph.isEmpty()) {
                lines += ""
                return@forEach
            }
            var line = ""
            paragraph.forEach { character ->
                val candidate = line + character
                if (line.isNotEmpty() && paint.measureText(candidate) > width) {
                    lines += line
                    line = character.toString()
                } else {
                    line = candidate
                }
            }
            if (line.isNotEmpty()) lines += line
        }
        return lines.ifEmpty { listOf("") }
    }

    /** A table cell is a single line; anything wider than its column is cut with an ellipsis. */
    private fun ellipsize(text: String, paint: Paint, width: Float): String =
        TextUtils.ellipsize(text, TextPaint(paint), width.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 210, 210)
        strokeWidth = 0.7f
    }

    private val rowRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 225, 225)
        strokeWidth = 0.4f
    }

    private val headerFillPaint = Paint().apply {
        color = Color.rgb(237, 237, 237)
        style = Paint.Style.FILL
    }

    private val tableHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 11f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val checkboxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 90, 90)
        style = Paint.Style.STROKE
        strokeWidth = 0.8f
    }

    private const val LINE_HEIGHT_MULTIPLIER = 1.35f
    private const val DIVIDER_HEIGHT = 10f
    private const val DIVIDER_OFFSET = 4f
    private const val FOOTER_HEIGHT = 30f
    private const val FOOTER_TOP_PADDING = 4f
    private const val PAGE_NUMBER_GAP = 12f
    private const val TABLE_HEADER_HEIGHT = 20f
    private const val TABLE_ROW_HEIGHT = 22f
    private const val CELL_PADDING = 4f
    private const val CHECKBOX_SIZE = 9f
}
