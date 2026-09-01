package jp.rimtty.codematch.history

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import jp.rimtty.codematch.core.export.HistoryPdfExporter
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchSession
import jp.rimtty.codematch.feature.history.HistoryContent
import jp.rimtty.codematch.feature.history.HistoryLayoutMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application-owned history destination.
 *
 * Persistence and navigation state are kept here while the feature module
 * remains a reusable stateless Compose surface. The caller's Activity owns the
 * surrounding scaffold; this route only supplies the content and Android
 * document/share bridges.
 */
@Composable
fun HistoryRoute(modifier: Modifier = Modifier) {
    val viewModel: HistoryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var selectedGroupCode by remember { mutableStateOf<String?>(null) }
    var selectedEntryId by remember { mutableStateOf<String?>(null) }
    var pendingDocument by remember { mutableStateOf<PendingDocument?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { destination ->
        val pending = pendingDocument
        pendingDocument = null
        if (destination == null || pending == null) return@rememberLauncherForActivityResult

        // SAF I/O is deliberately off the main thread. The URI was selected
        // by the user, so the app never needs broad storage permissions.
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    output.write(pending.bytes)
                }
            }
        }
    }

    // A deleted session should not leave a stale detail pane selected. This
    // also handles Room's first emission after process recreation.
    val selectedSessionExists = state.sessions.any { it.id == selectedSessionId }
    LaunchedEffect(selectedSessionId, selectedSessionExists) {
        if (selectedSessionId != null && !selectedSessionExists) {
            selectedSessionId = null
            selectedGroupCode = null
            selectedEntryId = null
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        val layoutMode = if (maxWidth >= EXPANDED_MIN_WIDTH) {
            HistoryLayoutMode.EXPANDED
        } else {
            HistoryLayoutMode.COMPACT
        }

        HistoryContent(
            sessions = state.sessions,
            selectedSessionId = selectedSessionId,
            layoutMode = layoutMode,
            selectedGroupCode = selectedGroupCode,
            selectedEntryId = selectedEntryId,
            language = state.language,
            onSessionSelected = { sessionId ->
                selectedSessionId = sessionId
                selectedGroupCode = null
                selectedEntryId = null
            },
            onDeleteSession = { sessionId ->
                if (selectedSessionId == sessionId) {
                    selectedSessionId = null
                    selectedGroupCode = null
                    selectedEntryId = null
                }
                viewModel.deleteSession(sessionId)
            },
            onRenameSession = viewModel::renameSession,
            onGroupSelected = { code ->
                selectedGroupCode = code
                selectedEntryId = null
            },
            onEntrySelected = { entryId -> selectedEntryId = entryId },
            onBack = {
                when {
                    selectedEntryId != null -> selectedEntryId = null
                    selectedGroupCode != null -> selectedGroupCode = null
                    else -> selectedSessionId = null
                }
            },
            onSavePdf = { session ->
                preparePdfForSave(
                    session = session,
                    language = state.language,
                    scope = scope,
                    onReady = { pending ->
                        pendingDocument = pending
                        createDocument.launch(pending.fileName)
                    },
                )
            },
            onSharePdf = { session ->
                preparePdfForShare(
                    context = context,
                    session = session,
                    language = state.language,
                    scope = scope,
                )
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private data class PendingDocument(
    val bytes: ByteArray,
    val fileName: String,
)

private fun preparePdfForSave(
    session: MatchSession,
    language: AppLanguage,
    scope: kotlinx.coroutines.CoroutineScope,
    onReady: (PendingDocument) -> Unit,
) {
    scope.launch(Dispatchers.IO) {
        val pending = runCatching {
            PendingDocument(
                bytes = HistoryPdfExporter.generate(session, language),
                fileName = HistoryPdfExporter.fileName(session, language),
            )
        }.getOrNull() ?: return@launch
        withContext(Dispatchers.Main.immediate) { onReady(pending) }
    }
}

private fun preparePdfForShare(
    context: Context,
    session: MatchSession,
    language: AppLanguage,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    scope.launch(Dispatchers.IO) {
        val file = runCatching {
            HistoryPdfExporter.writeToCache(context, session, language)
        }.getOrNull() ?: return@launch

        withContext(Dispatchers.Main.immediate) {
            val uri = runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }.getOrNull() ?: return@withContext
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(null, uri)
            }
            context.startActivity(Intent.createChooser(sendIntent, null))
        }
    }
}

private val EXPANDED_MIN_WIDTH = 840.dp
