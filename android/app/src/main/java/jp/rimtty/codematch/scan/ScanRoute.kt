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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import jp.rimtty.codematch.R
import jp.rimtty.codematch.feature.scan.CameraAvailability
import jp.rimtty.codematch.feature.scan.CameraPermissionState
import jp.rimtty.codematch.feature.scan.CameraPreviewContent
import jp.rimtty.codematch.feature.scan.ScanScreen
import jp.rimtty.codematch.feature.scan.ScanUiAction
import jp.rimtty.codematch.navigation.CodeMatchBackHandler
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat

/**
 * Application-owned scan destination.
 *
 * This wrapper connects Hilt/repository state and Activity lifecycle to the
 * stateless feature surface. The optional [cameraHost] is a narrow adapter
 * boundary: M3 CameraX/ML Kit code can be supplied without leaking Android
 * camera types into the feature. It receives the current format, sends camera
 * [ScanUiAction.ScanReceived] payloads, and owns platform permission requests.
 *
 * [showDebugDemoTools] is an explicit opt-in. Keep it false in production;
 * enabling it does not add the debug Fake scanner to a release dependency.
 */
@Composable
fun ScanRoute(
    modifier: Modifier = Modifier,
    showDebugDemoTools: Boolean = false,
    viewModel: ScanViewModel = hiltViewModel(),
    /** Optional CameraX/ML Kit adapter. Null keeps previews deterministic in M2. */
    cameraHost: CameraHost? = null,
    /** Host-owned, testable hook for opening platform Bluetooth settings. */
    onOpenBluetoothSettings: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentCameraHost by rememberUpdatedState(cameraHost)
    var showEndSessionDialog by rememberSaveable { mutableStateOf(false) }
    var boundCameraFormat by remember(cameraHost) { mutableStateOf<ScanFormat?>(null) }
    val callbackGate = remember(cameraHost, viewModel) { CameraHostCallbackGate(viewModel) }

    LaunchedEffect(showDebugDemoTools) {
        viewModel.setDebugDemoEnabled(showDebugDemoTools)
    }

    // Capture the host that belongs to this lifecycle observer. The host is
    // recreated with a new Activity/context, while a ViewModel can survive a
    // configuration change. Using only rememberUpdatedState here would let an
    // old observer stop the replacement host during its disposal.
    DisposableEffect(lifecycleOwner, viewModel, cameraHost) {
        val hostForLifecycle = cameraHost
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    hostForLifecycle?.refreshPermissionState()?.let { permission ->
                        viewModel.synchronizeCameraPermission(permission)
                    }
                    viewModel.onAction(ScanUiAction.Foregrounded)
                }

                Lifecycle.Event.ON_STOP -> {
                    // Stop the platform session immediately, then update the
                    // logical state. The ViewModel keeps the resume intent.
                    callbackGate.invalidate()
                    hostForLifecycle?.stop()
                    boundCameraFormat = null
                    viewModel.onAction(ScanUiAction.Backgrounded)
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Ensure a countdown/input callback cannot outlive this route.
            callbackGate.invalidate()
            hostForLifecycle?.stop()
            boundCameraFormat = null
            viewModel.onAction(ScanUiAction.Backgrounded)
        }
    }

    // An observer attached after a configuration change may not receive a new
    // ON_START event. Reconcile once with the current lifecycle state so an
    // explicitly running camera resumes exactly once after rotation.
    LaunchedEffect(lifecycleOwner) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.onAction(ScanUiAction.Foregrounded)
        }
    }

    LaunchedEffect(cameraHost) {
        val host = cameraHost ?: return@LaunchedEffect
        if (host.availability == CameraAvailability.UNAVAILABLE) {
            viewModel.onCameraUnavailable()
            return@LaunchedEffect
        }
        viewModel.synchronizeCameraPermission(host.refreshPermissionState())
    }

    // Camera start/stop/rebind is driven by immutable ViewModel state. The
    // bound format guard makes recomposition and a synchronous host callback
    // harmless while still rebinding QR -> Code 128 in the same session.
    LaunchedEffect(
        cameraHost,
        state.sessionActive,
        state.inputSource,
        state.expectedFormat,
        state.isCameraRunning,
        state.isCameraStarting,
    ) {
        val host = cameraHost
        val expected = state.expectedFormat
        val wantsCamera = state.sessionActive &&
            state.inputSource == InputSource.CAMERA &&
            expected != null &&
            (state.isCameraRunning || state.isCameraStarting)

        if (!wantsCamera) {
            if (boundCameraFormat != null) {
                callbackGate.invalidate()
                host?.stop()
            }
            boundCameraFormat = null
            return@LaunchedEffect
        }

        if (host == null) {
            boundCameraFormat = null
            viewModel.onCameraUnavailable()
            return@LaunchedEffect
        }

        if (host.availability == CameraAvailability.UNAVAILABLE) {
            boundCameraFormat = null
            viewModel.onCameraUnavailable()
            return@LaunchedEffect
        }

        if (host.permissionState == CameraPermissionState.PERMANENTLY_DENIED) {
            boundCameraFormat = null
            viewModel.onCameraPermissionDenied(permanently = true)
            return@LaunchedEffect
        }

        if (boundCameraFormat == expected) return@LaunchedEffect
        callbackGate.invalidate()
        val callbacks = callbackGate.open()
        boundCameraFormat = expected
        when (host.start(CameraStartRequest(expected), callbacks)) {
            CameraStartResult.Started -> viewModel.onCameraStarted()
            CameraStartResult.PermissionRequired -> viewModel.onCameraPermissionRequesting()
            CameraStartResult.PermissionDenied -> viewModel.onCameraPermissionDenied(false)
            CameraStartResult.PermanentlyDenied -> viewModel.onCameraPermissionDenied(true)
            CameraStartResult.Unavailable -> viewModel.onCameraUnavailable()
            CameraStartResult.Failed -> viewModel.onCameraStartFailed()
        }
    }

    val cameraPreview: CameraPreviewContent? = cameraHost?.let { host ->
        { previewModifier, request -> host.Preview(previewModifier, request) }
    }

    LaunchedEffect(state.sessionActive) {
        if (!state.sessionActive) showEndSessionDialog = false
    }

    CodeMatchBackHandler(
        enabled = state.sessionActive || showEndSessionDialog,
        onBack = {
            if (showEndSessionDialog) {
                showEndSessionDialog = false
            } else if (state.sessionActive) {
                // Back is a potentially destructive operation while a
                // session is active, so use the same confirmation as the
                // explicit End session action.
                showEndSessionDialog = true
            }
        },
    )

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
        cameraPreview = cameraPreview,
        onCameraFocus = { point -> currentCameraHost?.focus(point) },
        onOpenCameraSettings = { currentCameraHost?.openSettings() },
        onOpenBluetoothSettings = onOpenBluetoothSettings,
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
                        callbackGate.invalidate()
                        // Do not end the logical session until CameraX's
                        // unbind and any in-flight ML Kit frame have drained.
                        // This is the Android equivalent of waiting for
                        // AVCaptureSession.stopRunning().
                        stopCameraBeforeSessionEnd(
                            cameraHost = currentCameraHost,
                            onCameraStopped = viewModel::onCameraStopped,
                            onSessionEnded = viewModel::confirmEndSession,
                        )
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
