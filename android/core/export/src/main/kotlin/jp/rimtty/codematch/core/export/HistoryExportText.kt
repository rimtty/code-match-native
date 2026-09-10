package jp.rimtty.codematch.core.export

import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchSession
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Localized labels shared by the PDF content builder and its callers. */
data class HistoryExportLabels(
    val reportTitle: String,
    val filePrefix: String,
    val sessionName: String,
    val start: String,
    val end: String,
    val status: String,
    val inProgress: String,
    val boxCount: String,
    val inspectionBoxCount: String,
    val partCount: String,
    val noMatches: String,
    val matchTime: String,
    val deliveryInformation: String,
    val itemNumber: String,
    val cardNumber: String,
    val suffix: String,
    val deliveryQuantity: String,
    val instructedQuantity: String,
    val factory: String,
    val warehouse: String,
    val supplyPoint: String,
    val boxRecords: String,
    val box: String,
    val managementCode: String,
    val qrFullText: String,
    val code128FullText: String,
    val legacyPayload: String,
    val generatedNote: String,
    val destination: String,
    val destinationSawai: String,
    val destinationMolten: String,
    val destinationDenso: String,
    val deliveryNumberCount: String,
    val ordererCode: String,
    val moltenPartNumber: String,
    val deliveryNumber: String,
    val deliveryDestination: String,
    val tyLocation: String,
    val packQuantity: String,
    val instructionDate: String,
    val instructionTime: String,
    val cumulativeQuantity: String,
    val pieceUnit: String,
    val formType: String,
    val packagingCode: String,
    val nextProcess: String,
    val instructionCode: String,
    val kanbanSerial: String,
    val managementNumber: String,
    val deliveryDate: String,
    val deliveryRun: String,
    /** Denso item number; [itemNumber] is the Sawai slip's 品目番号. */
    val densoItemNumber: String,
    val receivingCode: String,
    /** 検品レポート: title, file prefix, header counts, column titles and footer. */
    val inspectionTitle: String,
    val inspectionFilePrefix: String,
    val inspectionPartCount: String,
    val inspectionPartCountBySuffix: String,
    val columnNumber: String,
    val columnPartNumber: String,
    val columnDeliveryNumber: String,
    val columnDeliveryDestination: String,
    val columnBoxes: String,
    val columnDeliveryQuantityPerBox: String,
    val columnPackQuantityPerBox: String,
    val columnTotalQuantity: String,
    val columnCumulativeQuantity: String,
    val columnCheck: String,
    val inspectionFooterNote: String,
    /** Pre-filled report e-mail: intro per report and headings. */
    val mailIntroInspection: String,
    val mailIntroHistory: String,
    val mailSessionHeading: String,
    val mailAttachmentHeading: String,
    /** Singular and plural units are kept separately for natural English. */
    val boxCountSingular: String = boxCount,
    val boxCountPlural: String = boxCount,
) {
    /** The display name of a delivery destination. */
    fun destinationName(destination: Destination): String = when (destination) {
        Destination.SAWAI -> destinationSawai
        Destination.MOLTEN -> destinationMolten
        Destination.DENSO -> destinationDenso
    }
}

/**
 * Formatting rules used by history export.
 *
 * The formatter deliberately accepts a [ZoneId] so JVM tests can be
 * deterministic while production follows the device's current time zone.
 */
