package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerIssue

/**
 * All information needed to render the scan feature.
 *
 * This is intentionally a plain value object. A ViewModel or an Activity can
 * derive it from [ScanSessionCoordinator], while previews and tests can build
 * it directly without a scanner, repository, camera, or coroutine.
 */
data class ScanUiState(
    val sessionActive: Boolean = false,
    val sessionNameDraft: String = "",
    val sessionName: String? = null,
    val session: ScanSessionState = ScanSessionState(),
    val bluetoothReady: Boolean = false,
    val bluetoothDeviceName: String? = null,
    /** Current scanner configuration state; failure details stay outside UI state. */
    val bluetoothConfigurationState: ConfigurationState = ConfigurationState.Unavailable,
    val cameraAvailable: Boolean = true,
    val cameraPermissionDenied: Boolean = false,
    val isCameraRunning: Boolean = false,
    val isCameraStarting: Boolean = false,
    val message: String? = null,
    val lastInvalidReason: InvalidScanReason? = null,
    /** Safe scan metadata for actionable validation feedback; never the payload. */
    val lastInvalidPayloadLength: Int? = null,
    /** Demo controls are never shown unless both this and the composable flag are true. */
    val debugDemoEnabled: Boolean = false,
    /** Canonical permission state; [cameraPermissionDenied] remains for API compatibility. */
    val cameraPermissionState: CameraPermissionState = CameraPermissionState.UNKNOWN,
    /** A recoverable start error is intentionally represented without an exception message. */
    val cameraStartFailed: Boolean = false,
    /** Last BLE failure/fallback classification, with no raw transport text. */
    val bluetoothIssue: ScannerIssue = ScannerIssue.NONE,
    /** True when the current logical step was moved to camera after BLE loss. */
    val bluetoothFallbackActive: Boolean = false,
    /** Physical link only; never a substitute for verified scan readiness. */
    val bluetoothConnected: Boolean = false,
    val bluetoothReconnecting: Boolean = false,
) {
    val scan: ScanState get() = session.scan
    val phase: ScanPhase get() = session.phase
    val inputSource: InputSource get() = session.inputSource
    val expectedFormat: ScanFormat? get() = session.expectedFormat
    val matchedCount: Int get() = session.matchedCount
    val qrPayload: String? get() = session.qrPayload
    val barcodePayload: String? get() = session.barcodePayload
    val result: MatchResult? get() = session.result
    val countdownSeconds: Int? get() = session.autoAdvanceSecondsRemaining
    val autoAdvanceEnabled: Boolean get() = session.autoAdvanceEnabled
    val autoAdvanceDelay: AutoAdvanceDelay get() = session.autoAdvanceDelay
    val cameraPermissionPermanentlyDenied: Boolean
        get() = cameraPermissionState == CameraPermissionState.PERMANENTLY_DENIED
    val scannerIssue: ScannerIssue get() = bluetoothIssue
    val scannerConfigurationState: ConfigurationState get() = bluetoothConfigurationState
    val isBluetoothFallbackActive: Boolean get() = bluetoothFallbackActive

    companion object {
        fun fromSession(
            session: ScanSessionState,
            sessionActive: Boolean = session.phase != ScanPhase.IDLE,
            sessionNameDraft: String = "",
            sessionName: String? = null,
            bluetoothReady: Boolean = false,
            bluetoothDeviceName: String? = null,
            bluetoothConfigurationState: ConfigurationState = ConfigurationState.Unavailable,
            cameraAvailable: Boolean = true,
            cameraPermissionDenied: Boolean = false,
            isCameraRunning: Boolean = false,
            isCameraStarting: Boolean = false,
            message: String? = null,
            lastInvalidReason: InvalidScanReason? = null,
            lastInvalidPayloadLength: Int? = null,
            debugDemoEnabled: Boolean = false,
            cameraPermissionState: CameraPermissionState = if (cameraPermissionDenied) {
                CameraPermissionState.DENIED
            } else {
                CameraPermissionState.UNKNOWN
            },
            cameraStartFailed: Boolean = false,
            bluetoothIssue: ScannerIssue = ScannerIssue.NONE,
            bluetoothFallbackActive: Boolean = false,
        ): ScanUiState = ScanUiState(
            sessionActive = sessionActive,
            sessionNameDraft = sessionNameDraft,
            sessionName = sessionName,
            session = session,
            bluetoothReady = bluetoothReady,
            bluetoothDeviceName = bluetoothDeviceName,
            bluetoothConfigurationState = bluetoothConfigurationState,
            cameraAvailable = cameraAvailable,
            cameraPermissionDenied = cameraPermissionDenied,
            cameraPermissionState = cameraPermissionState,
            isCameraRunning = isCameraRunning,
            isCameraStarting = isCameraStarting,
            cameraStartFailed = cameraStartFailed,
            bluetoothIssue = bluetoothIssue,
            bluetoothFallbackActive = bluetoothFallbackActive,
            message = message,
            lastInvalidReason = lastInvalidReason,
            lastInvalidPayloadLength = lastInvalidPayloadLength,
            debugDemoEnabled = debugDemoEnabled,
        )
    }
}

/** One-way UI intent. The host translates each action to reducer/coordinator calls. */
sealed interface ScanUiAction {
    data class SessionNameChanged(val name: String) : ScanUiAction
    data object StartSession : ScanUiAction
    data object EndSession : ScanUiAction
    data object RereadQr : ScanUiAction
    data object ManualNext : ScanUiAction
    data object StartCamera : ScanUiAction
    data object StopCamera : ScanUiAction
    data class SelectInputSource(val source: InputSource) : ScanUiAction
    data class ScanReceived(val payload: ScanPayload) : ScanUiAction
    data object CancelAutoAdvance : ScanUiAction
    data object Backgrounded : ScanUiAction
    data object Foregrounded : ScanUiAction
    /** Retry the remembered scanner after a temporary BLE loss/unavailable state. */
    data object ReconnectBluetooth : ScanUiAction
    data class SetAutoAdvanceEnabled(val enabled: Boolean) : ScanUiAction
    data class SetAutoAdvanceDelay(val delay: AutoAdvanceDelay) : ScanUiAction

    /** Explicit debug-only demo actions; no production UI emits these. */
    data object DemoMatch : ScanUiAction
    data object DemoMismatch : ScanUiAction
}
