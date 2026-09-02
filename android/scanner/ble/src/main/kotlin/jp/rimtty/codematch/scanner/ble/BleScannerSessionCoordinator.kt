package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice

/**
 * Combined state exposed by the production-adapter boundary.
 *
 * A connected GATT link is not enough to scan: the device inventory must have
 * been read and the session restriction must have been applied successfully.
 * Keeping that distinction here prevents an adapter from publishing a
 * connected-but-unconfigured scanner to the application.
 */
data class BleScannerSessionState(
    val connection: BleConnectionState,
    val configuration: ConfigurationState,
    val symbology: BleSymbologySessionState,
    val expectedFormat: ScanFormat?,
    val isReadyForScanning: Boolean,
    val diagnostics: List<DiagnosticEvent>,
) {
    val connectedDevice: ScannerDevice?
        get() = connection.connectedDevice

    val isSessionActive: Boolean
        get() = symbology != BleSymbologySessionState.Disconnected &&
            symbology != BleSymbologySessionState.Ready &&
            symbology !is BleSymbologySessionState.Failed
}

/**
 * Wires the connection coordinator and the symbology/session owner together.
 *
 * This is deliberately a pure Kotlin orchestration layer. The future Android
 * adapter still owns Bluetooth callbacks, service discovery, permissions,
 * UUIDs, and byte framing; it only needs to implement [BleTransport] and feed
 * events into [BleConnectionCoordinator].
 */