object HistoryExportTextFormatter {
    private val japaneseDateTime =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.JAPAN)
    private val englishDateTime =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.US)
    private val japaneseTime =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.JAPAN)
    private val englishTime =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.US)

    /** Localized PDF/UI labels. */
    fun labels(language: AppLanguage): HistoryExportLabels = when (language) {
        AppLanguage.JAPANESE -> HistoryExportLabels(
            reportTitle = "照合履歴レポート",
            filePrefix = "照合履歴レポート",
            sessionName = "セッション名",
            start = "開始",
            end = "終了",
            status = "状態",
            inProgress = "照合中",
            boxCount = "箱",
            inspectionBoxCount = "検査箱数",
            partCount = "品番数",
            noMatches = "一致したコードはありません。",
            matchTime = "照合時刻",
            deliveryInformation = "納品書情報",
            itemNumber = "品目番号",
            cardNumber = "カード番号",
            suffix = "枝番",
            deliveryQuantity = "納入数量",
            instructedQuantity = "指示数",
            factory = "工場",
            warehouse = "受入部品庫",
            supplyPoint = "供給先",
            boxRecords = "各箱の読み取り記録",
            box = "箱",
            managementCode = "管理コード",
            qrFullText = "QR全文",
            code128FullText = "Code 128全文",
            legacyPayload = "記録なし（旧バージョンで照合）",
            generatedNote =
                "CodeMatch により生成 — このレポートは端末内のデータから作成されています。",
            destination = "仕向地",
            destinationSawai = "澤井製作所",
            destinationMolten = "モルテン",
            destinationDenso = "デンソー",
            deliveryNumberCount = "納品番号数",
            ordererCode = "受注者",
            moltenPartNumber = "部品番号",
            deliveryNumber = "納品番号",
            deliveryDestination = "納入先",
            tyLocation = "TYロケーション",
            packQuantity = "収容数",
            instructionDate = "納入指示日(JUMP)",
            instructionTime = "時刻",
            cumulativeQuantity = "累計",
            pieceUnit = "個",
            formType = "帳票区分",
            packagingCode = "包装",
            nextProcess = "次区",
            instructionCode = "指示",
            kanbanSerial = "かんばん連番",
            managementNumber = "管理番号",
            deliveryDate = "納入日",
            deliveryRun = "便",
            densoItemNumber = "アイテムNo",
            receivingCode = "受入",
            inspectionTitle = "検品レポート",
            inspectionFilePrefix = "検品レポート",
            inspectionPartCount = "品番数",
            inspectionPartCountBySuffix = "品番数（枝番別）",
            columnNumber = "No",
            columnPartNumber = "品番",
            columnDeliveryNumber = "納品番号",
            columnDeliveryDestination = "納入先",
            columnBoxes = "箱数",
            columnDeliveryQuantityPerBox = "納入数量/箱",
            columnPackQuantityPerBox = "収容数/箱",
            columnTotalQuantity = "数量計",
            columnCumulativeQuantity = "累計",
            columnCheck = "確認",
            inspectionFooterNote =
                "検品表にあってこの一覧にない品番は、このセッションで照合されていません。",
            mailIntroInspection = "検品レポートをお送りします。",
            mailIntroHistory = "照合履歴レポートをお送りします。",
            mailSessionHeading = "■ セッション",
            mailAttachmentHeading = "■ 添付",
            boxCountSingular = "箱",
            boxCountPlural = "箱",
        )

        AppLanguage.ENGLISH -> HistoryExportLabels(
            reportTitle = "Match History Report",
            filePrefix = "MatchHistoryReport",
            sessionName = "Session name",
            start = "Start",
            end = "End",
            status = "Status",
            inProgress = "In progress",
            boxCount = "boxes",
            inspectionBoxCount = "Boxes",
            partCount = "part numbers",
            noMatches = "There are no matched codes.",
            matchTime = "Matched",
            deliveryInformation = "Delivery information",
            itemNumber = "Item number",
            cardNumber = "Card number",
            suffix = "Suffix",
            deliveryQuantity = "Delivery quantity",
            instructedQuantity = "Instructed quantity",
            factory = "Factory",
            warehouse = "Receiving warehouse",
            supplyPoint = "Supply point",
            boxRecords = "Box scan records",
            box = "Box",
            managementCode = "Management code",
            qrFullText = "QR full text",
            code128FullText = "Code 128 full text",
            legacyPayload = "No record (matched in older app version)",
            generatedNote =
                "Generated by CodeMatch — This report was created from data stored on this device.",
            destination = "Ship-to",
            destinationSawai = "Sawai Seisakusho",
            destinationMolten = "Molten",
            destinationDenso = "Denso",
            deliveryNumberCount = "Delivery numbers",
            ordererCode = "Orderer",
            moltenPartNumber = "Part number",
            deliveryNumber = "Delivery number",
            deliveryDestination = "Delivery point",
            tyLocation = "TY location",
            packQuantity = "Pack quantity",
            instructionDate = "Instruction date (JUMP)",
            instructionTime = "Time",
            cumulativeQuantity = "Total",
            pieceUnit = "pcs",
            formType = "Form type",
            packagingCode = "Packaging",
            nextProcess = "Next process",
            instructionCode = "Instruction",
            kanbanSerial = "Kanban serial",
            managementNumber = "Management number",
            deliveryDate = "Delivery date",
            deliveryRun = "Delivery run",
            densoItemNumber = "Item No.",
            receivingCode = "Receiving",
            inspectionTitle = "Inspection Report",
            inspectionFilePrefix = "InspectionReport",
            inspectionPartCount = "Part numbers",
            inspectionPartCountBySuffix = "Part numbers (by suffix)",
            columnNumber = "No",
            columnPartNumber = "Part no.",
            columnDeliveryNumber = "Delivery no.",
            columnDeliveryDestination = "Deliv. point",
            columnBoxes = "Boxes",
            columnDeliveryQuantityPerBox = "Qty / box",
            columnPackQuantityPerBox = "Pack / box",
            columnTotalQuantity = "Total qty",
            columnCumulativeQuantity = "Total",
            columnCheck = "Check",
            inspectionFooterNote =
                "Part numbers on the inspection sheet that are missing from this list were not matched in this session.",
            mailIntroInspection = "Please find the inspection report attached.",
            mailIntroHistory = "Please find the match history report attached.",
            mailSessionHeading = "Session",
            mailAttachmentHeading = "Attachment",
            boxCountSingular = "box",
            boxCountPlural = "boxes",
        )
    }

    fun dateTime(
        epochMillis: Long,
        language: AppLanguage,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val formatter = when (language) {
            AppLanguage.JAPANESE -> japaneseDateTime
            AppLanguage.ENGLISH -> englishDateTime
        }
        return formatter.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))
    }

    fun time(
        epochMillis: Long,
        language: AppLanguage,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val formatter = when (language) {
            AppLanguage.JAPANESE -> japaneseTime
            AppLanguage.ENGLISH -> englishTime
        }
        return formatter.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))
    }

    fun integer(value: Int, language: AppLanguage): String =
        NumberFormat.getIntegerInstance(locale(language)).format(value)

    fun quantity(value: Double?, language: AppLanguage): String {
        if (value == null) return "-"
        val formatter = NumberFormat.getNumberInstance(locale(language)).apply {
            minimumFractionDigits = if (value % 1.0 == 0.0) 0 else 2
            maximumFractionDigits = 2
        }
        return formatter.format(value)
    }

    /**
     * Formats a box count with the language's natural singular/plural unit.
     * Japanese does not inflect the unit; English uses "box" only for one.
     */
    fun boxCount(count: Int, language: AppLanguage): String {
        val safeCount = count.coerceAtLeast(0)
        val labels = labels(language)
        val unit = if (safeCount == 1) labels.boxCountSingular else labels.boxCountPlural
        return if (language == AppLanguage.JAPANESE) {
            "${integer(safeCount, language)}$unit"
        } else {
            "${integer(safeCount, language)} $unit"
        }
    }

    /**
     * `<prefix>_<仕向地>_<開始日時>.pdf`, e.g. `検品レポート_澤井製作所_2026-09-07_2317.pdf`.
     * Both reports are filed by destination and start time, never by the
     * session name, so the saved file and the mail attachment sort the same
     * way as the paper sheets. A session without a destination omits it.
     * Unicode names are retained, but path separators, control characters,
     * reserved punctuation, and traversal sequences are removed.
     */
    fun reportFileName(
        session: MatchSession,
        language: AppLanguage,
        zoneId: ZoneId = ZoneId.systemDefault(),
        prefix: String,
    ): String {
        val labels = labels(language)
        val destination = session.resolvedDestination()
        val parts = mutableListOf(prefix)
        if (destination != null) parts += sanitizeFileNamePart(labels.destinationName(destination), session)
        parts += sanitizeFileNamePart(dateTime(session.startedAt, language, zoneId), session)
        return parts.joinToString("_") + ".pdf"
    }

    private fun sanitizeFileNamePart(source: String, session: MatchSession): String {
        val safe = buildString(source.length) {
            source.forEach { character ->
                when {
                    character == '/' || character == '\\' -> append('-')
                    character == ':' -> Unit
                    character == ' ' || character == '\t' || character == '\n' -> append('_')
                    character.isISOControl() -> append('_')
                    character in RESERVED_FILENAME_CHARACTERS -> append('_')
                    else -> append(character)
                }
            }
        }.trim('_', '.', ' ')
            .replace("..", "_")
            .ifBlank { "session_${session.id.take(8)}" }
        return safe
    }

    private fun locale(language: AppLanguage): Locale = when (language) {
        AppLanguage.JAPANESE -> Locale.JAPAN
        AppLanguage.ENGLISH -> Locale.US
    }

    private val RESERVED_FILENAME_CHARACTERS = setOf('*', '?', '"', '<', '>', '|')
}
