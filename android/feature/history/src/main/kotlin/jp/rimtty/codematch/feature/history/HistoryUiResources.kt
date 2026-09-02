package jp.rimtty.codematch.feature.history

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import jp.rimtty.codematch.core.export.HistoryExportTextFormatter
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchSession
import java.time.ZoneId
import java.util.Locale

/**
 * Resource-backed strings for the history Compose surface.
 *
 * [HistoryUiText] intentionally remains Android-free so date, number, and
 * accessibility calculations can be covered by ordinary JVM tests. This
 * facade is the only place where history UI text is resolved from Android
 * resources.
 */
object HistoryUiResources {
    @Composable
    fun labels(): HistoryUiLabels = HistoryUiLabels(
        title = stringResource(R.string.history_title),
        emptyTitle = stringResource(R.string.history_empty_title),
        emptyDescription = stringResource(R.string.history_empty_description),
        delete = stringResource(R.string.history_delete),
        sessionInProgress = stringResource(R.string.history_session_in_progress),
        sessionEnded = stringResource(R.string.history_session_ended),
        sessionName = stringResource(R.string.history_session_name),
        start = stringResource(R.string.history_start),
        end = stringResource(R.string.history_end),
        status = stringResource(R.string.history_status),
        inspectionBoxes = stringResource(R.string.history_inspection_boxes),
        partCount = stringResource(R.string.history_part_count),
        namePlaceholder = stringResource(R.string.history_name_placeholder),
        savePdf = stringResource(R.string.history_save_pdf),
        sharePdf = stringResource(R.string.history_share_pdf),
        matchedCodes = stringResource(R.string.history_matched_codes),
        noMatchesTitle = stringResource(R.string.history_no_matches_title),
        noMatchesDescription = stringResource(R.string.history_no_matches_description),
        detailsNotFound = stringResource(R.string.history_details_not_found),
        number = stringResource(R.string.history_number),
        firstMatch = stringResource(R.string.history_first_match),
        lastMatch = stringResource(R.string.history_last_match),
        boxRecords = stringResource(R.string.history_box_records),
        itemNumber = stringResource(R.string.history_item_number),
        qrParsed = stringResource(R.string.history_qr_parsed),
        barcodeParsed = stringResource(R.string.history_barcode_parsed),
        fullQr = stringResource(R.string.history_full_qr),
        fullBarcode = stringResource(R.string.history_full_barcode),
        noRecord = stringResource(R.string.history_no_record),
        back = stringResource(R.string.history_back),
        box = stringResource(R.string.history_box),
        matchedAt = stringResource(R.string.history_matched_at),
        partNumber = stringResource(R.string.history_part_number),
        cardNumber = stringResource(R.string.history_card_number),
        suffix = stringResource(R.string.history_suffix),
        deliveryQuantity = stringResource(R.string.history_delivery_quantity),
        instructedQuantity = stringResource(R.string.history_instructed_quantity),
        factory = stringResource(R.string.history_factory),
        warehouse = stringResource(R.string.history_warehouse),
        supplyPoint = stringResource(R.string.history_supply_point),
        managementCode = stringResource(R.string.history_management_code),
    )

    @Composable
    fun durationText(session: MatchSession, language: AppLanguage): String {
        val minutes = HistoryUiText.durationMinutes(session)
            ?: return stringResource(R.string.history_session_in_progress)
        return stringResource(
            R.string.history_finished_duration,
            HistoryExportTextFormatter.integer(minutes.toInt(), language),
        )
    }

    @Composable
    fun sessionAccessibilitySummary(
        session: MatchSession,
        language: AppLanguage,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val labels = labels()
        return HistoryUiText.sessionAccessibilitySummary(
            session = session,
            date = HistoryUiText.dateTime(session.startedAt, language, zoneId),
            count = boxCount(session.matchedCount, language),
            status = if (session.isActive) labels.sessionInProgress else labels.sessionEnded,
            separator = stringResource(R.string.history_accessibility_separator),
        )
    }

    @Composable
    fun boxCount(count: Int, language: AppLanguage): String {
        val safeCount = count.coerceAtLeast(0)
        return pluralStringResource(
            R.plurals.history_box_count,
            safeCount,
            HistoryExportTextFormatter.integer(safeCount, language),
        )
    }

    @Composable
    fun groupAccessibilitySummary(
        number: Int,
        code: String,
        count: Int,
        language: AppLanguage,
    ): String = stringResource(
        R.string.history_group_accessibility,
        number,
        code,
        boxCount(count, language),
    )

    @Composable
    fun matchedAt(
        firstMillis: Long,
        lastMillis: Long,
        hasRange: Boolean,
        language: AppLanguage,
    ): String {
        val label = labels().matchedAt
        val first = HistoryUiText.time(firstMillis, language)
        return if (hasRange) {
            stringResource(
                R.string.history_matched_at_range,
                label,
                first,
                HistoryUiText.time(lastMillis, language),
            )
        } else {
            stringResource(R.string.history_matched_at_single, label, first)
        }
    }

    @Composable
    fun boxIndex(index: Int): String = stringResource(
        R.string.history_box_index,
        index,
    )

    @Composable
    fun entryBoxNumber(number: Int): String = stringResource(
        R.string.history_entry_box_number,
        number,
    )

    @Composable
    fun groupNumber(number: Int): String = stringResource(R.string.history_group_number, number)

    @Composable
    fun partWithSuffix(part: String, suffix: String): String = stringResource(
        R.string.history_part_with_suffix,
        part,
        labels().suffix,
        suffix,
    )

    @Composable
    fun notAvailable(): String = stringResource(R.string.history_not_available)
}

/**
 * Resolve this feature's resources from the language in immutable app state.
 *
 * The app also synchronizes Android's per-app locale, but keeping this narrow
 * provider at the feature boundary makes a language change redraw immediately
 * (and keeps Compose tests deterministic even when the host device is in the
 * other language). Font scale and all other configuration values are retained.
 */
@Composable
internal fun HistoryLocalized(language: AppLanguage, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val localizedContext = remember(baseContext, baseConfiguration, language) {
        val configuration = Configuration(baseConfiguration).apply {
            setLocale(Locale.forLanguageTag(language.code))
        }
        baseContext.createConfigurationContext(configuration)
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedContext.resources.configuration,
        content = content,
    )
}
