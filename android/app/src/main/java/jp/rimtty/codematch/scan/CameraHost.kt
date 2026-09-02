package jp.rimtty.codematch.scan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import jp.rimtty.codematch.feature.scan.CameraAvailability
import jp.rimtty.codematch.feature.scan.CameraGuide
import jp.rimtty.codematch.feature.scan.CameraFocusPoint
import jp.rimtty.codematch.feature.scan.CameraPermissionState
import jp.rimtty.codematch.feature.scan.CameraPreviewRequest
import jp.rimtty.codematch.feature.scan.CameraRegion
import jp.rimtty.codematch.feature.scan.ScanUiAction
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanPayload

/**
 * Platform boundary for the scan screen's camera.
 *
 * The implementation may live in a CameraX/ML Kit adapter. No Android camera
 * or permission class crosses this boundary, which keeps [ScanRoute] easy to
 * exercise with a deterministic host and leaves the feature module stateless.
 */
interface CameraHost {
    /** Availability is checked before the first start request. */
    val availability: CameraAvailability
        get() = CameraAvailability.AVAILABLE

    /** The last platform permission result known by the adapter. */
    val permissionState: CameraPermissionState
        get() = CameraPermissionState.UNKNOWN

    /** Re-read platform permission state after returning from system settings. */
    fun refreshPermissionState(): CameraPermissionState = permissionState

    /**
     * Start or rebind the camera for one logical scan format.
     *
     * A [CameraStartResult.PermissionRequired] result means that the adapter
     * has launched the platform permission request; it must later call either
     * [CameraHostCallbacks.onStarted] or
     * [CameraHostCallbacks.onPermissionDenied].
     */
    fun start(request: CameraStartRequest, callbacks: CameraHostCallbacks): CameraStartResult

    /** Stop capture and release analysis resources. This must be idempotent. */
    fun stop()

    /**
     * Stop capture and notify after the adapter's physical teardown boundary.
     * The default implementation preserves synchronous hosts; CameraX hosts
     * override this to wait for unbind and any in-flight analysis drain.
     */
    fun stop(onComplete: () -> Unit) {
        stop()
        onComplete()
    }

    /** Map a normalized preview tap to CameraX focus/metering. */
    fun focus(point: CameraFocusPoint): Boolean

    /** Open the system app-details page after a permanent permission denial. */
    fun openSettings(): Boolean = false

    /** Render the host's PreviewView when capture is running. */
    @Composable
    fun Preview(modifier: Modifier, request: CameraPreviewRequest) = Unit
}

/**
 * Keep logical session teardown behind the host's physical stop boundary.
 *
 * CameraX hosts use the completion callback after use cases are unbound and
 * any asynchronous analysis frame has drained. Keeping this sequencing in a
 * small app-side helper makes it impossible for a destination to accidentally
 * confirm the session before the camera has actually stopped, while retaining
 * the immediate behavior for destinations without a camera host.
 */
internal fun stopCameraBeforeSessionEnd(
    cameraHost: CameraHost?,
    onCameraStopped: () -> Unit,
    onSessionEnded: () -> Unit,
) {
    if (cameraHost == null) {
        onCameraStopped()
        onSessionEnded()
        return
    }

    cameraHost.stop {
        onCameraStopped()
        onSessionEnded()
    }
}

/** Immutable camera start request; the adapter chooses the back camera. */
data class CameraStartRequest(
    val format: ScanFormat,
    /** Same normalized ROI rendered by [CameraPreviewRequest]. */
    val regionOfInterest: CameraRegion = CameraGuide(format).regionOfInterest,
)

/** Results that intentionally avoid exposing platform exception or permission types. */
sealed interface CameraStartResult {
    data object Started : CameraStartResult
    data object PermissionRequired : CameraStartResult
    data object PermissionDenied : CameraStartResult
    data object PermanentlyDenied : CameraStartResult
    data object Unavailable : CameraStartResult
    data object Failed : CameraStartResult
}

/** Events emitted by a running camera adapter. */
interface CameraHostCallbacks {
    fun onStarted() {}
    fun onStopped() {}
    fun onScan(payload: ScanPayload) {}
    fun onPermissionDenied(permanently: Boolean) {}
    fun onUnavailable() {}
    fun onFailed() {}
}

/**
 * Invalidates callbacks when a camera host is stopped or rebound.
 *
 * CameraX callbacks can arrive after unbind on another executor. A generation
 * gate prevents an old session from changing the current logical step or
 * injecting a payload into a newly bound session.
 */
internal class CameraHostCallbackGate(
    private val viewModel: ScanViewModel,
) {
    private var generation: Long = 0L

    fun invalidate() {
        generation += 1
    }

    fun open(): CameraHostCallbacks {
        val ticket = ++generation
        fun isCurrent(): Boolean = ticket == generation

        return object : CameraHostCallbacks {
            override fun onStarted() {
                if (isCurrent()) viewModel.onCameraStarted()
            }

            override fun onStopped() {
                if (isCurrent()) viewModel.onCameraStopped()
            }

            override fun onScan(payload: ScanPayload) {
                if (isCurrent() && payload.source == InputSource.CAMERA) {
                    viewModel.onAction(ScanUiAction.ScanReceived(payload))
                }
            }

            override fun onPermissionDenied(permanently: Boolean) {
                if (isCurrent()) viewModel.onCameraPermissionDenied(permanently)
            }

            override fun onUnavailable() {
                if (isCurrent()) viewModel.onCameraUnavailable()
            }

            override fun onFailed() {
                if (isCurrent()) viewModel.onCameraStartFailed()
            }
        }
    }
}
