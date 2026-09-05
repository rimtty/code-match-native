package jp.rimtty.codematch.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import jp.rimtty.codematch.core.data.SettingsRepository
import jp.rimtty.codematch.feature.settings.SettingsPresentationState
import jp.rimtty.codematch.feature.settings.SettingsUiAction
import jp.rimtty.codematch.feature.settings.SettingsUiState
import jp.rimtty.codematch.feedback.FeedbackPlayer
import jp.rimtty.codematch.locale.AppLanguageSynchronizer
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.ScannerIssue
import jp.rimtty.codematch.scanner.api.scannerIssueFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val scanner: ExternalScanner,
    private val feedbackPlayer: FeedbackPlayer,
    private val appLanguageSynchronizer: AppLanguageSynchronizer,
) : ViewModel() {
    /**
     * Scanner callbacks are subscribed through the fan-out API. Assigning the
     * legacy single listener here would detach ScanSessionCoordinator and
     * silently stop scan payload delivery.
     */
    private val scannerListener = object : ExternalScannerListener {
        override fun onIlluminationStateChanged(state: jp.rimtty.codematch.scanner.api.IlluminationState) {
            refreshScannerState()
        }
        override fun onTuningStateChanged(state: jp.rimtty.codematch.scanner.api.TuningState) {
            refreshScannerState()
        }
        override fun onConnectionStateChanged(state: ConnectionState) {
            refreshScannerState()
        }

        override fun onConfigurationStateChanged(state: jp.rimtty.codematch.scanner.api.ConfigurationState) {
            refreshScannerState()
        }
    }

    private var scannerListenerRegistered = false
    private val _state = MutableStateFlow(scannerState(SettingsUiState()))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        scannerListenerRegistered = scanner.addListener(scannerListener)
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _state.update { scannerState(it.copy(settings = settings)) }
            }
        }
    }

    override fun onCleared() {
        if (scannerListenerRegistered) {
            scanner.removeListener(scannerListener)
            scannerListenerRegistered = false
        }
        super.onCleared()
    }

    fun onAction(action: SettingsUiAction) {
        when (action) {
            is SettingsUiAction.SetIllumination -> {
                scanner.setIllumination(action.enabled)
                refreshScannerState()
            }
            SettingsUiAction.OpenSetupGuide ->
                _state.update { it.copy(setupGuideVisible = true) }
            SettingsUiAction.CloseSetupGuide ->
                _state.update { it.copy(setupGuideVisible = false) }
            SettingsUiAction.StartDiscovery -> {
                scanner.startDiscovery()
                refreshScannerState()
            }
            SettingsUiAction.StopDiscovery -> {
                scanner.stopDiscovery()
                refreshScannerState()
            }
            is SettingsUiAction.SelectDevice ->
                _state.update { it.copy(selectedDeviceId = action.device.id) }
            is SettingsUiAction.Connect -> {
                scanner.connect(action.device)
                _state.update { scannerState(it.copy(selectedDeviceId = action.device.id)) }
            }
            SettingsUiAction.Disconnect -> {
                scanner.disconnect()
                refreshScannerState()
            }
            SettingsUiAction.Reconnect -> {
                scanner.reconnectKnownDevice()
                refreshScannerState()
            }
            SettingsUiAction.RetryScanner -> {
                retryScanner()
            }
            is SettingsUiAction.SetAutoAdvanceEnabled ->
                viewModelScope.launch { repository.setAutoAdvanceEnabled(action.enabled) }
            is SettingsUiAction.SetAutoAdvanceDelay ->
                viewModelScope.launch { repository.setAutoAdvanceDelay(action.delay) }
            is SettingsUiAction.SetFeedbackVolume ->
                viewModelScope.launch { repository.setFeedbackVolume(action.volume) }
            is SettingsUiAction.SetSuccessSound ->
                viewModelScope.launch { repository.setSuccessSound(action.sound) }
            is SettingsUiAction.PreviewSuccessSound ->
                feedbackPlayer.playSuccess(action.sound, state.value.feedbackVolume)
            is SettingsUiAction.SetFailureSound ->
                viewModelScope.launch { repository.setFailureSound(action.sound) }
            is SettingsUiAction.PreviewFailureSound ->
                feedbackPlayer.playFailure(action.sound, state.value.feedbackVolume)
            SettingsUiAction.ShareDiagnostics, SettingsUiAction.SaveDiagnostics -> Unit // host-owned
            is SettingsUiAction.SetLanguage -> viewModelScope.launch {
                appLanguageSynchronizer.setLanguage(action.language)
            }
        }
    }

    fun refreshScannerState() {
        _state.update(::scannerState)
    }

    private fun scannerState(current: SettingsUiState): SettingsUiState = current.copy(
        illuminationState = scanner.illuminationState,
        tuningState = scanner.tuningState,
        devices = scanner.devices,
        connectionState = scanner.connectionState,
        configurationState = scanner.configurationState,
        diagnosticEvents = scanner.diagnosticEvents.takeLast(MAX_DIAGNOSTICS),
        scannerIssue = scannerIssueFor(scanner.connectionState, scanner.configurationState),
        presentation = if (!scanner.supportsConnectionControls) {
            SettingsPresentationState.RELEASE_CAMERA_ONLY
        } else {
            SettingsPresentationState.FAKE_BLE
        },
    )

    private fun retryScanner() {
        when {
            scanner.connectionState is ConnectionState.Searching -> {
                scanner.stopDiscovery()
                scanner.startDiscovery()
            }
            scanner.connectionState.isConnected &&
                scanner.configurationState is jp.rimtty.codematch.scanner.api.ConfigurationState.Failed -> {
                // A failed recovery/configuration must not be marked Ready by
                // the UI. Reconnect is the adapter-neutral way to obtain a
                // fresh handshake; the adapter owns the actual protocol.
                scanner.disconnect()
                scanner.reconnectKnownDevice()
            }
            scanner.connectionState.isConnected -> {
                // A connected/ready scanner has nothing to retry. Keep this
                // branch side-effect free so a stale button cannot rewrite
                // scanner settings.
            }
            else -> {
                val reconnected = scanner.reconnectKnownDevice()
                if (!reconnected) scanner.startDiscovery()
            }
        }
        refreshScannerState()
    }

    private companion object {
        /** Retained for share/save; the screen itself renders only the latest 20. */
        const val MAX_DIAGNOSTICS = 300
    }
}
