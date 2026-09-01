package jp.rimtty.codematch.feature.history

import jp.rimtty.codematch.core.export.HistoryExportTextFormatter
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchSession
import java.time.Duration
import java.time.ZoneId

/** Small UI-only localization table; data and navigation stay outside Compose. */
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
    fun labels(language: AppLanguage): HistoryUiLabels = when (language) {
        AppLanguage.JAPANESE -> HistoryUiLabels(
            title = "照合履歴",
            emptyTitle = "履歴はまだありません",
            emptyDescription = "照合タブで記録を開始すると、一致したコードがセッション単位で保存されます。",
            delete = "削除",
            sessionInProgress = "照合中のセッション",
            sessionEnded = "終了済み",
            sessionName = "セッション名",
            start = "開始",
            end = "終了",
            status = "状態",
            inspectionBoxes = "検査箱数",
            partCount = "品番数",
            namePlaceholder = "名前を入力（任意）",
            savePdf = "PDFで保存",
            sharePdf = "共有する",
            matchedCodes = "一致したコード",
            noMatchesTitle = "一致履歴はありません",
            noMatchesDescription = "このセッションではまだ一致したコードがありません。",
            detailsNotFound = "履歴が見つかりません",
            number = "番号",
            firstMatch = "最初の照合",
            lastMatch = "最後の照合",
            boxRecords = "各箱の照合記録",
            itemNumber = "品目番号",
            qrParsed = "納品書情報（QR解析）",
            barcodeParsed = "現品票情報（バーコード解析）",
            fullQr = "QRコード（納品書兼現品票）全文",
            fullBarcode = "Code 128（現品票）全文",
            noRecord = "記録なし（旧バージョンで照合）",
            back = "戻る",
            box = "箱",
            matchedAt = "照合時刻",
            partNumber = "品番",
            cardNumber = "カード番号",
            suffix = "枝番",
            deliveryQuantity = "納入数量",
            instructedQuantity = "指示数",
            factory = "工場",
            warehouse = "受入部品庫",
            supplyPoint = "供給先",
            managementCode = "管理コード",
        )

        AppLanguage.ENGLISH -> HistoryUiLabels(
            title = "Match history",
            emptyTitle = "No history yet",
            emptyDescription = "Start a match on the scan tab to save matched codes by session.",
            delete = "Delete",
            sessionInProgress = "Session in progress",
            sessionEnded = "Finished",
            sessionName = "Session name",
            start = "Start",
            end = "End",
            status = "Status",
            inspectionBoxes = "Boxes",
            partCount = "Part numbers",
            namePlaceholder = "Name (optional)",
            savePdf = "Save PDF",
            sharePdf = "Share",
            matchedCodes = "Matched codes",
            noMatchesTitle = "No matches in this session",
            noMatchesDescription = "No matched codes have been recorded in this session.",
            detailsNotFound = "History not found",
            number = "Number",
            firstMatch = "First match",
            lastMatch = "Last match",
            boxRecords = "Box scan records",
            itemNumber = "Item number",
            qrParsed = "Delivery information (QR)",
            barcodeParsed = "Product tag information (barcode)",
            fullQr = "QR code full text",
            fullBarcode = "Code 128 full text",
            noRecord = "No record (matched in older app version)",
            back = "Back",
            box = "Box",
            matchedAt = "Matched",
            partNumber = "Part number",
            cardNumber = "Card number",
            suffix = "Suffix",
            deliveryQuantity = "Delivery quantity",
            instructedQuantity = "Instructed quantity",
            factory = "Factory",
            warehouse = "Receiving warehouse",
            supplyPoint = "Supply point",
            managementCode = "Management code",
        )
    }

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

    fun durationText(session: MatchSession, language: AppLanguage): String {
        val endedAt = session.endedAt ?: return labels(language).sessionInProgress
        val minutes = maxOf(1L, Duration.ofMillis((endedAt - session.startedAt).coerceAtLeast(0L)).toMinutes())
        return if (language == AppLanguage.JAPANESE) {
            "終了済み・約${HistoryExportTextFormatter.integer(minutes.toInt(), language)}分"
        } else {
            "Finished · about ${HistoryExportTextFormatter.integer(minutes.toInt(), language)} min"
        }
    }

    fun sessionAccessibilitySummary(
        session: MatchSession,
        language: AppLanguage,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val labels = labels(language)
        val date = dateTime(session.startedAt, language, zoneId)
        val count = if (language == AppLanguage.JAPANESE) {
            "${HistoryExportTextFormatter.integer(session.matchedCount, language)}${labels.box}"
        } else {
            "${HistoryExportTextFormatter.integer(session.matchedCount, language)} ${labels.box}"
        }
        val status = if (session.isActive) labels.sessionInProgress else labels.sessionEnded
        return listOf(session.displayName, date, count, status)
            .filter { it.isNotBlank() }
            .joinToString(", ")
    }
}
