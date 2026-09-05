package jp.rimtty.codematch.feature.settings

import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.AppSettings
import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.FailureSound
import jp.rimtty.codematch.core.model.SuccessSound
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.ScannerDevice
import jp.rimtty.codematch.scanner.api.ScannerIssue
import jp.rimtty.codematch.scanner.api.scannerIssueFor

/**
 * Which scanner controls should be presented to the user.
 *
 * The fake-BLE presentation is useful for development and acceptance tests.
 * Production can explicitly select [RELEASE_CAMERA_ONLY] until a supported
 * Android BLE adapter is available. Keeping this in value state makes it
 * impossible for a Composable to accidentally infer a release capability from
 * a scanner implementation detail.
 */
enum class SettingsPresentationState {
    FAKE_BLE,
    RELEASE_CAMERA_ONLY,
}

/** Familiar aliases for callers that use scanner-oriented terminology. */
typealias SettingsPresentation = SettingsPresentationState
typealias ScannerPresentation = SettingsPresentationState
typealias ScannerPresentationMode = SettingsPresentationState

/** Stable tags shared by Compose tests and accessibility checks. */
object SettingsTestTags {
    const val SCREEN = "settings_screen"
    const val SETUP_GUIDE = "settings_scanner_setup_guide"
    const val SETUP_GUIDE_STEP_1 = "settings_setup_step_1"
    const val SETUP_GUIDE_STEP_2 = "settings_setup_step_2"
    const val SETUP_GUIDE_STEP_3 = "settings_setup_step_3"
    const val SETUP_GUIDE_CLOSE = "settings_setup_guide_close"
    const val SETUP_GUIDE_OPEN = "settings_setup_guide_open"
    const val SETUP_BARCODE = "settings_setup_barcode"
    const val SETUP_ENLARGE = "settings_setup_enlarge"
    const val SETUP_FULLSCREEN_BARCODE = "settings_setup_fullscreen_barcode"
    const val SETUP_FULLSCREEN_CLOSE = "settings_setup_fullscreen_close"
    const val SETUP_PREVIOUS = "settings_setup_previous"
    const val SETUP_NEXT = "settings_setup_next"
    const val SCANNER_SECTION = "settings_scanner_section"
    const val SCANNER_STATUS = "settings_scanner_status"
    const val SCANNER_PROGRESS = "settings_scanner_progress"
    const val SCANNER_CONFIGURATION_STATUS = "settings_scanner_configuration_status"
    const val DISCOVERY = "settings_scanner_discovery"
    const val DEVICE_ROW = "settings_scanner_device"
    const val CONNECT = "settings_scanner_connect"
    const val DISCONNECT = "settings_scanner_disconnect"
    const val RECONNECT = "settings_scanner_reconnect"
    const val RETRY = "settings_scanner_retry"
    const val OPEN_BLUETOOTH_SETTINGS = "settings_scanner_open_bluetooth_settings"
    const val SCANNER_ISSUE = "settings_scanner_issue"
    const val SCANNER_ISSUE_MESSAGE = "settings_scanner_issue_message"
    const val DIAGNOSTICS = "settings_scanner_diagnostics"
    const val DIAGNOSTICS_TOGGLE = "settings_scanner_diagnostics_toggle"
    const val DIAGNOSTIC_ROW = "settings_scanner_diagnostic"
    const val CAMERA_ONLY = "settings_camera_only"
    const val AUTO_ADVANCE = "settings_auto_advance"
    const val AUTO_ADVANCE_SWITCH = "settings_auto_advance_switch"
    const val DELAY_CHOICES = "settings_auto_advance_delay_choices"
    const val DELAY_CHOICE = "settings_auto_advance_delay"
    const val VOLUME = "settings_volume"
    const val SUCCESS_SOUNDS = "settings_success_sounds"
    const val SUCCESS_SOUND = "settings_success_sound"
    const val SUCCESS_PREVIEW = "settings_success_preview"
    const val FAILURE_SOUNDS = "settings_failure_sounds"
    const val FAILURE_SOUND = "settings_failure_sound"
    const val FAILURE_PREVIEW = "settings_failure_preview"
    const val LANGUAGE = "settings_language"
    const val LANGUAGE_CHOICE = "settings_language_choice"

    fun setupBarcode(code: BluetoothScannerSetupCode): String =
        "${SETUP_BARCODE}_${code.accessibilityId}"

    fun setupEnlarge(code: BluetoothScannerSetupCode): String =
        "${SETUP_ENLARGE}_${code.accessibilityId}"

