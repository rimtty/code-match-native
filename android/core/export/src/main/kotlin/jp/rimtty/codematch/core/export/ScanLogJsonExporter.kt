package jp.rimtty.codematch.core.export

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import jp.rimtty.codematch.core.model.ScanLogEvent

/**
 * Serializes the on-device scan log as JSON Lines.
 *
 * The first line is a header object describing the export; every following
 * line is exactly one event. A line-oriented document is deliberate: the log
 * is read with `grep`/`jq` during a field investigation, and a truncated file
 * still parses up to its last complete line.
 *
 * Key names, key order, the ISO 8601 timestamps, and the vocabularies of
 * `event`/`reason`/`step` are a cross-platform contract shared with the iOS
 * exporter, so a null is written out explicitly rather than omitted. As in
 * [HistoryJsonExporter] the document is built by hand instead of through a
 * JSON library, which keeps this class unit-testable on a plain JVM and keeps
 * escaping and key order visible.
 */
object ScanLogJsonExporter {
    /** Bump only together with the iOS exporter and any reader of this file. */
    const val SCHEMA_VERSION: Int = 1

    /** Value of the header's `platform` field written by this app. */
    const val PLATFORM: String = "android"

    /** Retention cap reported in the header; see `ScanLogRepository`. */
    const val EVENT_LIMIT: Int = 5_000

    const val MIME_TYPE: String = "text/plain"

    /** Shared with the history JSON export; the FileProvider exposes it. */
    const val CACHE_DIRECTORY: String = HistoryJsonExporter.CACHE_DIRECTORY

    /**
     * Header line values the module cannot derive itself.
     *
     * [appVersion] is supplied by the caller (`versionName (versionCode)`)
     * because this module has no access to the application's package info.
     */
    data class ExportHeader(
        val appVersion: String,
        val exportedAt: Instant,
        val platform: String = PLATFORM,
        val limit: Int = EVENT_LIMIT,
    )

    /**
     * Build the JSON Lines document.
     *
     * [events] are written in the order given: the repository lists them
     * oldest first, so a reader sees the session as it happened.
     */
    fun buildJsonLines(
        events: List<ScanLogEvent>,
        header: ExportHeader,
    ): String = buildString {
        append("{")
        append("\"type\":").appendJsonString("header").append(",")
        append("\"schemaVersion\":").append(SCHEMA_VERSION).append(",")
        append("\"platform\":").appendJsonString(header.platform).append(",")
        append("\"appVersion\":").appendJsonString(header.appVersion).append(",")
        append("\"exportedAt\":").appendJsonString(isoSeconds(header.exportedAt)).append(",")
        append("\"eventCount\":").append(events.size).append(",")
        append("\"limit\":").append(header.limit)
        append("}\n")
        events.forEach { event ->
            append("{")
            append("\"at\":").appendJsonString(isoMillis(event.atEpochMillis)).append(",")
            append("\"session\":").appendJsonString(event.sessionId).append(",")
            append("\"source\":").appendJsonString(event.source).append(",")
            append("\"step\":").appendJsonString(event.step).append(",")
            append("\"event\":").appendJsonString(event.event).append(",")
            append("\"reason\":").appendJsonString(event.reason).append(",")
            append("\"destination\":").appendJsonString(event.destination?.id).append(",")
            append("\"qr\":").appendJsonString(event.qrPayload).append(",")
            append("\"barcode\":").appendJsonString(event.barcodePayload).append(",")
            append("\"code\":").appendJsonString(event.code).append(",")
            append("\"boxNumber\":").appendJsonNumber(event.boxNumber).append(",")
            append("\"message\":").appendJsonString(event.message)
            append("}\n")
        }
    }

    /** `codematch-scan-log-20260908-0123.jsonl`, stamped in the caller's zone. */
    fun fileName(
        exportedAt: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = "codematch-scan-log-" +
        FILE_STAMP.format(exportedAt.atZone(zoneId)) +
        ".jsonl"

    /**
     * Write one export below app-private cache storage.
     *
     * It shares the history export's cache subdirectory, which is the only
     * cache path the FileProvider exposes for data files. FileProvider URI
     * creation and Intent ownership stay in the app layer, so callers only
     * need the returned file.
     */
    fun writeToCache(
        context: Context,
        text: String,
        exportedAt: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): File {
        val directory = File(context.cacheDir, CACHE_DIRECTORY)
        check(directory.isDirectory || directory.mkdirs()) {
            "Scan log export cache directory could not be created"
        }
        val output = File(directory, fileName(exportedAt, zoneId))
        check(output.canonicalFile.parentFile == directory.canonicalFile) {
            "Scan log export filename escaped its private cache directory"
        }
        output.outputStream().use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
        }
        return output
    }

    /** `2026-09-08T01:23:45.678Z`; milliseconds are always present. */
    private fun isoMillis(epochMillis: Long): String =
        EVENT_STAMP.format(Instant.ofEpochMilli(epochMillis))

    /** `2026-09-08T01:23:45Z`, matching the history export's header. */
    private fun isoSeconds(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))

    private fun StringBuilder.appendJsonNumber(value: Int?): StringBuilder =
        if (value == null) append("null") else append(value)

    /**
     * Appends a JSON string literal, or the literal `null`.
     *
     * Every character the JSON grammar forbids unescaped is escaped, and no
     * other character is altered: a payload's trailing spaces and its exact
     * byte sequence survive the round trip. A newline inside a payload becomes
     * `\n`, which is what keeps one event on one line.
     */
    private fun StringBuilder.appendJsonString(value: String?): StringBuilder {
        if (value == null) return append("null")
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append(String.format(Locale.ROOT, "\\u%04x", character.code))
                } else {
                    append(character)
                }
            }
        }
        return append('"')
    }

    private val FILE_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)

    private val EVENT_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC)
}
