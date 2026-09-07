package jp.rimtty.codematch.core.export

import jp.rimtty.codematch.core.matching.CodeMatcher
import jp.rimtty.codematch.core.matching.KanbanQrRecord
import jp.rimtty.codematch.core.matching.TagBarcodeRecord
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.GroupedMatchEntry
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession
import java.time.ZoneId

/** Visual intent for a platform PDF renderer. */
enum class PdfTextStyle {
    TITLE,
    SECTION,
    BODY,
    MUTED,
    MONOSPACE,
    MONOSPACE_BOLD,
    FOOTER,
    DIVIDER,
}

/** One logical PDF block before it is wrapped to the physical page width. */
data class HistoryPdfBlock(
    val text: String,
    val style: PdfTextStyle = PdfTextStyle.BODY,
    val spacingAfter: Float = 4f,
)

/**
 * Pure history report content. It contains every stored group and entry and
 * is intentionally independent from [android.graphics.pdf.PdfDocument], so
 * content parity can be tested on the local JVM.
 */
object HistoryPdfContent {
    fun build(
        session: MatchSession,
        language: AppLanguage = AppLanguage.JAPANESE,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<HistoryPdfBlock> {
        val labels = HistoryExportTextFormatter.labels(language)
        val blocks = mutableListOf<HistoryPdfBlock>()

        blocks += HistoryPdfBlock(labels.reportTitle, PdfTextStyle.TITLE, spacingAfter = 8f)
        if (session.displayName.isNotEmpty()) {
            blocks += HistoryPdfBlock(
                text = "${labels.sessionName}: ${session.displayName}",
                style = PdfTextStyle.SECTION,
                spacingAfter = 4f,
            )
        }
        blocks += HistoryPdfBlock(
            text = "${labels.start}: ${HistoryExportTextFormatter.dateTime(session.startedAt, language, zoneId)}",
            style = PdfTextStyle.MUTED,
            spacingAfter = 2f,
        )
        val endedAt = session.endedAt
        if (endedAt != null) {
            blocks += HistoryPdfBlock(
                text = "${labels.end}: ${HistoryExportTextFormatter.dateTime(endedAt, language, zoneId)}",
                style = PdfTextStyle.MUTED,
                spacingAfter = 2f,
            )
        } else {
            blocks += HistoryPdfBlock(
                text = "${labels.status}: ${labels.inProgress}",
                style = PdfTextStyle.MUTED,
                spacingAfter = 2f,
            )
        }
        val destination = session.resolvedDestination()
        if (destination != null) {
            blocks += HistoryPdfBlock(
                text = "${labels.destination}: ${labels.destinationName(destination)}",
                style = PdfTextStyle.MUTED,
                spacingAfter = 2f,
            )
        }
        // Sawai slips are counted per part number, so only a Molten report adds
        // the delivery-number total; the Sawai header stays exactly as before.
        val deliveryNumberCount = if (destination == Destination.MOLTEN) {
            session.entries.moltenDeliveryGroups().size
        } else {
            null
        }
        blocks += HistoryPdfBlock(
            text = sessionSummary(session, language, labels),
            style = PdfTextStyle.MUTED,
            spacingAfter = if (deliveryNumberCount == null) 8f else 2f,
        )
        if (deliveryNumberCount != null) {
            blocks += HistoryPdfBlock(
                text = "${labels.deliveryNumberCount}: " +
                    HistoryExportTextFormatter.integer(deliveryNumberCount, language),
                style = PdfTextStyle.MUTED,
                spacingAfter = 8f,
            )
        }
        blocks += HistoryPdfBlock("", PdfTextStyle.DIVIDER, spacingAfter = 6f)

        if (session.entries.isEmpty()) {
            blocks += HistoryPdfBlock(labels.noMatches, PdfTextStyle.MUTED, spacingAfter = 8f)
        } else {
            session.groupedEntries.forEachIndexed { groupIndex, group ->
                appendGroup(blocks, group, groupIndex, language, zoneId, labels)
                blocks += HistoryPdfBlock("", PdfTextStyle.DIVIDER, spacingAfter = 6f)
            }
        }

        blocks += HistoryPdfBlock(labels.generatedNote, PdfTextStyle.FOOTER, spacingAfter = 0f)
        return blocks
    }

    private fun appendGroup(
        blocks: MutableList<HistoryPdfBlock>,
        group: GroupedMatchEntry,
        groupIndex: Int,
        language: AppLanguage,
        zoneId: ZoneId,
        labels: HistoryExportLabels,
    ) {
        val number = HistoryExportTextFormatter.integer(groupIndex + 1, language)
        val count = HistoryExportTextFormatter.boxCount(group.boxCount, language)
        blocks += HistoryPdfBlock(
            text = "#$number ${group.code} ($count)",
            style = PdfTextStyle.MONOSPACE_BOLD,
            spacingAfter = 2f,
        )

        val first = HistoryExportTextFormatter.dateTime(group.firstMatchedAt, language, zoneId)
        val timeText = if (group.boxCount > 1) {
            val last = HistoryExportTextFormatter.dateTime(group.lastMatchedAt, language, zoneId)
            if (language == AppLanguage.JAPANESE) "$first 〜 $last" else "$first – $last"
        } else {
            first
        }
        blocks += HistoryPdfBlock(
            text = "${labels.matchTime}: $timeText",
            style = PdfTextStyle.MUTED,
            spacingAfter = 4f,
        )

        // The group is a part number, but a Molten part repeats across delivery
        // numbers, so its boxes are reported per delivery number instead.
        val destination = group.entries.asSequence()
            .mapNotNull { it.qrPayload }
            .firstOrNull()
            ?.let(CodeMatcher::detectDestination)
        when (destination) {
            Destination.MOLTEN -> appendMoltenDeliveries(blocks, group, language, zoneId, labels)
            // KanbanQrRecord.parse is lenient enough to accept a Denso payload,
            // so the destination - not the parse result - picks the branch.
            Destination.DENSO -> appendDensoKanban(blocks, group, language, zoneId, labels)
            else -> {
                appendSawaiDelivery(blocks, group, language, labels)
                blocks += HistoryPdfBlock(labels.boxRecords, PdfTextStyle.SECTION, spacingAfter = 2f)
                group.entries.forEachIndexed { boxIndex, entry ->
                    appendBoxRecord(blocks, entry, boxIndex + 1, null, null, language, zoneId, labels)
                }
            }
        }
    }

    /**
     * The kanban block of one Denso part number, then every box of it.
     *
     * A Denso kanban repeats the same part fields on every box and differs only
     * in the kanban serial, so the shared fields are printed once from the first
     * box and the serial travels with each box record. Boxes are counted per
     * part number, exactly like Sawai.
     */
    private fun appendDensoKanban(
        blocks: MutableList<HistoryPdfBlock>,
        group: GroupedMatchEntry,
        language: AppLanguage,
        zoneId: ZoneId,
        labels: HistoryExportLabels,
    ) {
        val record = group.entries.asSequence()
            .mapNotNull { it.densoRecord() }
            .firstOrNull()
        if (record != null) {
            blocks += HistoryPdfBlock(labels.deliveryInformation, PdfTextStyle.SECTION, spacingAfter = 2f)
            blocks += HistoryPdfBlock(
                text = segments(
                    "${labels.moltenPartNumber}: " +
                        CodeMatcher.formatPartNumber(record.partNumber, Destination.DENSO),
                    "${labels.packQuantity}: " +
                        HistoryExportTextFormatter.integer(record.packQuantity, language),
                    record.instructedQuantity?.let {
                        "${labels.instructedQuantity}: " +
                            HistoryExportTextFormatter.integer(it, language)
                    },
                ),
                style = PdfTextStyle.BODY,
                spacingAfter = 2f,
            )
            blocks += HistoryPdfBlock(
                text = segments(
                    record.nextProcess?.let { "${labels.nextProcess}: $it" },
                    record.instructionCode?.let { "${labels.instructionCode}: $it" },
                    record.formattedDeliveryDate?.let { "${labels.deliveryDate}: $it" },
                    record.deliveryRun?.let { "${labels.deliveryRun}: $it" },
                ),
                style = PdfTextStyle.BODY,
                spacingAfter = 2f,
            )
            blocks += HistoryPdfBlock(
                text = segments(
                    record.managementNumber?.let { "${labels.managementNumber}: $it" },
                    record.itemNumber?.let { "${labels.densoItemNumber}: $it" },
                    record.receivingCode?.let { "${labels.receivingCode}: $it" },
                ),
                style = PdfTextStyle.BODY,
                spacingAfter = 4f,
            )
        }

        blocks += HistoryPdfBlock(labels.boxRecords, PdfTextStyle.SECTION, spacingAfter = 2f)
        group.entries.forEachIndexed { boxIndex, entry ->
            appendBoxRecord(
                blocks,
                entry,
                boxIndex + 1,
                null,
                entry.densoRecord()?.kanbanSerial,
                language,
                zoneId,
                labels,
            )
        }
    }

    /** Joins the present segments with the report's `; ` convention. */
    private fun segments(vararg values: String?): String =
        values.filterNotNull().joinToString("; ")

    private fun appendSawaiDelivery(
        blocks: MutableList<HistoryPdfBlock>,
        group: GroupedMatchEntry,
        language: AppLanguage,
        labels: HistoryExportLabels,
    ) {
        val qr = group.entries.asSequence()
            .mapNotNull { it.qrPayload?.let(KanbanQrRecord::parse) }
            .firstOrNull()
            ?: return

        blocks += HistoryPdfBlock(labels.deliveryInformation, PdfTextStyle.SECTION, spacingAfter = 2f)
        val suffix = qr.partSuffix?.let { " (${labels.suffix} $it)" }.orEmpty()
        blocks += HistoryPdfBlock(
            text = "${labels.itemNumber}: " +
                "${CodeMatcher.formatPartNumber(qr.partNumber, Destination.SAWAI)}$suffix; " +
                "${labels.cardNumber}: ${qr.cardNumber}",
            style = PdfTextStyle.BODY,
            spacingAfter = 2f,
        )
        blocks += HistoryPdfBlock(
            text = "${labels.deliveryQuantity}: " +
                "${HistoryExportTextFormatter.quantity(qr.deliveryQuantity, language)}; " +
                "${labels.instructedQuantity}: " +
                HistoryExportTextFormatter.quantity(qr.instructedQuantity, language),
            style = PdfTextStyle.BODY,
            spacingAfter = 2f,
        )
        blocks += HistoryPdfBlock(
            text = "${labels.factory}: ${qr.factoryCode ?: "-"}; " +
                "${labels.warehouse}: ${qr.warehouseCode ?: "-"}; " +
                "${labels.supplyPoint}: ${qr.supplyPointCode ?: "-"}",
            style = PdfTextStyle.BODY,
            spacingAfter = 4f,
        )
    }

    /**
     * One delivery block per delivery number, each followed by its own boxes.
     * The box index restarts inside a delivery number because that is the unit
     * an operator counts against the slip.
     */
    private fun appendMoltenDeliveries(
        blocks: MutableList<HistoryPdfBlock>,
        group: GroupedMatchEntry,
        language: AppLanguage,
        zoneId: ZoneId,
        labels: HistoryExportLabels,
    ) {
        val deliveries = group.entries.moltenDeliveryGroups()
        deliveries.forEach { delivery ->
            val record = delivery.record
            blocks += HistoryPdfBlock(labels.deliveryInformation, PdfTextStyle.SECTION, spacingAfter = 2f)
            blocks += HistoryPdfBlock(
                text = "${labels.deliveryNumber}: ${delivery.deliveryNumber}" +
                    deliverySummary(delivery, language, labels),
                style = PdfTextStyle.BODY,
                spacingAfter = 2f,
            )
            blocks += HistoryPdfBlock(
                text = "${labels.moltenPartNumber}: " +
                    "${CodeMatcher.formatPartNumber(record.partNumber, Destination.MOLTEN)}; " +
                    "${labels.ordererCode}: ${record.ordererCode}",
                style = PdfTextStyle.BODY,
                spacingAfter = 2f,
            )
            blocks += HistoryPdfBlock(
                text = "${labels.deliveryDestination}: ${record.deliveryDestination}; " +
                    "${labels.tyLocation}: ${record.tyLocation ?: "-"}; " +
                    "${labels.supplyPoint}: ${record.supplyPoint}",
                style = PdfTextStyle.BODY,
                spacingAfter = 2f,
            )
            blocks += HistoryPdfBlock(
                text = "${labels.instructionDate}: ${formatMoltenDate(record.instructionDate)}; " +
                    "${labels.instructionTime}: ${formatMoltenTime(record.instructionTime) ?: "-"}",
                style = PdfTextStyle.BODY,
                spacingAfter = 4f,
            )
            delivery.entries.forEachIndexed { boxIndex, entry ->
                appendBoxRecord(
                    blocks,
                    entry,
                    boxIndex + 1,
                    entry.moltenRecord()?.packQuantity,
                    null,
                    language,
                    zoneId,
                    labels,
                )
            }
        }

        // A box saved without its QR payload belongs to no delivery number, but
        // the report must never drop a recorded box.
        val reported = deliveries.flatMapTo(mutableSetOf()) { delivery ->
            delivery.entries.map(MatchEntry::id)
        }
        val remaining = group.entries.filterNot { it.id in reported }
        if (remaining.isNotEmpty()) {
            blocks += HistoryPdfBlock(labels.boxRecords, PdfTextStyle.SECTION, spacingAfter = 2f)
            remaining.forEachIndexed { boxIndex, entry ->
                appendBoxRecord(blocks, entry, boxIndex + 1, null, null, language, zoneId, labels)
            }
        }
    }

    /**
     * One box: its match time, optional pack quantity, optional kanban serial,
     * management code, and both raw payloads. [packQuantity] is null for a
     * Sawai box, whose slip carries the quantity in the delivery block instead,
     * and [kanbanSerial] is set only for Denso, where it identifies the box.
     */
    private fun appendBoxRecord(
        blocks: MutableList<HistoryPdfBlock>,
        entry: MatchEntry,
        boxIndex: Int,
        packQuantity: Int?,
        kanbanSerial: String?,
        language: AppLanguage,
        zoneId: ZoneId,
        labels: HistoryExportLabels,
    ) {
        val boxNumber = HistoryExportTextFormatter.integer(boxIndex, language)
        val managementCode = entry.barcodePayload
            ?.let(TagBarcodeRecord::parse)
            ?.managementCode
            ?: "-"
        val quantity = packQuantity
            ?.let { "${labels.packQuantity}: ${HistoryExportTextFormatter.integer(it, language)}; " }
            .orEmpty()
        val serial = kanbanSerial?.let { "${labels.kanbanSerial}: $it; " }.orEmpty()
        blocks += HistoryPdfBlock(
            text = "${boxLabel(boxNumber, language, labels)}  ${labels.matchTime}: " +
                "${HistoryExportTextFormatter.dateTime(entry.matchedAt, language, zoneId)}; " +
                quantity +
                serial +
                "${labels.managementCode}: $managementCode",
            style = PdfTextStyle.BODY,
            spacingAfter = 2f,
        )
        blocks += HistoryPdfBlock(
            text = "${labels.qrFullText}: ${entry.qrPayload ?: labels.legacyPayload}",
            style = PdfTextStyle.MONOSPACE,
            spacingAfter = 2f,
        )
        blocks += HistoryPdfBlock(
            text = "${labels.code128FullText}: ${entry.barcodePayload ?: labels.legacyPayload}",
            style = PdfTextStyle.MONOSPACE,
            spacingAfter = 5f,
        )
    }

    /** `（2箱、累計 240 個）`, following the language's parenthesis convention. */
    private fun deliverySummary(
        delivery: MoltenDeliveryGroup,
        language: AppLanguage,
        labels: HistoryExportLabels,
    ): String {
        val boxes = HistoryExportTextFormatter.boxCount(delivery.boxCount, language)
        val total = "${labels.cumulativeQuantity} " +
            "${HistoryExportTextFormatter.integer(delivery.cumulativeQuantity, language)} " +
            labels.pieceUnit
        return if (language == AppLanguage.JAPANESE) "（$boxes、$total）" else " ($boxes, $total)"
    }

    private fun sessionSummary(
        session: MatchSession,
        language: AppLanguage,
        labels: HistoryExportLabels,
    ): String = if (language == AppLanguage.JAPANESE) {
        "${labels.inspectionBoxCount}: ${HistoryExportTextFormatter.boxCount(session.matchedCount, language)}" +
            "（${labels.partCount}: " +
            HistoryExportTextFormatter.integer(session.groupedEntries.size, language) + "）"
    } else {
        "${labels.inspectionBoxCount}: ${HistoryExportTextFormatter.boxCount(session.matchedCount, language)} " +
            "(${labels.partCount}: " +
            HistoryExportTextFormatter.integer(session.groupedEntries.size, language) + ")"
    }

    private fun boxLabel(
        number: String,
        language: AppLanguage,
        labels: HistoryExportLabels,
    ): String = if (language == AppLanguage.JAPANESE) "$number${labels.box}" else "${labels.box} $number"

}
