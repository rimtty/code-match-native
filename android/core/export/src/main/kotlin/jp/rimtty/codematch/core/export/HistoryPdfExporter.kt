package jp.rimtty.codematch.core.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfDocument.Page
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchSession

/** Android PdfDocument renderer for the history report. */
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
    ): ByteArray {
        val document = PdfDocument()
        val output = ByteArrayOutputStream()
        var page: Page? = null
        var canvas: Canvas? = null
        var cursor = MARGIN
        var pageNumber = 0

        fun beginPage() {
            page?.let(document::finishPage)
            pageNumber += 1
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            page = document.startPage(info)
            canvas = page?.canvas
            cursor = MARGIN
        }

        fun finishCurrentPage() {
            page?.let(document::finishPage)
            page = null
            canvas = null
        }

        fun ensureLineSpace(lineHeight: Float) {
            if (cursor + lineHeight > PAGE_HEIGHT - MARGIN) beginPage()
        }

        try {
            beginPage()
            val contentWidth = PAGE_WIDTH - MARGIN * 2f
            HistoryPdfContent.build(session, language, zoneId).forEach { block ->
                if (block.style == PdfTextStyle.DIVIDER) {
                    ensureLineSpace(DIVIDER_HEIGHT)
                    val dividerY = cursor + DIVIDER_OFFSET
                    canvas?.drawLine(
                        MARGIN,
                        dividerY,
                        PAGE_WIDTH - MARGIN,
                        dividerY,
                        dividerPaint,
                    )
                    cursor += DIVIDER_HEIGHT + block.spacingAfter
                    return@forEach
                }

                val paint = paintFor(block.style)
                val lineHeight = paint.textSize * LINE_HEIGHT_MULTIPLIER
                wrap(block.text, paint, contentWidth).forEach { line ->
                    ensureLineSpace(lineHeight)
                    val baseline = cursor - paint.ascent()
                    canvas?.drawText(line, MARGIN, baseline, paint)
                    cursor += lineHeight
                }
                cursor += block.spacingAfter
            }
            finishCurrentPage()
            document.writeTo(output)
            return output.toByteArray()
        } finally {
            // PdfDocument.close is idempotent and also releases native state if
            // rendering or writing fails before the normal finish path.
            finishCurrentPage()
            document.close()
        }
    }

    /** Filename suitable for CreateDocument and the cache/share helper. */
    fun fileName(
        session: MatchSession,
        language: AppLanguage = AppLanguage.JAPANESE,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = HistoryExportTextFormatter.fileName(session, language, zoneId)

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
    ): File {
        val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        val output = File(directory, fileName(session, language, zoneId))
        output.outputStream().use { stream ->
            stream.write(generate(session, language, zoneId))
        }
        return output
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

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 210, 210)
        strokeWidth = 0.7f
    }

    private const val CACHE_DIRECTORY = "codematch-pdf"
    private const val LINE_HEIGHT_MULTIPLIER = 1.35f
    private const val DIVIDER_HEIGHT = 10f
    private const val DIVIDER_OFFSET = 4f
}
