package jp.rimtty.codematch.feature.history

import jp.rimtty.codematch.core.export.HistoryExportTextFormatter
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchSession
import java.time.Duration
import java.time.ZoneId

/** Android-free text formatting inputs shared by the history Compose surface. */
data class HistoryUiLabels(
    val title: String,
    val emptyTitle: String,
    val emptyDescription: String,
    val delete: String,
    val sessionInProgress: String,
    val sessionEnded: String,
    val sessionName: String,
    val start: String,
    val end: String,
    val status: String,
    val inspectionBoxes: String,
    val partCount: String,
    val namePlaceholder: String,
    val savePdf: String,
    val sharePdf: String,
    val matchedCodes: String,
    val noMatchesTitle: String,
    val noMatchesDescription: String,
    val detailsNotFound: String,
    val number: String,
    val firstMatch: String,
    val lastMatch: String,
    val boxRecords: String,
    val itemNumber: String,
    val qrParsed: String,
    val barcodeParsed: String,
    val fullQr: String,
    val fullBarcode: String,
    val noRecord: String,
    val back: String,
    val box: String,
    val matchedAt: String,
    val partNumber: String,
    val cardNumber: String,
    val suffix: String,
    val deliveryQuantity: String,
    val instructedQuantity: String,
    val factory: String,
    val warehouse: String,
    val supplyPoint: String,
    val managementCode: String,
)

object HistoryUiText {
    fun dateTime(
        epochMillis: Long,
        language: AppLanguage,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = HistoryExportTextFormatter.dateTime(epochMillis, language, zoneId)

    fun time(
        epochMillis: Long,
        language: AppLanguage,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = HistoryExportTextFormatter.time(epochMillis, language, zoneId)

    fun quantity(value: Double?, language: AppLanguage): String =
        HistoryExportTextFormatter.quantity(value, language)

    /**
     * Returns the rounded-up display duration without choosing a UI language.
     * The localized sentence is supplied by [HistoryUiResources].
     */
    fun durationMinutes(session: MatchSession): Long? {
        val endedAt = session.endedAt ?: return null
        return maxOf(
            1L,
            Duration.ofMillis((endedAt - session.startedAt).coerceAtLeast(0L)).toMinutes(),
        )
    }

    fun sessionAccessibilitySummary(
        session: MatchSession,
        date: String,
        count: String,
        status: String,
        separator: String,
    ): String = listOf(session.displayName, date, count, status)
        .filter { it.isNotBlank() }
        .joinToString(separator)
}
