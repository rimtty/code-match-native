package jp.rimtty.codematch.core.export

/** Horizontal alignment of a table cell. */
enum class PdfAlign {
    LEFT,
    RIGHT,
    CENTER,
}

/**
 * One column of a PDF table.
 *
 * [weight] is the column's share of the page content width; the weights of a
 * table sum to 1 so the renderer, not the content, owns the page size.
 */
data class PdfColumn(
    val title: String,
    val weight: Float,
    val align: PdfAlign = PdfAlign.LEFT,
    val style: PdfTextStyle = PdfTextStyle.BODY,
)

/** One logical block of the inspection report before page layout. */
sealed interface InspectionPdfBlock {
    /** Plain text, rendered exactly like a history report block. */
    data class Text(val block: HistoryPdfBlock) : InspectionPdfBlock

    /** The column titles; the renderer repeats them at the top of every page. */
    data class TableHeader(val columns: List<PdfColumn>) : InspectionPdfBlock

    /**
     * One table row. [cells] has one entry per column; [checkboxColumn] names
     * the column drawn as an empty square for the operator's pen instead of text.
     */
    data class TableRow(
        val cells: List<String>,
        val columns: List<PdfColumn>,
        val checkboxColumn: Int? = null,
    ) : InspectionPdfBlock {
        init {
            require(cells.size == columns.size) { "row has ${cells.size} cells for ${columns.size} columns" }
        }
    }
}

/**
 * Pure inspection report content: the blocks plus the two footer lines the
 * renderer prints on every page next to the page number.
 */
data class InspectionPdfDocument(
    val blocks: List<InspectionPdfBlock>,
    val footerNote: String,
    val generatedNote: String,
)