class BleScannerSessionCoordinator(
    private val connectionCoordinator: BleConnectionCoordinator,
    private val symbologySession: BleSymbologySession,
) : BleScannerListener {
    private var listener: ((BleScannerSessionState) -> Unit)? = null
    private var closed = false
    private var synchronizingConfiguration = false
    private var mutableState: BleScannerSessionState = stateFromCurrentValues()

    init {
        connectionCoordinator.setListener(this)
        symbologySession.setListener { _, configuration ->
            // The connection coordinator's state is also consumed directly by
            // settings UI. Keep it conservative: Ready is published there
            // only after this session owner has reached Ready.
            if (connectionCoordinator.configurationState != configuration &&
                !synchronizingConfiguration
            ) {
                synchronizingConfiguration = true
                try {
                    connectionCoordinator.markConfiguration(configuration)
                } finally {
                    synchronizingConfiguration = false
                }
            }
            publish()
        }
        reconcileConnection(connectionCoordinator.state.connection)
        publish()
    }

    val state: BleScannerSessionState
        get() = mutableState

    val device: ScannerDevice
        get() = symbologySession.scannerDevice

    fun setListener(listener: ((BleScannerSessionState) -> Unit)?) {
        this.listener = listener
        if (!closed) listener?.invoke(mutableState)
    }

    fun startDiscovery(): Boolean {
        if (closed) return false
        return connectionCoordinator.startDiscovery()
    }

    fun stopDiscovery(): Boolean {
        if (closed) return false
        return connectionCoordinator.stopDiscovery()
    }

    /** Rejects a device that does not match this session's persisted identity. */
    fun connect(device: ScannerDevice): Boolean {
        if (closed || device.id != this.device.id) return false
        return connectionCoordinator.connect(device)
    }

    fun disconnect(): Boolean {
        if (closed) return false
        return connectionCoordinator.disconnect()
    }

    fun reconnectKnownDevice(): Boolean {
        if (closed) return false
        return connectionCoordinator.reconnectKnownDevice()
    }

    /** Start the fixed physical QR + Code 128 mode. */
    fun startSession(expectedFormat: ScanFormat): Boolean {
        if (closed || !isConnectedToBoundDevice()) return false
        return symbologySession.startSession(expectedFormat)
    }

    /** Change the logical step without issuing another physical setting write. */
    fun setExpectedFormat(expectedFormat: ScanFormat): Boolean {
        if (closed) return false
        return symbologySession.setExpectedFormat(expectedFormat)
    }

    fun endSession(): Boolean {
        if (closed) return false
        return symbologySession.endSession()
    }

    /** Forward the host lifecycle state without importing Android Lifecycle. */
    fun setApplicationActive(active: Boolean, atMillis: Long) {
        if (closed) return
        connectionCoordinator.setApplicationActive(active, atMillis)
    }

    /** A transport timeout must be acknowledged only after the link is closed. */
    fun onTransportResetCompleted() {
        if (closed) return
        symbologySession.onTransportResetCompleted()
        publish()
    }

    /** Drive both deadline owners from one adapter/application ticker. */
    fun tick(atMillis: Long): BleScannerTickResult {
        if (closed) {
            return BleScannerTickResult(
                command = BleCommandTickResult.Noop,
                connectionOperationStarted = false,
            )
        }
        val command = symbologySession.tick(atMillis)
        val connectionOperationStarted = connectionCoordinator.tick(atMillis)
        publish()
        return BleScannerTickResult(command, connectionOperationStarted)
    }

    /** Stop accepting events and detach both callbacks owned by this bridge. */
    fun close() {
        if (closed) return
        closed = true
        listener = null
        connectionCoordinator.setListener(null)
        symbologySession.setListener(null)
    }

    override fun onStateChanged(state: BleScannerState) {
        if (closed) return
        reconcileConnection(state.connection)
        publish()
    }

    override fun onScanPayload(payload: ScanPayload) {
        if (closed || !mutableState.isReadyForScanning) return
        // A coordinator for a BLE transport must never forward a callback
        // mislabeled as camera input. Dropping it is safer than recording it.
        if (payload.source != InputSource.BLUETOOTH) return
        // Do not add the value to state or diagnostics. The typed payload is
        // delivered only to the caller that explicitly subscribed.
        onPayload?.invoke(payload)
    }

    /** Separate payload callback keeps state snapshots free of scan text. */
    var onPayload: ((ScanPayload) -> Unit)? = null

    private fun reconcileConnection(connection: BleConnectionState) {
        if (closed) return
        val connected = connection.connectedDevice
        if (connected?.id == device.id) {
            when (symbologySession.state) {
                BleSymbologySessionState.Disconnected,
                BleSymbologySessionState.AwaitingReconnect,
                is BleSymbologySessionState.Failed,
                -> symbologySession.onConnected()
                else -> Unit
            }
            return
        }

        // Do not release an awaiting-timeout command until the adapter
        // explicitly acknowledges transport reset. Every other non-connected
        // transition invalidates the session's pending read/write callbacks.
        if (symbologySession.state != BleSymbologySessionState.Disconnected &&
            symbologySession.state != BleSymbologySessionState.AwaitingTransportReset
        ) {
            symbologySession.onTransportDisconnected()
        }
    }

    private fun isConnectedToBoundDevice(): Boolean =
        connectionCoordinator.connectionState.connectedDevice?.id == device.id

    private fun publish() {
        if (closed) return
        mutableState = stateFromCurrentValues()
        listener?.invoke(mutableState)
    }

    private fun stateFromCurrentValues(): BleScannerSessionState {
        val connection = connectionCoordinator.connectionState
        val configuration = symbologySession.configurationState
        val ready = connection.connectedDevice?.id == device.id &&
            symbologySession.isReadyForScanning &&
            configuration.isReady
        return BleScannerSessionState(
            connection = connection,
            configuration = configuration,
            symbology = symbologySession.state,
            expectedFormat = symbologySession.expectedFormat,
            isReadyForScanning = ready,
            diagnostics = (connectionCoordinator.state.diagnostics +
                symbologySession.diagnosticEvents)
                .sortedWith(compareBy<DiagnosticEvent> { it.timestampMillis }.thenBy { it.sequence })
                .takeLast(MAX_DIAGNOSTICS),
        )
    }

    data class BleScannerTickResult(
        val command: BleCommandTickResult,
        val connectionOperationStarted: Boolean,
    )

    private companion object {
        const val MAX_DIAGNOSTICS = 20
    }
}
