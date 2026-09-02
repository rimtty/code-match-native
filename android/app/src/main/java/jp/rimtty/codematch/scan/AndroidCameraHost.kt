package jp.rimtty.codematch.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import jp.rimtty.codematch.feature.scan.CameraAvailability
import jp.rimtty.codematch.feature.scan.CameraFocusPoint
import jp.rimtty.codematch.feature.scan.CameraPermissionState
import jp.rimtty.codematch.feature.scan.CameraPreviewRequest
import jp.rimtty.codematch.scanner.camera.CameraCaptureState
import jp.rimtty.codematch.scanner.camera.CameraError
import jp.rimtty.codematch.scanner.camera.CameraErrorCode
import jp.rimtty.codematch.scanner.camera.CameraGuide
import jp.rimtty.codematch.scanner.camera.CameraScanner
import jp.rimtty.codematch.scanner.camera.CameraScannerHost

/**
 * Creates the application-owned CameraX host and disposes all camera/ML Kit
 * resources with the application composition. Permission requests deliberately
 * live here rather than in the preview, so a stopped preview can still request
 * permission without creating a start/request/render cycle.
 */
@Composable
fun rememberAndroidCameraHost(): CameraHost {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val host = remember(context, activity) {
        AndroidCameraHost(context.applicationContext, activity)
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = host::onPermissionResult,
    )

    SideEffect {
        host.permissionRequester = { launcher.launch(Manifest.permission.CAMERA) }
    }
    DisposableEffect(host) {
        onDispose {
            host.permissionRequester = null
            host.close()
        }
    }
    return host
}

