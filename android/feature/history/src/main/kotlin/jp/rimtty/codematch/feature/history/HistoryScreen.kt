package jp.rimtty.codematch.feature.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import jp.rimtty.codematch.core.export.HistoryExportTextFormatter
import jp.rimtty.codematch.core.matching.CodeMatcher
import jp.rimtty.codematch.core.matching.KanbanQrRecord
import jp.rimtty.codematch.core.matching.TagBarcodeRecord
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.GroupedMatchEntry
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession

/** Layout choice supplied by the app's window-size/navigation layer. */
enum class HistoryLayoutMode {
    COMPACT,
    EXPANDED,
}

/** Stable semantics/test tags shared by UI tests and accessibility checks. */
object HistoryTestTags {
    const val SCREEN = "historyScreen"
    const val CONTENT = "historyContent"
    const val SESSION_ROW = "historySessionRow"
    const val SESSION_DETAIL = "historySessionDetail"
    const val GROUP_ROW = "matchEntryRow"
    const val GROUP_DETAIL = "historyGroupDetail"
    const val BOX_ROW = "boxEntryRow"
    const val ENTRY_DETAIL = "historyEntryDetail"
    const val NAME_FIELD = "sessionNameEditField"
    const val SAVE_PDF = "savePDFButton"
    const val SHARE_PDF = "sharePDFButton"
}

/**
 * Stateless history list. The caller owns persistence and navigation.
 * [sessions] are defensively sorted newest first for a stable UI contract.
 */
@Composable
fun HistoryScreen(
    sessions: List<MatchSession>,
    language: AppLanguage = AppLanguage.JAPANESE,
    onSessionSelected: (String) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    HistoryLocalized(language) {
        HistorySessionList(
            sessions = sessions,
            language = language,
            onSessionSelected = onSessionSelected,
            onDeleteSession = onDeleteSession,
            modifier = modifier,
        )
    }
}

/**
 * One API for compact list→detail and expanded list/detail layouts.
 * Selection IDs and callbacks make this composable suitable for Navigation 3,
 * a hand-rolled state machine, or a two-pane tablet destination.
 */
