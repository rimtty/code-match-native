package jp.rimtty.codematch.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jp.rimtty.codematch.core.data.HistoryRepository
import jp.rimtty.codematch.core.data.SettingsRepository
import jp.rimtty.codematch.core.model.AppSettings
import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.MatchSession
import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.feedback.FeedbackPlayer
import jp.rimtty.codematch.feature.scan.ScanEffect
import jp.rimtty.codematch.feature.scan.ScanPhase
import jp.rimtty.codematch.feature.scan.ScanSessionCoordinator
import jp.rimtty.codematch.feature.scan.ScanSessionState
import jp.rimtty.codematch.feature.scan.ScanUiAction
import jp.rimtty.codematch.feature.scan.ScanUiState
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application-side owner for one scan session.
 *
 * The feature module remains stateless: this class translates [ScanUiAction]
 * into [ScanSessionCoordinator] calls, observes the repositories, and turns
 * only [ScanEffect.RecordMatch] into a history write. Scan payloads are never
 * sent to logs or scanner diagnostics.
 *
 * [ScanUiAction.EndSession] is the final action. The destination should show
 * its confirmation dialog outside this class and dispatch the action only
 * after the user confirms. [confirmEndSession] is provided as an explicit
 * call-site alternative for hosts that keep the action separate.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val scanner: ExternalScanner,
    private val feedbackPlayer: FeedbackPlayer,
) : ViewModel() {
    private val _state = MutableStateFlow(ScanUiState())

    /** Immutable state consumed by [jp.rimtty.codematch.feature.scan.ScanScreen]. */
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val initialized = CompletableDeferred<Unit>()
    private var coordinator: ScanSessionCoordinator? = null
    private var activeSessionId: String? = null
    private var activeSessionName: String? = null
    private var sessionNameDraft: String = ""
    private var latestSettings: AppSettings = AppSettings()
    private var countdownJob: Job? = null
    private var endingSession = false

    init {
        initialize()
    }

    /**
     * Translate one-way UI intent into repository/coordinator work.
     *
     * Camera frames are owned by the app's camera host. It sends accepted
     * camera callbacks back as [ScanUiAction.ScanReceived]. The small camera
     * lifecycle helpers below update presentation state without coupling this
     * ViewModel to CameraX.
     */
    fun onAction(action: ScanUiAction) {
        when (action) {
            is ScanUiAction.SessionNameChanged -> {
                sessionNameDraft = action.name
                _state.value = _state.value.copy(sessionNameDraft = action.name)
            }

            ScanUiAction.StartSession -> runWhenReady { beginSession() }
            ScanUiAction.EndSession -> confirmEndSession()
            ScanUiAction.RereadQr -> runWhenReady { coordinator?.rereadQr() }
            ScanUiAction.ManualNext -> runWhenReady { coordinator?.manualNext() }
            ScanUiAction.StartCamera -> runWhenReady { requestCameraStart() }
            ScanUiAction.StopCamera -> runWhenReady { requestCameraStop() }
            is ScanUiAction.SelectInputSource ->
                runWhenReady { selectInputSource(action.source) }

            is ScanUiAction.ScanReceived ->
                runWhenReady { coordinator?.submitScanPayload(action.payload) }

            ScanUiAction.CancelAutoAdvance ->
                runWhenReady { coordinator?.cancelAutoAdvance() }

            ScanUiAction.Backgrounded -> runWhenReady { backgrounded() }
            ScanUiAction.Foregrounded -> runWhenReady { foregrounded() }

            is ScanUiAction.SetAutoAdvanceEnabled ->
                runWhenReady { setAutoAdvanceEnabled(action.enabled) }

            is ScanUiAction.SetAutoAdvanceDelay ->
                runWhenReady { setAutoAdvanceDelay(action.delay) }

            ScanUiAction.DemoMatch -> runWhenReady { runDemo(shouldMatch = true) }
            ScanUiAction.DemoMismatch -> runWhenReady { runDemo(shouldMatch = false) }
        }
    }

    /**
     * Finish the active session after the host's confirmation dialog returns
     * positive. This method is intentionally separate from showing UI.
     */
    fun confirmEndSession() {
        runWhenReady { endSession() }
    }

    /** Update state after the camera host has successfully started capture. */
    fun onCameraStarted() {
        _state.value = _state.value.copy(
            isCameraStarting = false,
            isCameraRunning = true,
            cameraPermissionDenied = false,
        )
    }

    /** Update state after the camera host has stopped capture. */
    fun onCameraStopped() {
        _state.value = _state.value.copy(
            isCameraStarting = false,
            isCameraRunning = false,
        )
    }

    /** Report a denied camera permission without leaking a platform type. */
    fun onCameraPermissionDenied() {
        _state.value = _state.value.copy(
            isCameraStarting = false,
            isCameraRunning = false,
            cameraPermissionDenied = true,
        )
    }

    /** Report that no usable camera is present on the current device. */
    fun onCameraUnavailable() {
        _state.value = _state.value.copy(
            isCameraStarting = false,
            isCameraRunning = false,
            cameraAvailable = false,
        )
    }

    /** Restore camera availability after a transient host/device error. */
    fun onCameraAvailable() {
        _state.value = _state.value.copy(cameraAvailable = true)
    }

    /**
     * The debug UI must opt in explicitly. This does not import the debug Fake
     * scanner, so release builds retain the same source and dependency graph.
     */
    fun setDebugDemoEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(debugDemoEnabled = enabled)
    }

    /** Refresh scanner projection after a settings destination changes it. */
    fun refreshScannerState() {
        publishCoordinatorState()
    }

    override fun onCleared() {
        countdownJob?.cancel()
        countdownJob = null
        coordinator?.let { current ->
            if (scanner.listener === current) {
                scanner.listener = null
            }
        }
        super.onCleared()
    }

    private fun initialize() {
        viewModelScope.launch {
            try {
                val initial = combine(
                    settingsRepository.settings,
                    historyRepository.activeSession,
                ) { settings, session -> InitialData(settings, session) }.first()
                latestSettings = initial.settings
                installCoordinator(initial.settings, initial.activeSession)
                initialized.complete(Unit)

                launch {
                    settingsRepository.settings
                        .distinctUntilChanged()
                        .collect { settings -> applySettings(settings) }
                }
                launch {
                    historyRepository.activeSession
                        .map { session -> session?.id }
                        .distinctUntilChanged()
                        .collect { sessionId -> handleActiveSessionId(sessionId) }
                }
            } catch (error: Throwable) {
                if (!initialized.isCompleted) {
                    initialized.completeExceptionally(error)
                }
            }
        }
    }

    private fun installCoordinator(settings: AppSettings, active: MatchSession?) {
        coordinator?.let { current ->
            if (scanner.listener === current) scanner.listener = null
        }

        activeSessionId = active?.id
        activeSessionName = active?.name
        sessionNameDraft = active?.name.orEmpty()

        val created = ScanSessionCoordinator(
            scanner = scanner,
            autoAdvanceEnabled = settings.autoAdvanceEnabled,
            autoAdvanceDelay = settings.autoAdvanceDelay,
            existingMatchedCount = active?.matchedCount ?: 0,
        )
        created.onStateChanged = { publishCoordinatorState() }
        created.onEffects = ::handleEffects
        created.onInputSourceChanged = { publishCoordinatorState() }
        coordinator = created
        publishCoordinatorState()

        if (active != null) {
            created.startSession()
        }
    }

    private fun runWhenReady(block: suspend () -> Unit) {
        viewModelScope.launch {
            initialized.await()
            block()
        }
    }

    private suspend fun beginSession() {
        val current = coordinator ?: return
        if (activeSessionId == null) {
            val requestedName = sessionNameDraft.trim().takeIf { it.isNotEmpty() }
            val id = historyRepository.beginSession(name = requestedName)
            val session = historyRepository.getSession(id)
            activeSessionId = id
            activeSessionName = session?.name ?: requestedName

            // beginSession returns an existing active session when another
            // host won a race. Rebuild only while the local coordinator is
            // still idle so its injected count remains exact.
            if (session != null && session.matchedCount != current.state.matchedCount) {
                installCoordinator(latestSettings, session)
            }
        }

        coordinator?.let { activeCoordinator ->
            if (activeCoordinator.state.phase == ScanPhase.IDLE) {
                activeCoordinator.startSession()
            }
            publishCoordinatorState()
        }
    }

    private suspend fun endSession() {
        if (endingSession) return
        endingSession = true
        try {
            val id = activeSessionId
            coordinator?.endSession()
            activeSessionId = null
            activeSessionName = null
            sessionNameDraft = ""
            _state.value = _state.value.copy(
                sessionActive = false,
                sessionName = null,
                sessionNameDraft = "",
            )
            if (id != null) {
                historyRepository.endSession(id)
            }
        } finally {
            endingSession = false
        }
    }

    private fun selectInputSource(source: InputSource) {
        val current = coordinator ?: return
        val selected = current.selectInputSource(source)
        if (selected && source == InputSource.BLUETOOTH) {
            _state.value = _state.value.copy(
                isCameraStarting = false,
                isCameraRunning = false,
            )
        }
        publishCoordinatorState()
    }

    private fun requestCameraStart() {
        val current = coordinator ?: return
        if (current.state.expectedFormat == null || current.state.phase == ScanPhase.IDLE) return
        _state.value = _state.value.copy(
            isCameraStarting = true,
            isCameraRunning = false,
            cameraPermissionDenied = false,
        )
    }

    private fun requestCameraStop() {
        _state.value = _state.value.copy(
            isCameraStarting = false,
            isCameraRunning = false,
        )
    }

    private fun backgrounded() {
        coordinator?.onBackgrounded()
        _state.value = _state.value.copy(
            isCameraStarting = false,
            isCameraRunning = false,
        )
    }

    private fun foregrounded() {
        coordinator?.onForegrounded()
        publishCoordinatorState()
    }

    private fun setAutoAdvanceEnabled(enabled: Boolean) {
        coordinator?.setAutoAdvanceEnabled(enabled)
        viewModelScope.launch { settingsRepository.setAutoAdvanceEnabled(enabled) }
    }

    private fun setAutoAdvanceDelay(delay: AutoAdvanceDelay) {
        coordinator?.setAutoAdvanceDelay(delay)
        viewModelScope.launch { settingsRepository.setAutoAdvanceDelay(delay) }
    }

    private suspend fun applySettings(settings: AppSettings) {
        latestSettings = settings
        coordinator?.let { current ->
            if (current.state.autoAdvanceEnabled != settings.autoAdvanceEnabled) {
                current.setAutoAdvanceEnabled(settings.autoAdvanceEnabled)
            }
            if (current.state.autoAdvanceDelay != settings.autoAdvanceDelay) {
                current.setAutoAdvanceDelay(settings.autoAdvanceDelay)
            }
        }
        publishCoordinatorState()
    }

    private suspend fun handleActiveSessionId(sessionId: String?) {
        if (endingSession || sessionId == activeSessionId) return

        if (sessionId == null) {
            activeSessionId = null
            activeSessionName = null
            coordinator?.endSession()
            publishCoordinatorState()
            return
        }

        // A process-restored active session is installed by initialize(). A
        // later non-null id means another host created a session while this
        // ViewModel was idle; load it and use the same restoration path.
        val session = historyRepository.getSession(sessionId) ?: return
        if (activeSessionId == null && coordinator?.state?.phase == ScanPhase.IDLE) {
            installCoordinator(latestSettings, session)
        }
    }

    private fun handleEffects(effects: List<ScanEffect>) {
        effects.forEach { effect ->
            when (effect) {
                is ScanEffect.RecordMatch -> {
                    feedbackPlayer.playSuccess(
                        latestSettings.successSound,
                        latestSettings.feedbackVolume,
                        includeHaptic = true,
                    )
                    persistMatch(effect)
                }
                ScanEffect.ScanAccepted -> {
                    if (coordinator?.state?.result == MatchResult.MISMATCH) {
                        feedbackPlayer.playFailure(
                            latestSettings.failureSound,
                            latestSettings.feedbackVolume,
                            includeHaptic = true,
                        )
                    }
                }
                is ScanEffect.AutoAdvanceStarted -> startCountdown()
                ScanEffect.AutoAdvanceCancelled,
                ScanEffect.AutoAdvanceCompleted,
                ScanEffect.SessionEnded,
                -> cancelCountdown()
                else -> Unit
            }
        }
    }

    private fun persistMatch(effect: ScanEffect.RecordMatch) {
        val sessionId = activeSessionId ?: return
        viewModelScope.launch {
            // This is the sole history entry write in the scan integration.
            // Do not log or add the payloads to scanner diagnostics.
            historyRepository.recordMatch(
                code = effect.code,
                qrPayload = effect.qrPayload,
                barcodePayload = effect.barcodePayload,
                sessionId = sessionId,
            )
        }
    }

    private fun startCountdown() {
        cancelCountdown()
        countdownJob = viewModelScope.launch {
            while (isActive) {
                delay(COUNTDOWN_TICK_MILLIS)
                val current = coordinator ?: break
                current.tickAutoAdvance()
                if (current.state.autoAdvanceSecondsRemaining == null) break
            }
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
    }

    private fun runDemo(shouldMatch: Boolean) {
        if (!_state.value.debugDemoEnabled) return
        val current = coordinator ?: return
        if (current.state.phase == ScanPhase.IDLE || current.state.phase == ScanPhase.RESULT) return

        val source = current.inputSource
        current.submitScanPayload(
            ScanPayload.qr(
                value = SAMPLE_QR_PAYLOAD,
                source = source,
                timestampMillis = 0L,
            ),
        )
        val barcode = if (shouldMatch) SAMPLE_BARCODE_PAYLOAD else SAMPLE_MISMATCH_BARCODE_PAYLOAD
        if (source == InputSource.CAMERA) {
            // Camera Code 128 follows the production stabilizer contract:
            // two identical values inside the strict 1.5-second window.
            current.submitScanPayload(
                ScanPayload.code128(barcode, source, timestampMillis = 250L),
            )
            current.submitScanPayload(
                ScanPayload.code128(barcode, source, timestampMillis = 500L),
            )
        } else {
            current.submitScanPayload(
                ScanPayload.code128(barcode, source, timestampMillis = 0L),
            )
        }
    }

    private fun publishCoordinatorState() {
        val current = coordinator
        val session = current?.state ?: ScanSessionState()
        _state.value = _state.value.copy(
            sessionActive = activeSessionId != null && session.phase != ScanPhase.IDLE,
            sessionNameDraft = sessionNameDraft,
            sessionName = activeSessionName,
            session = session,
            bluetoothReady = scanner.isReadyForScanning,
            bluetoothDeviceName = scanner.connectedDevice?.name,
        )
    }

    private data class InitialData(
        val settings: AppSettings,
        val activeSession: MatchSession?,
    )

    companion object {
        private const val COUNTDOWN_TICK_MILLIS = 1_000L
        private const val SAMPLE_QR_PAYLOAD =
            "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
        private const val SAMPLE_BARCODE_PAYLOAD = "BCJH-52-81GG@1N5X0C"
        private const val SAMPLE_MISMATCH_BARCODE_PAYLOAD = "BCJH-55-81GG@1KVV0C"
    }
}
