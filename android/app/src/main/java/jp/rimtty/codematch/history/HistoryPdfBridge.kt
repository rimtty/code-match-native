package jp.rimtty.codematch.history

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import java.time.ZoneId
import jp.rimtty.codematch.core.export.HistoryPdfExporter
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchSession

internal data class PendingHistoryPdf(
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String = HistoryPdfBridge.PDF_MIME_TYPE,
) {
    init {
        require(bytes.isNotEmpty()) { "PDF bytes must not be empty" }
        require(mimeType == HistoryPdfBridge.PDF_MIME_TYPE) {
            "History export must remain an application/pdf document"
        }
        require(fileName == fileName.trim()) {
            "PDF filename must not have surrounding whitespace"
        }
        require(fileName.endsWith(".pdf", ignoreCase = true)) {
            "History export filename must use the PDF extension"
        }
        require(fileName.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "History export filename must not contain path separators or control characters"
        }
        require(".." !in fileName) { "History export filename must not contain traversal" }
    }
}

/** Android-only bridges for SAF saving and FileProvider sharing. */
internal object HistoryPdfBridge {
    const val PDF_MIME_TYPE: String = "application/pdf"

    fun createDocumentContract(): ActivityResultContracts.CreateDocument =
        ActivityResultContracts.CreateDocument(PDF_MIME_TYPE)

    fun createDocument(
        session: MatchSession,
        language: AppLanguage,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): PendingHistoryPdf = PendingHistoryPdf(
        bytes = HistoryPdfExporter.generate(session, language, zoneId),
        fileName = HistoryPdfExporter.fileName(session, language, zoneId),
    )

    fun writeDocument(
        contentResolver: ContentResolver,
        destination: Uri,
        document: PendingHistoryPdf,
    ): Boolean = writeDocument(
        destination = destination,
        openOutputStream = { uri -> contentResolver.openOutputStream(uri) },
        document = document,
    )

    /** Injectable output-stream seam for deterministic SAF write tests. */
    internal fun writeDocument(
        destination: Uri,
        openOutputStream: (Uri) -> OutputStream?,
        document: PendingHistoryPdf,
    ): Boolean {
        val output = runCatching { openOutputStream(destination) }.getOrNull() ?: return false
        return runCatching {
            output.use { stream ->
                stream.write(document.bytes)
                stream.flush()
            }
            true
        }.getOrDefault(false)
    }

    fun createShareChooser(context: Context, file: File): Intent? = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        Intent.createChooser(createShareIntent(uri), null)
    }.getOrNull()

    internal fun createShareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = PDF_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri(null, uri)
    }
}