    fun setupFullscreenBarcode(code: BluetoothScannerSetupCode): String =
        "${SETUP_FULLSCREEN_BARCODE}_${code.accessibilityId}"
}

/**
 * All input needed by the settings destination.
 *
 * This is deliberately a plain immutable value object. A ViewModel can derive
 * it from [jp.rimtty.codematch.core.data.SettingsRepository] and an
 * [jp.rimtty.codematch.scanner.api.ExternalScanner], while previews and tests
 * can construct it without Android services, a repository, or Bluetooth.
 */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val devices: List<ScannerDevice> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Idle,
    val configurationState: ConfigurationState = ConfigurationState.Unavailable,
    val diagnosticEvents: List<DiagnosticEvent> = emptyList(),
    val selectedDeviceId: String? = null,
    val setupGuideVisible: Boolean = false,
    val presentation: SettingsPresentationState = SettingsPresentationState.FAKE_BLE,
    /** Typed scanner issue; raw adapter reason strings never reach the UI. */
    val scannerIssue: ScannerIssue = ScannerIssue.NONE,
    val illuminationState: jp.rimtty.codematch.scanner.api.IlluminationState =
        jp.rimtty.codematch.scanner.api.IlluminationState.UNSUPPORTED,
) {
    /** Compatibility/readability aliases for hosts that name these values explicitly. */
    val appSettings: AppSettings get() = settings
    val scannerDevices: List<ScannerDevice> get() = devices
    val scannerConnectionState: ConnectionState get() = connectionState
    val scannerConfigurationState: ConfigurationState get() = configurationState
    val diagnostics: List<DiagnosticEvent> get() = diagnosticEvents
    val selectedDevice: ScannerDevice?
        get() = devices.firstOrNull { it.id == selectedDeviceId }
            ?: connectionState.connectedDevice
    val scannerPresentation: SettingsPresentationState get() = presentation
    val presentationState: SettingsPresentationState get() = presentation
    val showSetupGuide: Boolean get() = setupGuideVisible
    val isReleaseCameraOnly: Boolean
        get() = presentation == SettingsPresentationState.RELEASE_CAMERA_ONLY
    val cameraOnly: Boolean get() = isReleaseCameraOnly
    /** Derive a typed issue for direct feature previews/tests as well as app state. */
    val resolvedScannerIssue: ScannerIssue
        get() = if (scannerIssue != ScannerIssue.NONE) {
            scannerIssue
        } else {
            scannerIssueFor(connectionState, configurationState)
        }
    val bluetoothIssue: ScannerIssue get() = resolvedScannerIssue

    val autoAdvanceEnabled: Boolean get() = settings.autoAdvanceEnabled
    val autoAdvanceDelay: AutoAdvanceDelay get() = settings.autoAdvanceDelay
    val feedbackVolume: Float get() = settings.feedbackVolume
    val successSound: SuccessSound get() = settings.successSound
    val failureSound: FailureSound get() = settings.failureSound
    val language: AppLanguage get() = settings.language
}

/** One-way intents emitted by the stateless settings UI. */
sealed interface SettingsUiAction {
    data class SetIllumination(val enabled: Boolean) : SettingsUiAction
    data object OpenSetupGuide : SettingsUiAction
    data object CloseSetupGuide : SettingsUiAction

    data object StartDiscovery : SettingsUiAction
    data object StopDiscovery : SettingsUiAction
    data class SelectDevice(val device: ScannerDevice) : SettingsUiAction
    data class Connect(val device: ScannerDevice) : SettingsUiAction
    data object Disconnect : SettingsUiAction
    data object Reconnect : SettingsUiAction
    /** Retry the current discovery, connection, or recovery operation. */
    data object RetryScanner : SettingsUiAction

    data class SetAutoAdvanceEnabled(val enabled: Boolean) : SettingsUiAction
    data class SetAutoAdvanceDelay(val delay: AutoAdvanceDelay) : SettingsUiAction
    data class SetFeedbackVolume(val volume: Float) : SettingsUiAction

    data class SetSuccessSound(val sound: SuccessSound) : SettingsUiAction
    data class PreviewSuccessSound(val sound: SuccessSound) : SettingsUiAction
    data class SetFailureSound(val sound: FailureSound) : SettingsUiAction
    data class PreviewFailureSound(val sound: FailureSound) : SettingsUiAction
    data class SetLanguage(val language: AppLanguage) : SettingsUiAction
}

typealias SettingsAction = SettingsUiAction
