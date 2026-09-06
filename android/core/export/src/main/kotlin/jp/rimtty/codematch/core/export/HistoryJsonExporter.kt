package jp.rimtty.codematch.core.export

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession

/**
 * Serializes the whole match history to one JSON document for offline analysis.
 *
 * Unlike the PDF report this export is deliberately lossless: payloads are
 * written verbatim, including the trailing spaces a Molten record depends on,
 * and a value that was never recorded is emitted as JSON `null` rather than
 * being omitted. Both apps must produce the same document, so the schema —
 * key names, key order, the ISO 8601 UTC timestamps, and the destination ids —
 * is a cross-platform contract; see the iOS `HistoryJSONExporter`.
 *
 * The document is written by hand rather than through a JSON library so the
 * module keeps working in plain JVM unit tests and stays free of an Android
 * `org.json` stub, and so escaping and key order remain explicit.
 */
object HistoryJsonExporter {
    /** Bump only together with the iOS exporter and any reader of this file. */
    const val SCHEMA_VERSION: Int = 1

    /** Value of the `platform` field written by this app. */
    const val PLATFORM: String = "android"

    const val JSON_MIME_TYPE: String = "application/json"

    /** Name of the only cache directory used for a shareable history export. */
    const val CACHE_DIRECTORY: String = "codematch-export"

    /**
     * Build the export document.
     *
     * [sessions] are written in the order given: the repository already lists
     * them newest first, and each session's entries already arrive in scan
     * sequence, so this function never reorders history.
     *
     * [appVersion] is supplied by the caller (`versionName (versionCode)`)
     * because this module has no access to the application's package info.
     */
    fun build(
        sessions: List<MatchSession>,
        exportedAt: Instant,
        platform: String = PLATFORM,
        appVersion: String,
    ): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n")
        append("  \"platform\": ").appendJsonString(platform).append(",\n")
        append("  \"appVersion\": ").appendJsonString(appVersion).append(",\n")
        append("  \"exportedAt\": ").appendJsonString(isoInstant(exportedAt)).append(",\n")
        append("  \"sessions\": ")
        appendArray(sessions, indent = "  ") { session, indent ->
            appendSession(session, indent)
        }
        append("\n}\n")
    }

    /** `codematch-history-20260907-0123.json`, stamped in the caller's zone. */
    fun fileName(
        exportedAt: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = "codematch-history-" +
        FILE_STAMP.format(exportedAt.atZone(zoneId)) +
        ".json"

    /**
     * Write one export below app-private cache storage.
     *
     * FileProvider URI creation and Intent ownership stay in the app layer, so
     * callers only need the returned file. The directory is separate from the
     * PDF cache so each export type can be granted on its own provider path.
     */
    fun writeToCache(
        context: Context,
        json: String,
        exportedAt: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): File {
        val directory = File(context.cacheDir, CACHE_DIRECTORY)
        check(directory.isDirectory || directory.mkdirs()) {
            "History export cache directory could not be created"
        }
        val output = File(directory, fileName(exportedAt, zoneId))
        check(output.canonicalFile.parentFile == directory.canonicalFile) {
            "History export filename escaped its private cache directory"
        }
        output.outputStream().use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
        }
        return output
    }

    private fun StringBuilder.appendSession(session: MatchSession, indent: String) {
        val inner = "$indent  "
        append("{\n")
        append(inner).append("\"id\": ").appendJsonString(session.id).append(",\n")
        append(inner).append("\"name\": ").appendJsonString(session.name).append(",\n")
        append(inner).append("\"destination\": ")
            .appendJsonString(session.resolvedDestination()?.id).append(",\n")
        append(inner).append("\"startedAt\": ")
            .appendJsonString(isoInstant(session.startedAt)).append(",\n")
        append(inner).append("\"endedAt\": ")
            .appendJsonString(session.endedAt?.let { isoInstant(it) }).append(",\n")
        append(inner).append("\"entries\": ")
        appendArray(session.entries, inner) { entry, entryIndent ->
            appendEntry(entry, entryIndent)
        }
        append("\n").append(indent).append("}")
    }

    private fun StringBuilder.appendEntry(entry: MatchEntry, indent: String) {
        val inner = "$indent  "
        append("{\n")
        append(inner).append("\"id\": ").appendJsonString(entry.id).append(",\n")
        append(inner).append("\"code\": ").appendJsonString(entry.code).append(",\n")
        append(inner).append("\"matchedAt\": ")
            .appendJsonString(isoInstant(entry.matchedAt)).append(",\n")
        append(inner).append("\"qrPayload\": ").appendJsonString(entry.qrPayload).append(",\n")
        append(inner).append("\"barcodePayload\": ")
            .appendJsonString(entry.barcodePayload).append("\n")
        append(indent).append("}")
    }

    private fun <T> StringBuilder.appendArray(
        values: List<T>,
        indent: String,
        appendValue: StringBuilder.(T, String) -> Unit,
    ) {
        if (values.isEmpty()) {
            append("[]")
            return
        }
        val elementIndent = "$indent  "
        append("[\n")
        values.forEachIndexed { index, value ->
            append(elementIndent)
            appendValue(value, elementIndent)
            if (index != values.lastIndex) append(",")
            append("\n")
        }
        append(indent).append("]")
    }

    /** `2026-09-07T01:23:45Z`; a null value becomes the JSON literal `null`. */
    private fun isoInstant(epochMillis: Long): String =
        isoInstant(Instant.ofEpochMilli(epochMillis))

    private fun isoInstant(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))

    /**
     * Appends a JSON string literal, or the literal `null`.
     *
     * Every character the JSON grammar forbids unescaped is escaped, and no
     * other character is altered: a payload's trailing spaces and its exact
     * byte sequence survive the round trip.
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
}
