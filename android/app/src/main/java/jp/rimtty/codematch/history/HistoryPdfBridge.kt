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

/** Failure categories intentionally contain no exception, URI, or path data. */
internal enum class HistoryPdfFailure {
    GENERATION_FAILED,
    SAVE_PICKER_LAUNCH_FAILED,
    DESTINATION_OPEN_FAILED,
    DESTINATION_WRITE_FAILED,
    CACHE_WRITE_FAILED,
    FILE_PROVIDER_FAILED,
    SHARE_LAUNCH_FAILED,
}

internal sealed interface HistoryPdfResult<out T> {
    data class Success<T>(val value: T) : HistoryPdfResult<T>

    data class Failure(val reason: HistoryPdfFailure) : HistoryPdfResult<Nothing>
}

/** Result of the CreateDocument callback; picker cancellation is not a failure. */
internal sealed interface HistoryPdfPickerResult {
    data object Cancelled : HistoryPdfPickerResult

    data class Selected(
        val destination: Uri,
        val document: PendingHistoryPdf,
    ) : HistoryPdfPickerResult

    data object MissingPendingDocument : HistoryPdfPickerResult
}

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

    internal fun launchDocumentPicker(
        launch: (String) -> Unit,
        fileName: String,
    ): HistoryPdfResult<Unit> = try {
        launch(fileName)
        HistoryPdfResult.Success(Unit)
    } catch (_: Exception) {
        HistoryPdfResult.Failure(HistoryPdfFailure.SAVE_PICKER_LAUNCH_FAILED)
    }

    fun createDocument(
        session: MatchSession,
        language: AppLanguage,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): HistoryPdfResult<PendingHistoryPdf> = createDocument(
        session = session,
        language = language,
        zoneId = zoneId,
        generate = { currentSession, currentLanguage, currentZone ->
            HistoryPdfExporter.generate(currentSession, currentLanguage, currentZone)
        },
        fileName = { currentSession, currentLanguage, currentZone ->
            HistoryPdfExporter.fileName(currentSession, currentLanguage, currentZone)
        },
    )

    /** Injectable generation seams keep error handling deterministic in tests. */
    internal fun createDocument(
        session: MatchSession,
        language: AppLanguage,
        zoneId: ZoneId,
        generate: (MatchSession, AppLanguage, ZoneId) -> ByteArray,
        fileName: (MatchSession, AppLanguage, ZoneId) -> String,
    ): HistoryPdfResult<PendingHistoryPdf> = try {
        HistoryPdfResult.Success(
            PendingHistoryPdf(
                bytes = generate(session, language, zoneId),
                fileName = fileName(session, language, zoneId),
            ),
        )
    } catch (_: Exception) {
        HistoryPdfResult.Failure(HistoryPdfFailure.GENERATION_FAILED)
    }

    fun writeDocument(
        contentResolver: ContentResolver,
        destination: Uri,
        document: PendingHistoryPdf,
    ): HistoryPdfResult<Unit> = writeDocument(
        destination = destination,
        openOutputStream = { uri -> contentResolver.openOutputStream(uri) },
        document = document,
    )

    /** Injectable output-stream seam for deterministic SAF write tests. */
    internal fun writeDocument(
        destination: Uri,
        openOutputStream: (Uri) -> OutputStream?,
        document: PendingHistoryPdf,
    ): HistoryPdfResult<Unit> {
        val output = try {
            openOutputStream(destination)
        } catch (_: Exception) {
            return HistoryPdfResult.Failure(HistoryPdfFailure.DESTINATION_OPEN_FAILED)
        } ?: return HistoryPdfResult.Failure(HistoryPdfFailure.DESTINATION_OPEN_FAILED)

        return try {
            output.use { stream ->
                stream.write(document.bytes)
                stream.flush()
            }
            HistoryPdfResult.Success(Unit)
        } catch (_: Exception) {
            HistoryPdfResult.Failure(HistoryPdfFailure.DESTINATION_WRITE_FAILED)
        }
    }

    fun writeShareCache(
        context: Context,
        session: MatchSession,
        language: AppLanguage,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): HistoryPdfResult<File> = try {
        HistoryPdfResult.Success(
            HistoryPdfExporter.writeToCache(context, session, language, zoneId),
        )
    } catch (_: Exception) {
        HistoryPdfResult.Failure(HistoryPdfFailure.CACHE_WRITE_FAILED)
    }

    /** Injectable cache seam for deterministic generation/cache failure tests. */
    internal fun writeShareCache(
        context: Context,
        session: MatchSession,
        language: AppLanguage,
        zoneId: ZoneId,
        writeToCache: (Context, MatchSession, AppLanguage, ZoneId) -> File,
    ): HistoryPdfResult<File> = try {
        HistoryPdfResult.Success(writeToCache(context, session, language, zoneId))
    } catch (_: Exception) {
        HistoryPdfResult.Failure(HistoryPdfFailure.CACHE_WRITE_FAILED)
    }

    fun createShareChooser(context: Context, file: File): HistoryPdfResult<Intent> = try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        HistoryPdfResult.Success(Intent.createChooser(createShareIntent(uri), null))
    } catch (_: Exception) {
        HistoryPdfResult.Failure(HistoryPdfFailure.FILE_PROVIDER_FAILED)
    }

    internal fun launchShare(context: Context, chooser: Intent): HistoryPdfResult<Unit> = try {
        context.startActivity(chooser)
        HistoryPdfResult.Success(Unit)
    } catch (_: Exception) {
        HistoryPdfResult.Failure(HistoryPdfFailure.SHARE_LAUNCH_FAILED)
    }

    internal fun resolveDocumentPickerResult(
        destination: Uri?,
        pending: PendingHistoryPdf?,
    ): HistoryPdfPickerResult = when {
        destination == null -> HistoryPdfPickerResult.Cancelled
        pending == null -> HistoryPdfPickerResult.MissingPendingDocument
        else -> HistoryPdfPickerResult.Selected(destination, pending)
    }

    internal fun createShareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = PDF_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri(null, uri)
    }
}
