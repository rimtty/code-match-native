package jp.rimtty.codematch.scan

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import jp.rimtty.codematch.R
import jp.rimtty.codematch.feature.scan.ScanScreen
import jp.rimtty.codematch.feature.scan.ScanUiAction

/**
 * Application-owned scan destination.
 *
 * This wrapper connects Hilt/repository state and Activity lifecycle to the
 * stateless feature surface. A camera host is intentionally not embedded yet:
 * M3 will observe the current scan format and call [ScanViewModel.onAction]
 * with camera [ScanUiAction.ScanReceived] payloads plus the camera lifecycle
 * helpers when CameraX is introduced.
 *
 * [showDebugDemoTools] is an explicit opt-in. Keep it false in production;
 * enabling it does not add the debug Fake scanner to a release dependency.
 */
@Composable
fun ScanRoute(
    modifier: Modifier = Modifier,
    showDebugDemoTools: Boolean = false,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showEndSessionDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(showDebugDemoTools) {
        viewModel.setDebugDemoEnabled(showDebugDemoTools)
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START ->
                    viewModel.onAction(ScanUiAction.Foregrounded)

                Lifecycle.Event.ON_STOP ->
                    viewModel.onAction(ScanUiAction.Backgrounded)

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Ensure a countdown/input callback cannot outlive this route.
            viewModel.onAction(ScanUiAction.Backgrounded)
        }
    }

    LaunchedEffect(state.sessionActive) {
        if (!state.sessionActive) showEndSessionDialog = false
    }

    val dispatch: (ScanUiAction) -> Unit = remember(viewModel) {
        { action ->
            if (action === ScanUiAction.EndSession) {
                // Ending a session is destructive for an empty session and
                // therefore waits for confirmation outside the ViewModel.
                showEndSessionDialog = true
            } else {
                viewModel.onAction(action)
            }
        }
    }

    ScanScreen(
        state = state,
        onAction = dispatch,
        modifier = modifier,
        showDebugDemoTools = showDebugDemoTools,
    )

    if (showEndSessionDialog && state.sessionActive) {
        AlertDialog(
            onDismissRequest = { showEndSessionDialog = false },
            title = { Text(stringResource(R.string.end_session_title)) },
            text = { Text(stringResource(R.string.end_session_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEndSessionDialog = false
                        viewModel.confirmEndSession()
                    },
                ) {
                    Text(stringResource(R.string.end_session_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndSessionDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
