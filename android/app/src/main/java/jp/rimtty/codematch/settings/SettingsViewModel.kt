package jp.rimtty.codematch.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import jp.rimtty.codematch.core.data.SettingsRepository
import jp.rimtty.codematch.feature.settings.SettingsPresentationState
import jp.rimtty.codematch.feature.settings.SettingsUiAction
import jp.rimtty.codematch.feature.settings.SettingsUiState
import jp.rimtty.codematch.feedback.FeedbackPlayer
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.ExternalScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val scanner: ExternalScanner,
    private val feedbackPlayer: FeedbackPlayer,
) : ViewModel() {
    private val _state = MutableStateFlow(scannerState(SettingsUiState()))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _state.update { scannerState(it.copy(settings = settings)) }
            }
        }
    }

    fun onAction(action: SettingsUiAction) {
        when (action) {
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
            is SettingsUiAction.SetLanguage -> viewModelScope.launch {
                repository.setLanguage(action.language)
                withContext(Dispatchers.Main.immediate) {
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(action.language.code),
                    )
                }
            }
        }
    }

    fun refreshScannerState() {
        _state.update(::scannerState)
    }

    private fun scannerState(current: SettingsUiState): SettingsUiState = current.copy(
        devices = scanner.devices,
        connectionState = scanner.connectionState,
        configurationState = scanner.configurationState,
        diagnosticEvents = scanner.diagnosticEvents.takeLast(MAX_DIAGNOSTICS),
        presentation = if (
            scanner.connectionState is ConnectionState.Unavailable && scanner.devices.isEmpty()
        ) {
            SettingsPresentationState.RELEASE_CAMERA_ONLY
        } else {
            SettingsPresentationState.FAKE_BLE
        },
    )

    private companion object {
        const val MAX_DIAGNOSTICS = 20
    }
}
