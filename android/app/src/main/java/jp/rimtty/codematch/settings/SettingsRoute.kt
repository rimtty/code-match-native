package jp.rimtty.codematch.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import jp.rimtty.codematch.feature.settings.DiagnosticLogFormatter
import jp.rimtty.codematch.feature.settings.R
import jp.rimtty.codematch.feature.settings.SettingsScreen
import jp.rimtty.codematch.feature.settings.SettingsUiAction
import jp.rimtty.codematch.feature.settings.SettingsUiState
import jp.rimtty.codematch.navigation.CodeMatchBackHandler
import jp.rimtty.codematch.scanner.api.ScannerIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    /** Host-owned, testable hook for opening platform Bluetooth settings. */
    onOpenBluetoothSettings: (ScannerIssue) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshScannerState() }
    // The scanner guide is an inline settings destination. Predictive/system
    // back closes it first; once closed, the Activity owns root back behavior.
    CodeMatchBackHandler(
        enabled = state.setupGuideVisible,
        onBack = { viewModel.onAction(SettingsUiAction.CloseSetupGuide) },
    )

    // Diagnostic log export is host-owned: the feature module never starts an
    // external Activity or touches ContentResolver. The text contains only the
    // sanitized status events plus app/device identification; scan values
    // cannot reach it because the scanner API has no payload-logging entry.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingLog by remember { mutableStateOf<String?>(null) }
    val savedMessage = stringResource(R.string.settings_diagnostics_saved)
    val saveFailedMessage = stringResource(R.string.settings_diagnostics_save_failed)
    val shareSubject = stringResource(R.string.settings_diagnostics_subject)
    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { destination ->
        val text = pendingLog
        pendingLog = null
        if (destination == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(destination, "wt")?.use { stream ->
                        stream.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("no output stream")
                }.isSuccess
            }
            Toast.makeText(
                context,
                if (written) savedMessage else saveFailedMessage,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    SettingsScreen(
        state = state,
        onAction = { action ->
            when (action) {
                SettingsUiAction.ShareDiagnostics -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, shareSubject)
                        putExtra(Intent.EXTRA_TEXT, diagnosticLogText(context, state))
                    }
                    runCatching { context.startActivity(Intent.createChooser(intent, shareSubject)) }
                }
                SettingsUiAction.SaveDiagnostics -> {
                    pendingLog = diagnosticLogText(context, state)
                    runCatching {
                        createDocument.launch(DiagnosticLogFormatter.fileName(Instant.now()))
                    }.onFailure {
                        pendingLog = null
                        Toast.makeText(context, saveFailedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                else -> viewModel.onAction(action)
            }
        },
        modifier = modifier,
        onOpenBluetoothSettings = { onOpenBluetoothSettings(state.resolvedScannerIssue) },
    )
}

private fun diagnosticLogText(context: Context, state: SettingsUiState): String {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val versionName = packageInfo?.versionName ?: "?"
    val versionCode = packageInfo?.longVersionCode ?: 0L
    return DiagnosticLogFormatter.format(
        events = state.diagnosticEvents,
        header = DiagnosticLogFormatter.Header(
            appVersion = "$versionName ($versionCode)",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            system = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            connection = DiagnosticLogFormatter.connectionLabel(state.connectionState),
            configuration = DiagnosticLogFormatter.configurationLabel(state.configurationState),
            illumination = state.illuminationState.name,
            tuning = state.tuningState.name,
        ),
    )
}
