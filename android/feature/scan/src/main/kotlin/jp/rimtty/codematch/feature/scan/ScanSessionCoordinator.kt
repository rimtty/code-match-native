package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.matching.CodeMatcher
import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.ScanSessionCheckpoint
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerIssue
import jp.rimtty.codematch.scanner.api.scannerIssueFor

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
    restoredCheckpoint: ScanSessionCheckpoint? = null,
    matchedQrPayloads: Collection<String> = emptyList(),
) : ExternalScannerListener {
    private val cameraAcceptanceLock = ScanAcceptanceLock()
    private var applyingScannerFormat = false
    private val matchedQrPayloadIdentities = matchedQrPayloads
        .map(CodeMatcher::payloadIdentity)
        .filterTo(linkedSetOf()) { it.isNotEmpty() }
    private val restoredState: ScanSessionState? = restoredCheckpoint?.toScanSessionState(
        autoAdvanceEnabled = autoAdvanceEnabled,
        autoAdvanceDelay = autoAdvanceDelay,
    )?.copy(matchedQrPayloadIdentities = matchedQrPayloadIdentities)
    private val hasRestoredState: Boolean = restoredState != null

    var state: ScanSessionState = restoredState ?: ScanReducer.initial(
        autoAdvanceEnabled = autoAdvanceEnabled,
        autoAdvanceDelay = autoAdvanceDelay,
        existingMatchedCount = existingMatchedCount,
        matchedQrPayloads = matchedQrPayloads,
    )
        private set

    var inputSource: InputSource = state.inputSource
        private set

    /** True after an explicit camera selection until the user selects Bluetooth. */
    var cameraWasSelectedByUser: Boolean = restoredCheckpoint?.cameraWasSelectedByUser ?: false
        private set

    /** Prevents a synchronous scanner callback from restarting input in the background. */
    var isBackgrounded: Boolean = false
        private set

    /**
     * Keeps a scanner with an unverified connected configuration from being
     * promoted back to Bluetooth while fallback restores its baseline. A
     * later explicit reconnect or Bluetooth selection clears this hold.
     */
    private var bluetoothFallbackBlocksPromotion = false

    var lastEffects: List<ScanEffect> = emptyList()
        private set

    var onStateChanged: ((ScanSessionState) -> Unit)? = null
    var onEffects: ((List<ScanEffect>) -> Unit)? = null
    var onInputSourceChanged: ((InputSource) -> Unit)? = null
    /** Invoked only when a lost/unready Bluetooth link forces camera fallback. */
    var onBluetoothFallback: (() -> Unit)? = null
    /** Typed reason captured before fallback restores the scanner baseline. */
    var onBluetoothFallbackIssue: ((ScannerIssue) -> Unit)? = null
    /** Publishes scanner configuration transitions without exposing adapter details. */
    var onScannerConfigurationStateChanged: ((ConfigurationState) -> Unit)? = null

    init {
        // Settings and Scan are separate destinations but observe the same
        // scanner. Use the fan-out contract so installing this coordinator
        // never steals the settings observer (or vice versa).
        scanner.addListener(this)
        // A scanner may already be connected before the scan feature is
        // constructed. It becomes the default only once a session starts.
        handleConnectionState(scanner.connectionState)
    }

    /** Stop receiving transport callbacks when the owning ViewModel is cleared. */
    fun dispose() {
        scanner.removeListener(this)
    }

    fun startSession(): ScanReduction {
        if (hasRestoredState && state.phase != ScanPhase.IDLE) {
            // A restored result/waiting step is already a live session. Do not
            // feed StartSession through the reducer: that would erase the
            // accepted QR/barcode or re-trigger the countdown. If the saved
            // Bluetooth source is no longer available, retain the logical step
            // and fall back to camera input.
            if (inputSource == InputSource.BLUETOOTH &&
                !scanner.isReadyToStartSession &&
                !scanner.connectionState.isConnectionPending
            ) {
                setInputSource(InputSource.CAMERA)
            }
            val reduction = ScanReduction(
                state = state,
                effects = listOf(ScanEffect.ExpectFormat(state.expectedFormat)),
            )
            lastEffects = reduction.effects
            applyEffects(reduction.effects)
            onStateChanged?.invoke(state)
            onEffects?.invoke(reduction.effects)
            return reduction
        }

        if (scanner.isReadyToStartSession && !cameraWasSelectedByUser) {
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
                bluetoothFallbackBlocksPromotion = false
                setInputSource(InputSource.CAMERA)
                applyExpectedFormat(null)
                true
            }

            InputSource.BLUETOOTH -> {
                cameraWasSelectedByUser = false
                if (!scanner.isReadyToStartSession) {
                    setInputSource(InputSource.CAMERA)
                    applyExpectedFormat(null)
                    false
                } else {
                    bluetoothFallbackBlocksPromotion = false
                    setInputSource(InputSource.BLUETOOTH)
                    applyExpectedFormat()
                    true
                }
            }
        }
    }

    /**
     * Explicit reconnect entry point for the scan screen.
     *
     * A transport can remain connected while its settings restoration has
     * failed. Treat that as a stale link and ask the adapter for a fresh
     * handshake rather than reporting the already-connected link as a retry
     * success. The adapter still owns every protocol operation.
     */
    fun reconnectKnownDevice(): Boolean {
        if (scanner.isConnected &&
            (!scanner.isReadyForScanning || bluetoothFallbackBlocksPromotion)
        ) {
            scanner.disconnect()
        }
        val reconnected = scanner.reconnectKnownDevice()
        if (reconnected) {
            // Reconnect is normally asynchronous. An explicit user retry
            // releases the configuration-failure hold now so the later Ready
            // callback can promote the session back to Bluetooth.
            bluetoothFallbackBlocksPromotion = false
            if (scanner.isReadyForScanning) {
                handleConnectionState(scanner.connectionState)
            }
        }
        return reconnected
    }

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
        onScannerConfigurationStateChanged?.invoke(state)
        when (state) {
            ConfigurationState.Ready -> handleConnectionState(scanner.connectionState)
            is ConfigurationState.Failed -> fallbackToCameraIfBluetooth()
            ConfigurationState.Unavailable -> {
                if (scanner.connectionState.connectedDevice == null &&
                    !scanner.connectionState.isConnectionPending
                ) {
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
                !bluetoothFallbackBlocksPromotion &&
                !isBackgrounded
            ) {
                setInputSource(InputSource.BLUETOOTH)
                applyExpectedFormat()
            }
            return
        }

        // A process recreation can restore the logical BLE source before the
        // known device has finished reconnecting. Keep that source selected
        // while discovery/connection is genuinely in flight; otherwise the
        // initial Connecting + Unavailable pair would immediately switch the
        // restored session to camera and hide the recovery state from UI.
        if (connectionState.isConnectionPending) return

        fallbackToCameraIfBluetooth()
    }

    private val ConnectionState.isConnectionPending: Boolean
        get() = this is ConnectionState.Searching || this is ConnectionState.Connecting

    private fun fallbackToCameraIfBluetooth() {
        if (inputSource != InputSource.BLUETOOTH) return
        val issue = scannerIssueFor(
            scanner.connectionState,
            scanner.configurationState,
        ).takeIf { it != ScannerIssue.NONE } ?: ScannerIssue.CONNECTION_FAILED
        if (issue == ScannerIssue.CONFIGURATION_FAILED || issue == ScannerIssue.RESTORE_FAILED) {
            bluetoothFallbackBlocksPromotion = true
        }
        // setInputSource/applyExpectedFormat may synchronously clear a failed
        // configuration on real or fake adapters. Publish the typed issue
        // before that cleanup so the host cannot lose the failure category.
        onBluetoothFallbackIssue?.invoke(issue)
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
