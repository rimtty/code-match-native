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
import jp.rimtty.codematch.core.model.ScanSessionCheckpoint
import jp.rimtty.codematch.feedback.FeedbackPlayer
import jp.rimtty.codematch.feature.scan.ScanEffect
import jp.rimtty.codematch.feature.scan.CameraPermissionState
import jp.rimtty.codematch.feature.scan.ScanPhase
import jp.rimtty.codematch.feature.scan.ScanSessionCoordinator
import jp.rimtty.codematch.feature.scan.ScanSessionState
import jp.rimtty.codematch.feature.scan.ScanUiAction
import jp.rimtty.codematch.feature.scan.ScanUiState
import jp.rimtty.codematch.feature.scan.toScanSessionCheckpoint
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerIssue
import jp.rimtty.codematch.scanner.api.scannerIssueFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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

/** Logical feedback states emitted by the scan integration. */
internal enum class ScanFeedbackEvent {
    SCAN_ACCEPTED,
    INVALID_SCAN,
    MATCH,
    MISMATCH,
}

/**
 * Maps one reducer reduction to at most one cue for each logical state.
 *
 * A successful or mismatching Code 128 reduction contains ScanAccepted plus a
 * terminal result. The terminal result owns that reduction's cue, so the
 * accepted blip is emitted only for a non-terminal read. This avoids an
 * interrupting terminal cue cancelling the accepted audio and haptic twice.
 */