@Composable
fun HistoryContent(
    sessions: List<MatchSession>,
    selectedSessionId: String? = null,
    layoutMode: HistoryLayoutMode = HistoryLayoutMode.COMPACT,
    selectedGroupCode: String? = null,
    selectedEntryId: String? = null,
    language: AppLanguage = AppLanguage.JAPANESE,
    onSessionSelected: (String) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onRenameSession: (String, String?) -> Unit = { _, _ -> },
    onGroupSelected: (String) -> Unit = {},
    onEntrySelected: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onSavePdf: (MatchSession) -> Unit = {},
    onSharePdf: (MatchSession) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    HistoryLocalized(language) {
        val sortedSessions = remember(sessions) { sortSessions(sessions) }
        val selectedSession = sortedSessions.firstOrNull { it.id == selectedSessionId }
        val list: @Composable (Modifier) -> Unit = { listModifier ->
            HistorySessionList(
                sessions = sortedSessions,
                language = language,
                onSessionSelected = onSessionSelected,
                onDeleteSession = onDeleteSession,
                modifier = listModifier,
            )
        }
        val detail: @Composable (Modifier) -> Unit = { detailModifier ->
            HistorySessionDetail(
                session = selectedSession,
                selectedGroupCode = selectedGroupCode,
                selectedEntryId = selectedEntryId,
                language = language,
                onRenameSession = onRenameSession,
                onGroupSelected = onGroupSelected,
                onEntrySelected = onEntrySelected,
                onSavePdf = onSavePdf,
                onSharePdf = onSharePdf,
                modifier = detailModifier,
            )
        }

        BoxWithConstraints(modifier.fillMaxSize().testTag(HistoryTestTags.CONTENT)) {
            if (layoutMode == HistoryLayoutMode.EXPANDED) {
                // The app normally selects EXPANDED only for a wide window, but
                // multi-window resizing can temporarily leave this destination
                // with narrower constraints. Keep both panes visible rather than
                // allowing the fixed list width to push detail completely offscreen.
                val listPaneWidth = minOf(344.dp, maxWidth * 0.42f)
                Row(Modifier.fillMaxSize()) {
                    list(Modifier.width(listPaneWidth))
                    VerticalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                    detail(Modifier.weight(1f))
                }
            } else if (selectedSession == null) {
                list(Modifier.fillMaxSize())
            } else {
                Column(Modifier.fillMaxSize()) {
                    BackButton(onBack = onBack)
                    detail(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HistorySessionList(
    sessions: List<MatchSession>,
    language: AppLanguage,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = HistoryUiResources.labels()
    Column(
        modifier = modifier
            .testTag(HistoryTestTags.SCREEN)
            .semantics { contentDescription = labels.title },
    ) {
        Text(
            text = labels.title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        if (sessions.isEmpty()) {
            EmptyHistoryState(labels, Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            ) {
                items(sortSessions(sessions), key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        language = language,
                        onClick = { onSessionSelected(session.id) },
                        onDelete = { onDeleteSession(session.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryState(labels: HistoryUiLabels, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = labels.emptyTitle,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = labels.emptyDescription,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SessionRow(
    session: MatchSession,
    language: AppLanguage,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val labels = HistoryUiResources.labels()
    val summary = HistoryUiResources.sessionAccessibilitySummary(session, language)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = summary
                role = Role.Button
            }
            .testTag(HistoryTestTags.SESSION_ROW),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(13.dp),
                color = if (session.isActive) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                },
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = HistoryUiResources.boxCount(session.matchedCount, language),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (session.displayName.isNotEmpty()) {
                    Text(
                        text = session.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = HistoryUiText.dateTime(session.startedAt, language),
                    style = if (session.displayName.isEmpty()) {
                        MaterialTheme.typography.bodyMedium
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                )
                Text(
                    text = if (session.isActive) labels.sessionInProgress
                    else HistoryUiResources.durationText(session, language),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (session.isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp).testTag("${HistoryTestTags.SESSION_ROW}.delete"),
            ) {
                Icon(imageVector = Icons.Outlined.Delete, contentDescription = labels.delete)
            }
        }
    }
}

/** Session detail, group detail, and box detail are all stateless entry points. */
@Composable
fun HistorySessionDetail(
    session: MatchSession?,
    selectedGroupCode: String? = null,
    selectedEntryId: String? = null,
    language: AppLanguage = AppLanguage.JAPANESE,
    onRenameSession: (String, String?) -> Unit = { _, _ -> },
    onGroupSelected: (String) -> Unit = {},
    onEntrySelected: (String) -> Unit = {},
    onSavePdf: (MatchSession) -> Unit = {},
    onSharePdf: (MatchSession) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    HistoryLocalized(language) {
        val labels = HistoryUiResources.labels()
        if (session == null) {
            EmptyDetailState(labels, modifier.testTag(HistoryTestTags.SESSION_DETAIL))
        } else {
            val selectedEntry = session.entries.firstOrNull { it.id == selectedEntryId }
            val selectedGroup = session.groupedEntries.firstOrNull { it.code == selectedGroupCode }
            when {
                selectedEntry != null -> {
                    val number = selectedGroup?.entries?.indexOfFirst { it.id == selectedEntry.id }
                        ?.takeIf { it >= 0 }
                        ?.plus(1)
                        ?: session.entries.indexOfFirst { it.id == selectedEntry.id }.plus(1)
                    HistoryEntryDetail(
                        entry = selectedEntry,
                        boxNumber = number,
                        language = language,
                        modifier = modifier,
                    )
                }
                selectedGroup != null -> {
                    HistoryGroupDetail(
                        group = selectedGroup,
                        groupNumber = session.groupedEntries.indexOf(selectedGroup) + 1,
                        language = language,
                        onEntrySelected = onEntrySelected,
                        modifier = modifier,
                    )
                }
                else -> SessionOverview(
                    session = session,
                    labels = labels,
                    language = language,
                    onRenameSession = onRenameSession,
                    onGroupSelected = onGroupSelected,
                    onSavePdf = onSavePdf,
                    onSharePdf = onSharePdf,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun SessionOverview(
    session: MatchSession,
    labels: HistoryUiLabels,
    language: AppLanguage,
    onRenameSession: (String, String?) -> Unit,
    onGroupSelected: (String) -> Unit,
    onSavePdf: (MatchSession) -> Unit,
    onSharePdf: (MatchSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editedName by remember(session.id, session.displayName) {
        mutableStateOf(session.displayName)
    }

    fun commitName() {
        val normalized = editedName.trim().ifBlank { null }
        if (normalized != session.name?.trim().takeIf { !it.isNullOrBlank() }) {
            onRenameSession(session.id, normalized)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(HistoryTestTags.SESSION_DETAIL),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item {
            SectionCard(title = labels.sessionName) {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    label = { Text(labels.namePlaceholder) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitName() }),
                    modifier = Modifier.fillMaxWidth().testTag(HistoryTestTags.NAME_FIELD),
                )
            }
        }
        item {
            SectionCard {
                SummaryRow(labels.start, HistoryUiText.dateTime(session.startedAt, language))
                val endedAt = session.endedAt
                if (endedAt != null) {
                    SummaryRow(labels.end, HistoryUiText.dateTime(endedAt, language))
                } else {
                    SummaryRow(labels.status, labels.sessionInProgress, valueColor = MaterialTheme.colorScheme.primary)
                }
                SummaryRow(
                    labels.inspectionBoxes,
                    HistoryUiResources.boxCount(session.matchedCount, language),
                )
                SummaryRow(
                    labels.partCount,
                    HistoryExportTextFormatter.integer(session.groupedEntries.size, language),
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { onSavePdf(session) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(HistoryTestTags.SAVE_PDF),
                ) {
                    Icon(Icons.Outlined.SaveAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(labels.savePdf)
                }
                OutlinedButton(
                    onClick = { onSharePdf(session) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(HistoryTestTags.SHARE_PDF),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(labels.sharePdf)
                }
            }
        }
        item {
            Text(
                text = labels.matchedCodes,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        if (session.entries.isEmpty()) {
            item { EmptyMatchesState(labels) }
        } else {
            itemsIndexed(session.groupedEntries, key = { _, group -> group.id }) { index, group ->
                GroupRow(
                    group = group,
                    number = index + 1,
                    language = language,
                    onClick = { onGroupSelected(group.code) },
                )
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: GroupedMatchEntry,
    number: Int,
    language: AppLanguage,
    onClick: () -> Unit,
) {
    val labels = HistoryUiResources.labels()
    val groupAccessibilitySummary = HistoryUiResources.groupAccessibilitySummary(
        number = number,
        code = group.code,
        count = group.boxCount,
        language = language,
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = groupAccessibilitySummary
                role = Role.Button
            }
            .testTag(HistoryTestTags.GROUP_ROW),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    HistoryUiResources.groupNumber(number),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = HistoryUiResources.matchedAt(
                        firstMillis = group.firstMatchedAt,
                        lastMillis = group.lastMatchedAt,
                        hasRange = group.boxCount > 1,
                        language = language,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = group.code,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = HistoryUiResources.boxCount(group.boxCount, language),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Same group detail as Swift's GroupedMatchDetail, exposed for adaptive hosts. */
@Composable
fun HistoryGroupDetail(
    group: GroupedMatchEntry,
    groupNumber: Int = 1,
    language: AppLanguage = AppLanguage.JAPANESE,
    onEntrySelected: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    HistoryLocalized(language) {
        val labels = HistoryUiResources.labels()
        LazyColumn(
            modifier = modifier.fillMaxSize().testTag(HistoryTestTags.GROUP_DETAIL),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item {
                SectionCard {
                    SummaryRow(labels.number, HistoryUiResources.groupNumber(groupNumber))
                    SummaryRow(labels.inspectionBoxes, HistoryUiResources.boxCount(group.boxCount, language))
                    SummaryRow(labels.firstMatch, HistoryUiText.dateTime(group.firstMatchedAt, language))
                    if (group.boxCount > 1) {
                        SummaryRow(labels.lastMatch, HistoryUiText.dateTime(group.lastMatchedAt, language))
                    }
                }
            }
            item {
                Text(
                    text = labels.itemNumber,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                SelectionContainerText(group.code, Modifier.padding(horizontal = 20.dp))
            }
            item {
                Text(
                    text = labels.boxRecords,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
            itemsIndexed(group.entries, key = { _, entry -> entry.id }) { index, entry ->
                val boxDescription = HistoryUiResources.entryBoxNumber(index + 1)
                val accessibilitySeparator = stringResource(R.string.history_accessibility_separator)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .heightIn(min = 52.dp)
                        .clickable(role = Role.Button) { onEntrySelected(entry.id) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = boxDescription +
                                accessibilitySeparator +
                                HistoryUiText.time(entry.matchedAt, language)
                            role = Role.Button
                        }
                        .testTag(HistoryTestTags.BOX_ROW),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = HistoryUiResources.boxIndex(index + 1),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = HistoryUiText.time(entry.matchedAt, language),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Box detail including parsed QR/Code 128 fields and both raw payloads. */
@Composable
fun HistoryEntryDetail(
    entry: MatchEntry,
    boxNumber: Int = 1,
    language: AppLanguage = AppLanguage.JAPANESE,
    modifier: Modifier = Modifier,
) {
    HistoryLocalized(language) {
        val labels = HistoryUiResources.labels()
        val qr = entry.qrPayload?.let(KanbanQrRecord::parse)
        val barcode = entry.barcodePayload?.let(TagBarcodeRecord::parse)
        LazyColumn(
            modifier = modifier.fillMaxSize().testTag(HistoryTestTags.ENTRY_DETAIL),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item {
                SectionCard {
                    SummaryRow(
                        labels.box,
                        HistoryUiResources.entryBoxNumber(boxNumber),
                    )
                    SummaryRow(labels.matchedAt, HistoryUiText.dateTime(entry.matchedAt, language))
                }
            }
            item {
                Text(
                    text = labels.itemNumber,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                SelectionContainerText(entry.code, Modifier.padding(horizontal = 20.dp))
            }
            if (qr != null) {
                item {
                    SectionCard(title = labels.qrParsed) {
                        SummaryRow(labels.cardNumber, qr.cardNumber)
                        SummaryRow(
                            labels.itemNumber,
                            qr.partSuffix?.let {
                                HistoryUiResources.partWithSuffix(
                                    CodeMatcher.formatPartNumber(qr.partNumber),
                                    it,
                                )
                            } ?: CodeMatcher.formatPartNumber(qr.partNumber),
                        )
                        SummaryRow(labels.deliveryQuantity, HistoryUiText.quantity(qr.deliveryQuantity, language))
                        SummaryRow(labels.instructedQuantity, HistoryUiText.quantity(qr.instructedQuantity, language))
                        SummaryRow(labels.factory, qr.factoryCode ?: HistoryUiResources.notAvailable())
                        SummaryRow(labels.warehouse, qr.warehouseCode ?: HistoryUiResources.notAvailable())
                        SummaryRow(labels.supplyPoint, qr.supplyPointCode ?: HistoryUiResources.notAvailable())
                    }
                }
            }
            if (barcode != null) {
                item {
                    SectionCard(title = labels.barcodeParsed) {
                        SummaryRow(labels.partNumber, barcode.partNumber)
                        SummaryRow(labels.managementCode, barcode.managementCode ?: HistoryUiResources.notAvailable())
                    }
                }
            }
            item {
                PayloadSection(
                    title = labels.fullQr,
                    payload = entry.qrPayload,
                    fallback = labels.noRecord,
                    testTag = "historyQrPayload",
                )
            }
            item {
                PayloadSection(
                    title = labels.fullBarcode,
                    payload = entry.barcodePayload,
                    fallback = labels.noRecord,
                    testTag = "historyBarcodePayload",
                )
            }
        }
    }
}

@Composable
private fun PayloadSection(title: String, payload: String?, fallback: String, testTag: String) {
    SectionCard(title = title) {
        SelectionContainerText(
            text = payload ?: fallback,
            modifier = Modifier.testTag(testTag),
            muted = payload == null,
        )
    }
}

@Composable
private fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            content()
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f).padding(vertical = 6.dp),
        )
        Text(
            value,
            color = valueColor,
            modifier = Modifier.weight(0.58f).padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun SelectionContainerText(text: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text = text,
            modifier = modifier.fillMaxWidth(),
            fontFamily = if (muted) FontFamily.Default else FontFamily.Monospace,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EmptyMatchesState(labels: HistoryUiLabels) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp)) {
        Text(labels.noMatchesTitle, style = MaterialTheme.typography.titleSmall)
        Text(
            labels.noMatchesDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun EmptyDetailState(labels: HistoryUiLabels, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(labels.detailsNotFound, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    val labels = HistoryUiResources.labels()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = labels.back)
        }
        Text(labels.back, style = MaterialTheme.typography.titleMedium)
    }
}

private fun sortSessions(sessions: List<MatchSession>): List<MatchSession> =
    sessions.sortedWith(compareByDescending<MatchSession> { it.startedAt }.thenByDescending { it.id })
