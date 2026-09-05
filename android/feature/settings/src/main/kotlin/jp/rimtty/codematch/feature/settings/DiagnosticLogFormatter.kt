package jp.rimtty.codematch.feature.settings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import jp.rimtty.codematch.scanner.api.DiagnosticCategory
import jp.rimtty.codematch.scanner.api.DiagnosticEvent

/**
 * Renders the retained BLE diagnostic events as plain text for the share
 * sheet or a SAF document. Events carry only sanitized status strings (the
 * scanner API has no way to record a scan payload), so the export is safe to
 * hand to a third party. Mirrors the iOS "診断ログを共有" export.
 */
object DiagnosticLogFormatter {
    data class Header(
        val appVersion: String,
        val device: String,
        val system: String,
        val connection: String,
        val configuration: String,
        val illumination: String,
        val tuning: String,
    )

    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private val fileNameFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun format(
        events: List<DiagnosticEvent>,
        header: Header,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        appendLine("CodeMatch Bluetooth diagnostics (Android)")
        appendLine("app: ${header.appVersion}")
        appendLine("device: ${header.device}")
        appendLine("system: ${header.system}")
        appendLine("connection: ${header.connection}")
        appendLine("configuration: ${header.configuration}")
        appendLine("illumination: ${header.illumination}")
        appendLine("tuning: ${header.tuning}")
        appendLine("events: ${events.size}")
        appendLine()
        events
            .sortedWith(compareBy<DiagnosticEvent> { it.timestampMillis }.thenBy { it.sequence })
            .forEach { event ->
                val timestamp = timestampFormatter.format(
                    Instant.ofEpochMilli(event.timestampMillis).atZone(zoneId),
                )
                appendLine("$timestamp #${event.sequence} ${categoryLabel(event.category)}: ${event.message}")
            }
    }

    fun fileName(now: Instant, zoneId: ZoneId = ZoneId.systemDefault()): String =
        "codematch-ble-diagnostics-${fileNameFormatter.format(now.atZone(zoneId))}.txt"

    private fun categoryLabel(category: DiagnosticCategory): String = when (category) {
        DiagnosticCategory.CONNECTION -> "connection"
        DiagnosticCategory.CONFIGURATION -> "configuration"
        DiagnosticCategory.ERROR -> "error"
    }
}