internal object ScanFeedbackEventMapper {
    fun map(
        effects: List<ScanEffect>,
        result: MatchResult?,
    ): List<ScanFeedbackEvent> = when {
        effects.any { it is ScanEffect.InvalidScan } ->
            listOf(ScanFeedbackEvent.INVALID_SCAN)
        effects.any { it is ScanEffect.RecordMatch } ->
            listOf(ScanFeedbackEvent.MATCH)
        (result == MatchResult.MISMATCH || result == MatchResult.DUPLICATE) &&
            effects.any { it === ScanEffect.ScanAccepted } ->
            listOf(ScanFeedbackEvent.MISMATCH)
        effects.any { it === ScanEffect.ScanAccepted } ->
            listOf(ScanFeedbackEvent.SCAN_ACCEPTED)
        else -> emptyList()
    }
}

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
    /** Serializes checkpoint writes so an older QR state cannot overwrite a newer result. */
    private var checkpointWriteJob: Job? = null
    private var endingSession = false
    /** User intent survives a configuration change; the physical host does not. */
    private var cameraResumeOnForeground = false
    /** Set only when a Bluetooth transport event forced camera fallback. */
    private var bluetoothFallbackActive = false
    private var bluetoothFallbackIssue = ScannerIssue.NONE

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

            ScanUiAction.ReconnectBluetooth ->
                runWhenReady { reconnectBluetooth() }

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
        if (_state.value.inputSource != InputSource.CAMERA || _state.value.expectedFormat == null) return
        _state.value = _state.value.copy(
            isCameraStarting = false,
            isCameraRunning = true,
            cameraPermissionDenied = false,
            cameraPermissionState = CameraPermissionState.GRANTED,
            cameraStartFailed = false,
            cameraAvailable = true,
        )
    }

    /** Update state after the camera host has stopped capture. */
    fun onCameraStopped() {
        _state.value = _state.value.copy(
            isCameraStarting = false,
            isCameraRunning = false,
        )
    }

    /** Show the transient platform permission request state. */
    fun onCameraPermissionRequesting() {
        _state.value = _state.value.copy(
            cameraPermissionState = CameraPermissionState.REQUESTING,
            cameraPermissionDenied = false,
            cameraStartFailed = false,
        )
    }

    /** Synchronize a permission result observed by a host before a start request. */
    fun onCameraPermissionGranted() {
        _state.value = _state.value.copy(
            cameraPermissionState = CameraPermissionState.GRANTED,
            cameraPermissionDenied = false,
            cameraStartFailed = false,
        )
    }

    /** Reconcile permission state reported at host creation or lifecycle start. */
    fun synchronizeCameraPermission(permission: CameraPermissionState) {
        when (permission) {
            CameraPermissionState.GRANTED -> onCameraPermissionGranted()
            CameraPermissionState.DENIED -> onCameraPermissionDenied(permanently = false)
            CameraPermissionState.PERMANENTLY_DENIED -> onCameraPermissionDenied(permanently = true)
            CameraPermissionState.UNKNOWN,
            CameraPermissionState.REQUESTING,
            -> Unit
        }
    }

    /** Report a denied camera permission without leaking a platform type. */
    fun onCameraPermissionDenied(permanently: Boolean = false) {
        cameraResumeOnForeground = false
        _state.value = _state.value.copy(
            isCameraStarting = false,
            isCameraRunning = false,
            cameraPermissionDenied = true,
            cameraPermissionState = if (permanently) {
                CameraPermissionState.PERMANENTLY_DENIED
            } else {
                CameraPermissionState.DENIED
            },
            cameraStartFailed = false,
        )
    }

    /** Report that no usable camera is present on the current device. */
    fun onCameraUnavailable() {
        cameraResumeOnForeground = false
        _state.value = _state.value.copy(
            isCameraStarting = false,
            isCameraRunning = false,
            cameraAvailable = false,
            cameraStartFailed = false,
        )
    }

    /** Restore camera availability after a transient host/device error. */
    fun onCameraAvailable() {
        _state.value = _state.value.copy(
            cameraAvailable = true,
            cameraStartFailed = false,
        )
    }

    /** Report a recoverable camera start failure without surfacing exception text. */
    fun onCameraStartFailed() {
        cameraResumeOnForeground = false
        _state.value = _state.value.copy(
            isCameraStarting = false,
            isCameraRunning = false,
            cameraStartFailed = true,
        )
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
        checkpointWriteJob?.cancel()
        checkpointWriteJob = null
        coordinator?.dispose()
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
                val checkpoint = initial.activeSession?.let { active ->
                    historyRepository.getScanCheckpoint(active.id)
                }
                installCoordinator(initial.settings, initial.activeSession, checkpoint)
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

    private fun installCoordinator(
        settings: AppSettings,
        active: MatchSession?,
        checkpoint: ScanSessionCheckpoint? = null,
    ) {
        coordinator?.dispose()

        activeSessionId = active?.id
        activeSessionName = active?.name
        sessionNameDraft = active?.name.orEmpty()

        val created = ScanSessionCoordinator(
            scanner = scanner,
            autoAdvanceEnabled = settings.autoAdvanceEnabled,
            autoAdvanceDelay = settings.autoAdvanceDelay,
            existingMatchedCount = active?.matchedCount ?: 0,
            restoredCheckpoint = checkpoint,
            matchedQrPayloads = active?.entries
                ?.mapNotNull { it.qrPayload }
                .orEmpty(),
        )
        created.onStateChanged = { publishCoordinatorState() }
        created.onEffects = ::handleEffects
        created.onInputSourceChanged = { source ->
            if (source == InputSource.BLUETOOTH) {
                // A Ready callback can promote a camera fallback back to BLE
                // without passing through the manual picker action. Clear
                // the host's running flags so a later camera selection can
                // start a fresh CameraX binding.
                cameraResumeOnForeground = false
                _state.value = _state.value.copy(
                    isCameraStarting = false,
                    isCameraRunning = false,
                )
            }
            publishCoordinatorState()
            enqueueCurrentCheckpoint()
        }
        created.onScannerConfigurationStateChanged = {
            publishCoordinatorState()
        }
        created.onBluetoothFallbackIssue = { issue ->
            bluetoothFallbackIssue = issue
        }
        created.onBluetoothFallback = {
            bluetoothFallbackActive = true
            publishCoordinatorState()
            if (_state.value.sessionActive &&
                _state.value.expectedFormat != null &&
                _state.value.cameraAvailable &&
                !_state.value.cameraPermissionPermanentlyDenied
            ) {
                // A transport failure is different from the user's manual
                // camera choice: resume capture automatically while keeping
                // the current QR/Code 128 step and any accepted QR value.
                requestCameraStart()
            }
        }
        coordinator = created
        bluetoothFallbackActive = false
        bluetoothFallbackIssue = ScannerIssue.NONE
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
            // host won a race. Rebuild while the local coordinator is still
            // idle so its injected count and durable checkpoint are exact.
            if (session != null && current.state.phase == ScanPhase.IDLE) {
                val checkpoint = historyRepository.getScanCheckpoint(session.id)
                installCoordinator(latestSettings, session, checkpoint)
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
            cameraResumeOnForeground = false
            val id = activeSessionId
            // The route asks the platform host to stop first. Reflect that
            // boundary immediately as a defensive fallback for non-Compose
            // callers of confirmEndSession().
            _state.value = _state.value.copy(
                isCameraStarting = false,
                isCameraRunning = false,
            )
            coordinator?.endSession()
            // The coordinator's terminal reduction queues a checkpoint clear,
            // while a preceding QR/terminal write may still be in flight.
            // Finish those operations before ending the Room session so an
            // older queued save cannot race the end transaction. The
            // repository also clears atomically inside endSession() as a
            // second line of defence for process death or other callers.
            checkpointWriteJob?.join()
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
        val previousInputSource = current.inputSource
        val previousCameraSelection = current.cameraWasSelectedByUser
        val selected = current.selectInputSource(source)
        if (selected && source == InputSource.CAMERA) {
            // Selecting a source is not the same as pressing Start camera;
            // the host starts only after the explicit camera action.
            cameraResumeOnForeground = false
            bluetoothFallbackActive = false
            bluetoothFallbackIssue = ScannerIssue.NONE
        }
        if (selected && source == InputSource.BLUETOOTH) {
            cameraResumeOnForeground = false
            bluetoothFallbackActive = false
            bluetoothFallbackIssue = ScannerIssue.NONE
            _state.value = _state.value.copy(
                isCameraStarting = false,
                isCameraRunning = false,
            )
        }
        publishCoordinatorState()
        // The explicit-camera bit is part of the checkpoint policy even when
        // the selected source was already CAMERA (or an unavailable Bluetooth
        // request kept the source on CAMERA). A real source change is already
        // persisted by onInputSourceChanged, so enqueue here only when the
        // policy changed without changing the source.
        if (previousInputSource == current.inputSource &&
            previousCameraSelection != current.cameraWasSelectedByUser
        ) {
            enqueueCurrentCheckpoint()
        }
    }

    private fun reconnectBluetooth() {
        val current = coordinator ?: return
        val reconnected = current.reconnectKnownDevice()
        if (!reconnected) {
            // Keep the fallback card visible. The scanner's typed unavailable
            // or failed state is projected below; no protocol command is
            // guessed by the UI.
            publishCoordinatorState()
            return
        }
        if (scanner.isReadyForScanning) {
            bluetoothFallbackActive = false
            bluetoothFallbackIssue = ScannerIssue.NONE
        }
        publishCoordinatorState()
    }

    private fun requestCameraStart() {
        val current = coordinator ?: return
        if (current.state.expectedFormat == null || current.state.phase == ScanPhase.IDLE) return
        if (_state.value.cameraPermissionPermanentlyDenied) return
        if (_state.value.isCameraRunning || _state.value.isCameraStarting) return
        cameraResumeOnForeground = true
        _state.value = _state.value.copy(
            isCameraStarting = true,
            isCameraRunning = false,
            cameraPermissionDenied = false,
            cameraPermissionState = CameraPermissionState.UNKNOWN,
            cameraStartFailed = false,
        )
    }

    private fun requestCameraStop() {
        cameraResumeOnForeground = false
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
        if (cameraResumeOnForeground &&
            _state.value.sessionActive &&
            _state.value.inputSource == InputSource.CAMERA &&
            _state.value.expectedFormat != null &&
            !_state.value.cameraPermissionPermanentlyDenied
        ) {
            requestCameraStart()
        } else {
            publishCoordinatorState()
        }
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
            val checkpoint = historyRepository.getScanCheckpoint(session.id)
            installCoordinator(latestSettings, session, checkpoint)
        }
    }

    private fun handleEffects(effects: List<ScanEffect>) {
        val currentResult = coordinator?.state?.result
        val currentCheckpoint = coordinator?.state
            ?.toScanSessionCheckpoint(
                sessionId = activeSessionId ?: "",
                cameraWasSelectedByUser = coordinator?.cameraWasSelectedByUser ?: false,
            )
        val matchEffect = effects.filterIsInstance<ScanEffect.RecordMatch>().firstOrNull()

        // Persist the logical transition after every state-changing reducer
        // event. A terminal match uses the repository's atomic entry+
        // checkpoint transaction; all other non-idle states only replace the
        // single checkpoint row. Invalid callbacks do not rewrite state.
        if (matchEffect != null && currentCheckpoint != null) {
            persistMatch(matchEffect, currentCheckpoint)
        } else if (activeSessionId != null &&
            effects.none { it is ScanEffect.InvalidScan }
        ) {
            if (effects.any { it === ScanEffect.SessionEnded }) {
                val sessionId = activeSessionId ?: return
                enqueueCheckpointOperation {
                    historyRepository.clearScanCheckpoint(sessionId)
                }
            } else if (currentCheckpoint != null) {
                val checkpoint = currentCheckpoint
                enqueueCheckpointOperation {
                    historyRepository.saveScanCheckpoint(checkpoint)
                }
            }
        }

        val invalid = effects.filterIsInstance<ScanEffect.InvalidScan>().firstOrNull()
        when {
            invalid != null -> {
                // The feature renders this typed reason through localized
                // resources. Do not surface scanner/exception text here.
                _state.value = _state.value.copy(
                    message = null,
                    lastInvalidReason = invalid.reason,
                    lastInvalidPayloadLength = invalid.observedLength,
                )
            }
            effects.any { it === ScanEffect.ScanAccepted } -> {
                _state.value = _state.value.copy(
                    message = null,
                    lastInvalidReason = null,
                    lastInvalidPayloadLength = null,
                )
            }
        }

        ScanFeedbackEventMapper.map(effects, currentResult).forEach { event ->
            when (event) {
                ScanFeedbackEvent.SCAN_ACCEPTED ->
                    feedbackPlayer.playScanAccepted(latestSettings.feedbackVolume)
                ScanFeedbackEvent.INVALID_SCAN ->
                    feedbackPlayer.playInvalidScan(latestSettings.feedbackVolume)
                ScanFeedbackEvent.MATCH -> {
                    feedbackPlayer.playSuccess(
                        latestSettings.successSound,
                        latestSettings.feedbackVolume,
                    )
                    // Reducer output contains one RecordMatch at most. Taking
                    // the first one protects the integration from accidental
                    // duplicate terminal effects without dropping a valid row.
                    // The durable write is queued above together with the
                    // matching checkpoint, so no second history write is
                    // started here.
                    Unit
                }
                ScanFeedbackEvent.MISMATCH ->
                    feedbackPlayer.playFailure(
                        latestSettings.failureSound,
                        latestSettings.feedbackVolume,
                    )
            }
        }

        effects.forEach { effect ->
            when (effect) {
                is ScanEffect.AutoAdvanceStarted -> startCountdown()
                ScanEffect.AutoAdvanceCancelled,
                ScanEffect.AutoAdvanceCompleted,
                ScanEffect.SessionEnded,
                -> cancelCountdown()
                else -> Unit
            }
        }
    }

    private fun persistMatch(
        effect: ScanEffect.RecordMatch,
        checkpoint: ScanSessionCheckpoint,
    ) {
        val sessionId = activeSessionId ?: return
        enqueueCheckpointOperation {
            // This is the sole history entry write in the scan integration.
            // Do not log or add the payloads to scanner diagnostics.
            historyRepository.recordMatch(
                code = effect.code,
                qrPayload = effect.qrPayload,
                barcodePayload = effect.barcodePayload,
                sessionId = sessionId,
                checkpoint = checkpoint,
            )
        }
    }

    /** Queue writes in reducer order so an older QR state cannot win a race. */
    private fun enqueueCheckpointOperation(operation: suspend () -> Unit) {
        val previous = checkpointWriteJob
        checkpointWriteJob = viewModelScope.launch {
            try {
                previous?.join()
                operation()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Persistence failures must not crash the scan UI. The next
                // state transition can attempt the checkpoint again, while a
                // missing/corrupt row safely falls back to Waiting QR.
            }
        }
    }

    private fun enqueueCurrentCheckpoint() {
        val sessionId = activeSessionId ?: return
        val checkpoint = coordinator?.state?.toScanSessionCheckpoint(
            sessionId = sessionId,
            cameraWasSelectedByUser = coordinator?.cameraWasSelectedByUser ?: false,
        ) ?: return
        enqueueCheckpointOperation {
            historyRepository.saveScanCheckpoint(checkpoint)
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
        val currentScannerIssue = scannerIssueFor(
            scanner.connectionState,
            scanner.configurationState,
        )
        val projectedBluetoothIssue = when {
            scanner.isReadyForScanning && current?.inputSource == InputSource.BLUETOOTH -> {
                bluetoothFallbackActive = false
                bluetoothFallbackIssue = ScannerIssue.NONE
                ScannerIssue.NONE
            }
            bluetoothFallbackActive && bluetoothFallbackIssue != ScannerIssue.NONE ->
                bluetoothFallbackIssue
            else -> currentScannerIssue
        }
        _state.value = _state.value.copy(
            sessionActive = activeSessionId != null && session.phase != ScanPhase.IDLE,
            sessionNameDraft = sessionNameDraft,
            sessionName = activeSessionName,
            session = session,
            // Baseline-ready scanners must be selectable before the physical
            // QR/Code 128 restriction is applied. Payload-ready remains the
            // stricter adapter state used to forward scan callbacks.
            bluetoothReady = scanner.isReadyToStartSession,
            bluetoothDeviceName = scanner.connectedDevice?.name,
            bluetoothConfigurationState = scanner.configurationState,
            bluetoothIssue = projectedBluetoothIssue,
            bluetoothFallbackActive = bluetoothFallbackActive,
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