private class AndroidCameraHost(
    private val appContext: Context,
    private val activity: Activity?,
) : CameraHost, AutoCloseable {
    private var callbacks: CameraHostCallbacks? = null
    private var requestedPermission = false
    private var closed = false

    internal var permissionRequester: (() -> Unit)? = null

    override val availability: CameraAvailability
        get() = if (
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        ) {
            CameraAvailability.AVAILABLE
        } else {
            CameraAvailability.UNAVAILABLE
        }

    override var permissionState: CameraPermissionState = currentPermissionState()
        private set

    override fun refreshPermissionState(): CameraPermissionState {
        permissionState = reconcileCameraPermissionState(
            current = permissionState,
            hasPermission = hasPermission(),
            permissionWasRequested = requestedPermission,
            shouldShowRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(
                    it,
                    Manifest.permission.CAMERA,
                )
            } ?: false,
        )
        return permissionState
    }

    private val scanner = CameraScanner(
        context = appContext,
        onPayload = { payload -> callbacks?.onScan(payload) },
        onStateChanged = ::onCaptureStateChanged,
        onError = ::onCameraError,
    )

    override fun start(
        request: CameraStartRequest,
        callbacks: CameraHostCallbacks,
    ): CameraStartResult {
        if (closed) return CameraStartResult.Failed
        this.callbacks = callbacks

        if (availability == CameraAvailability.UNAVAILABLE) {
            return CameraStartResult.Unavailable
        }
        if (!hasPermission()) {
            val requester = permissionRequester ?: return CameraStartResult.Failed
            permissionState = CameraPermissionState.REQUESTING
            requestedPermission = true
            requester()
            return CameraStartResult.PermissionRequired
        }

        permissionState = CameraPermissionState.GRANTED
        scanner.updateExpectedFormat(request.format)
        return when (scanner.captureState) {
            CameraCaptureState.UNAVAILABLE -> CameraStartResult.Unavailable
            CameraCaptureState.ERROR -> CameraStartResult.Failed
            else -> CameraStartResult.Started
        }
    }

    override fun stop() {
        stop(onComplete = {})
    }

    override fun stop(onComplete: () -> Unit) {
        if (closed) {
            onComplete()
            return
        }

        // Clear ML Kit's format while retaining the host until the stop
        // barrier finishes. CameraScanner.unbind's completion is after the
        // synchronous CameraX provider unbind and any in-flight ML Kit task.
        val callbacksBeingStopped = callbacks
        runCatching { scanner.updateExpectedFormat(null) }
        scanner.unbind {
            // A new start may have replaced the callback while the previous
            // ML Kit task was draining. Never deliver a stale stop event to
            // that new binding or clear its callback slot.
            if (callbacks === callbacksBeingStopped) {
                callbacksBeingStopped?.onStopped()
                callbacks = null
            }
            onComplete()
        }
    }

    override fun focus(point: CameraFocusPoint): Boolean =
        !closed && scanner.focusAtNormalized(point.xFraction, point.yFraction)

    override fun openSettings(): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", appContext.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    @Composable
    override fun Preview(modifier: Modifier, request: CameraPreviewRequest) {
        CameraScannerHost(
            cameraScanner = scanner,
            expectedFormat = request.format,
            modifier = modifier,
            guide = request.toCameraGuide(),
            showGuide = false,
            requestPermission = false,
            onPermissionDenied = { reportPermissionDenied() },
        )
    }

    fun onPermissionResult(granted: Boolean) {
        if (closed) return
        if (granted) {
            permissionState = CameraPermissionState.GRANTED
            scanner.onPermissionGranted()
            callbacks?.onStarted()
            return
        }

        val permanently = requestedPermission &&
            activity?.let {
                !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
            } == true
        permissionState = if (permanently) {
            CameraPermissionState.PERMANENTLY_DENIED
        } else {
            CameraPermissionState.DENIED
        }
        scanner.onPermissionDenied()
        callbacks?.onPermissionDenied(permanently)
    }

    override fun close() {
        if (closed) return
        closed = true
        callbacks = null
        // Compose disposal may race a pending stop. Keep the scanner alive
        // through its own unbind/drain callback before closing ML Kit and its
        // analysis executor.
        runCatching { scanner.updateExpectedFormat(null) }
        scanner.unbind { scanner.close() }
    }

    private fun onCaptureStateChanged(state: CameraCaptureState) {
        when (state) {
            CameraCaptureState.RUNNING -> callbacks?.onStarted()
            // STOPPED is emitted only by the scanner's drain boundary. The
            // host's stop callback is the single source of truth so a new
            // binding cannot receive a stale stop event during a rebind.
            CameraCaptureState.STOPPED -> Unit
            CameraCaptureState.UNAVAILABLE -> callbacks?.onUnavailable()
            CameraCaptureState.ERROR -> callbacks?.onFailed()
            CameraCaptureState.IDLE,
            CameraCaptureState.STARTING,
            CameraCaptureState.PERMISSION_REQUIRED,
            CameraCaptureState.PERMISSION_DENIED,
            -> Unit
        }
    }

    private fun onCameraError(error: CameraError) {
        when (error.code) {
            CameraErrorCode.BACK_CAMERA_UNAVAILABLE -> callbacks?.onUnavailable()
            CameraErrorCode.PERMISSION_DENIED -> {
                permissionState = CameraPermissionState.DENIED
                callbacks?.onPermissionDenied(permanently = false)
            }
            CameraErrorCode.PROVIDER_UNAVAILABLE,
            CameraErrorCode.USE_CASE_BIND_FAILED,
            CameraErrorCode.SCANNER_UNAVAILABLE,
            -> callbacks?.onFailed()

            // A single unreadable frame or unsupported focus operation must
            // not tear down an otherwise healthy camera session.
            CameraErrorCode.ANALYSIS_FAILED,
            CameraErrorCode.FOCUS_FAILED,
            -> Unit
        }
    }

    private fun reportPermissionDenied() {
        if (!hasPermission()) onPermissionResult(granted = false)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

    private fun currentPermissionState(): CameraPermissionState =
        if (hasPermission()) CameraPermissionState.GRANTED else CameraPermissionState.UNKNOWN
}

private fun CameraPreviewRequest.toCameraGuide(): CameraGuide = CameraGuide(
    leftFraction = regionOfInterest.left,
    topFraction = regionOfInterest.top,
    rightFraction = regionOfInterest.right,
    bottomFraction = regionOfInterest.bottom,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun reconcileCameraPermissionState(
    current: CameraPermissionState,
    hasPermission: Boolean,
    permissionWasRequested: Boolean,
    shouldShowRationale: Boolean,
): CameraPermissionState = when {
    hasPermission -> CameraPermissionState.GRANTED
    permissionWasRequested && !shouldShowRationale -> CameraPermissionState.PERMANENTLY_DENIED
    current == CameraPermissionState.GRANTED -> CameraPermissionState.DENIED
    else -> current
}
