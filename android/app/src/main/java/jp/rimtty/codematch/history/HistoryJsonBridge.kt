package jp.rimtty.codematch.history

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import jp.rimtty.codematch.core.export.HistoryJsonExporter
import jp.rimtty.codematch.core.model.MatchSession

/** Failure categories intentionally contain no exception, URI, or path data. */
internal enum class HistoryJsonFailure {
    CACHE_WRITE_FAILED,
    FILE_PROVIDER_FAILED,
    SHARE_LAUNCH_FAILED,
}

internal sealed interface HistoryJsonResult<out T> {
    data class Success<T>(val value: T) : HistoryJsonResult<T>

    data class Failure(val reason: HistoryJsonFailure) : HistoryJsonResult<Nothing>
}

/**
 * Android bridge for sharing the whole history as one JSON document.
 *
 * The export is a cross-platform data file rather than a report, so it is only
 * shared: there is no SAF save flow and, unlike the PDF, nothing is ever
 * written outside the app-private cache by this app itself. Serialization
 * stays in `core/export`; this object owns FileProvider and Intent handling.
 */
internal object HistoryJsonBridge {
    const val JSON_MIME_TYPE: String = HistoryJsonExporter.JSON_MIME_TYPE

    /** Version string embedded in the export, formatted `versionName (versionCode)`. */
    fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = info.versionName?.takeIf { it.isNotBlank() } ?: UNKNOWN_VERSION
        "$name (${info.longVersionCode})"
    } catch (_: Exception) {
        UNKNOWN_VERSION
    }

    fun writeShareCache(
        context: Context,
        sessions: List<MatchSession>,
        exportedAt: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): HistoryJsonResult<File> = writeShareCache(
        context = context,
        sessions = sessions,
        exportedAt = exportedAt,
        zoneId = zoneId,
        writeToCache = { currentContext, json, currentExportedAt, currentZone ->
            HistoryJsonExporter.writeToCache(currentContext, json, currentExportedAt, currentZone)
        },
    )

    /** Injectable cache seam so a write failure can be tested deterministically. */
    internal fun writeShareCache(
        context: Context,
        sessions: List<MatchSession>,
        exportedAt: Instant,
        zoneId: ZoneId,
        writeToCache: (Context, String, Instant, ZoneId) -> File,
    ): HistoryJsonResult<File> = try {
        val json = HistoryJsonExporter.build(
            sessions = sessions,
            exportedAt = exportedAt,
            appVersion = appVersion(context),
        )
        HistoryJsonResult.Success(writeToCache(context, json, exportedAt, zoneId))
    } catch (_: Exception) {
        HistoryJsonResult.Failure(HistoryJsonFailure.CACHE_WRITE_FAILED)
    }

    fun createShareChooser(context: Context, file: File): HistoryJsonResult<Intent> = try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        HistoryJsonResult.Success(Intent.createChooser(createShareIntent(uri), null))
    } catch (_: Exception) {
        HistoryJsonResult.Failure(HistoryJsonFailure.FILE_PROVIDER_FAILED)
    }

    internal fun launchShare(context: Context, chooser: Intent): HistoryJsonResult<Unit> = try {
        context.startActivity(chooser)
        HistoryJsonResult.Success(Unit)
    } catch (_: Exception) {
        HistoryJsonResult.Failure(HistoryJsonFailure.SHARE_LAUNCH_FAILED)
    }

    internal fun createShareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = JSON_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri(null, uri)
    }

    private const val UNKNOWN_VERSION = "unknown"
}
