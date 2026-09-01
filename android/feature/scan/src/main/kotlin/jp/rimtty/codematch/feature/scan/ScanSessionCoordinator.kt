package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload

/**
 * Bridges scanner lifecycle callbacks to the pure [ScanReducer].
 *
 * This class owns only input-source policy: a ready connected scanner is the
 * initial source, a user camera choice wins over later connection callbacks,
 * and a disconnect falls back to camera without discarding the current step or
 * QR value. Persistence and Compose state collection are intentionally outside
 * this M2 contract.
 */
class ScanSessionCoordinator(
    private val scanner: ExternalScanner,
    private val reducer: ScanReducer = ScanReducer(),
    autoAdvanceEnabled: Boolean = false,
    autoAdvanceDelay: AutoAdvanceDelay = AutoAdvanceDelay.THREE_SECONDS,
    existingMatchedCount: Int = 0,
    private val cameraStabilizer: ScanStabilizer = ScanStabilizer(),
) : ExternalScannerListener {
    private val cameraAcceptanceLock = ScanAcceptanceLock()
    private var applyingScannerFormat = false

    var state: ScanSessionState = ScanReducer.initial(
        autoAdvanceEnabled = autoAdvanceEnabled,
        autoAdvanceDelay = autoAdvanceDelay,
        existingMatchedCount = existingMatchedCount,
    )
        private set

    var inputSource: InputSource = InputSource.CAMERA
        private set

    /** True after an explicit camera selection until the user selects Bluetooth. */
    var cameraWasSelectedByUser: Boolean = false
        private set

    /** Prevents a synchronous scanner callback from restarting input in the background. */
    var isBackgrounded: Boolean = false
        private set

    var lastEffects: List<ScanEffect> = emptyList()
        private set

    var onStateChanged: ((ScanSessionState) -> Unit)? = null
    var onEffects: ((List<ScanEffect>) -> Unit)? = null
    var onInputSourceChanged: ((InputSource) -> Unit)? = null
    /** Invoked only when a lost/unready Bluetooth link forces camera fallback. */
    var onBluetoothFallback: (() -> Unit)? = null

    init {
        scanner.listener = this
        // A scanner may already be connected before the scan feature is
        // constructed. It becomes the default only once a session starts.
        handleConnectionState(scanner.connectionState)
    }

    fun startSession(): ScanReduction {
        if (scanner.isReadyForScanning && !cameraWasSelectedByUser) {
            setInputSource(InputSource.BLUETOOTH)
        }
        return dispatch(ScanEvent.StartSession)
    }

    fun endSession(): ScanReduction = dispatch(ScanEvent.EndSession)

    fun rereadQr(): ScanReduction = dispatch(ScanEvent.RereadQr)

    fun manualNext(): ScanReduction = dispatch(ScanEvent.ManualNext)

    fun tickAutoAdvance(seconds: Int = 1): ScanReduction =
        dispatch(ScanEvent.AutoAdvanceTick(seconds))

    fun setAutoAdvanceEnabled(enabled: Boolean): ScanReduction =
        dispatch(ScanEvent.SetAutoAdvanceEnabled(enabled))

    fun setAutoAdvanceDelay(delay: AutoAdvanceDelay): ScanReduction =
        dispatch(ScanEvent.SetAutoAdvanceDelay(delay))

    fun onBackgrounded(): ScanReduction = dispatch(ScanEvent.Backgrounded)

    fun onForegrounded(): ScanReduction = dispatch(ScanEvent.Foregrounded)

    fun cancelAutoAdvance(): ScanReduction = dispatch(ScanEvent.CancelAutoAdvance)

    /** Feed a camera callback or a scanner callback through source filtering. */
    fun submitScanPayload(payload: ScanPayload): ScanReduction? {
        // CameraX/ML Kit and BLE callbacks may complete after lifecycle stop.
        // Never let a delayed result mutate a backgrounded session.
        if (isBackgrounded) return null
        if (payload.source != inputSource) return null

        val timestamp = payload.timestampMillis
        if (payload.source == InputSource.CAMERA && cameraAcceptanceLock.isLocked(timestamp)) {
            return null
        }

        val payloadToDispatch = if (
            payload.source == InputSource.CAMERA &&
            payload.format == ScanFormat.CODE_128 &&
            state.phase == ScanPhase.WAITING_CODE_128
        ) {
            when (val stabilization = cameraStabilizer.submit(payload.value, timestamp)) {
                is ScanStabilizationResult.Accepted -> payload.copy(value = stabilization.value)
                ScanStabilizationResult.Pending,
                ScanStabilizationResult.Locked,
                ScanStabilizationResult.Rejected,
                -> return null
            }
        } else {
            payload
        }

        val reduction = dispatch(ScanEvent.PayloadReceived(payloadToDispatch))
        if (payload.source == InputSource.CAMERA &&
            reduction.effects.any { it === ScanEffect.ScanAccepted }
        ) {
            cameraAcceptanceLock.acquire(timestamp)
        }
        return reduction
    }

    fun handleScanPayload(payload: ScanPayload): ScanReduction? = submitScanPayload(payload)

    /**
     * Select an input source. Selecting Bluetooth while unavailable keeps the
     * camera active and allows a later connection-ready callback to promote it.
     */
    fun selectInputSource(source: InputSource): Boolean {
        if (state.phase == ScanPhase.IDLE || state.phase == ScanPhase.RESULT) {
            // Result remains a valid session state, but no scanner input should
            // be started until the user chooses next. Keep selection for the
            // next logical step without accepting a payload in the meantime.
            if (state.phase == ScanPhase.IDLE) return false
        }

        return when (source) {
            InputSource.CAMERA -> {
                cameraWasSelectedByUser = true
                setInputSource(InputSource.CAMERA)
                applyExpectedFormat(null)
                true
            }

            InputSource.BLUETOOTH -> {
                cameraWasSelectedByUser = false
                if (!scanner.isReadyForScanning) {
                    setInputSource(InputSource.CAMERA)
                    applyExpectedFormat(null)
                    false
                } else {
                    setInputSource(InputSource.BLUETOOTH)
                    applyExpectedFormat()
                    true
                }
            }
        }
    }

    /** Explicit reconnect entry point for the settings screen. */
    fun reconnectKnownDevice(): Boolean = scanner.reconnectKnownDevice()

    /** Dispatch a reducer event and apply scanner-related effects. */
    fun dispatch(event: ScanEvent): ScanReduction {
        when (event) {
            ScanEvent.Backgrounded -> isBackgrounded = true
            ScanEvent.Foregrounded -> isBackgrounded = false
            ScanEvent.StartSession -> isBackgrounded = false
            ScanEvent.EndSession -> isBackgrounded = false
            else -> Unit
        }
        when (event) {
            ScanEvent.StartSession,
            ScanEvent.RereadQr,
            ScanEvent.ManualNext,
            ScanEvent.EndSession,
            -> {
                cameraStabilizer.reset()
                cameraAcceptanceLock.reset()
            }
            else -> Unit
        }
        val reduction = reducer.reduce(state, event)
        state = reduction.state
        lastEffects = reduction.effects
        applyEffects(reduction.effects)
        onStateChanged?.invoke(state)
        onEffects?.invoke(reduction.effects)
        return reduction
    }

    override fun onConnectionStateChanged(state: ConnectionState) {
        handleConnectionState(state)
    }

    override fun onConfigurationStateChanged(state: ConfigurationState) {
        when (state) {
            ConfigurationState.Ready -> handleConnectionState(scanner.connectionState)
            is ConfigurationState.Failed -> fallbackToCameraIfBluetooth()
            ConfigurationState.Unavailable -> {
                if (scanner.connectionState.connectedDevice == null) {
                    fallbackToCameraIfBluetooth()
                }
            }
            ConfigurationState.Configuring -> Unit
        }
    }

    override fun onScanPayload(payload: ScanPayload) {
        submitScanPayload(payload)
    }

    private fun handleConnectionState(connectionState: ConnectionState) {
        if (connectionState.connectedDevice != null) {
            if (state.phase != ScanPhase.IDLE &&
                scanner.configurationState === ConfigurationState.Ready &&
                !cameraWasSelectedByUser &&
                !isBackgrounded
            ) {
                setInputSource(InputSource.BLUETOOTH)
                applyExpectedFormat()
            }
            return
        }

        fallbackToCameraIfBluetooth()
    }

    private fun fallbackToCameraIfBluetooth() {
        if (inputSource != InputSource.BLUETOOTH) return
        setInputSource(InputSource.CAMERA)
        applyExpectedFormat(null)
        onBluetoothFallback?.invoke()
    }

    private fun applyEffects(effects: List<ScanEffect>) {
        effects.forEach { effect ->
            when (effect) {
                is ScanEffect.ExpectFormat -> applyExpectedFormat(effect.format)
                ScanEffect.StopInput -> applyExpectedFormat(null)
                is ScanEffect.ResumeInput -> applyExpectedFormat(effect.format)
                ScanEffect.SessionEnded -> applyExpectedFormat(null)
                else -> Unit
            }
        }
    }

    private fun applyExpectedFormat(format: ScanFormat? = state.expectedFormat) {
        val expected = if (inputSource == InputSource.BLUETOOTH) format else null
        // Real and fake adapters may synchronously invoke Ready from
        // setExpectedFormat. Do not re-enter the adapter from that callback.
        if (applyingScannerFormat) return
        if (scanner.expectedFormat == expected &&
            (expected == null || scanner.configurationState === ConfigurationState.Ready)
        ) return

        applyingScannerFormat = true
        try {
            scanner.setExpectedFormat(expected)
        } finally {
            applyingScannerFormat = false
        }
    }

    private fun setInputSource(source: InputSource) {
        if (inputSource == source) return
        inputSource = source
        state = state.copy(inputSource = source)
        onInputSourceChanged?.invoke(source)
    }
}

typealias ScanController = ScanSessionCoordinator
