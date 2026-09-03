package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice

/** Creates the settings/session owner after a user selects a discovered device. */
fun interface BleSessionCoordinatorFactory {
    fun create(device: ScannerDevice): BleScannerSessionCoordinator
}

/**
 * Production-facing BLE facade for a scanner whose identity is learned by discovery.
 *
 * [BleExternalScanner] remains useful when the adapter already knows a fixed device.
 * A real Android flow cannot construct that fixed stack before discovery, so this
 * facade binds a fresh [BleScannerSessionCoordinator] to the selected device before
 * starting the physical connection. The same factory path is used when a persisted
 * known device is reconnected after process recreation.
 *
 * Only one device/session owner can be active. Selecting another device is rejected
 * until the previous link is disconnected and its settings session is no longer
 * active, which prevents a pending restore from being redirected to another scanner.
 */
class SelectableBleExternalScanner(
    private val connectionCoordinator: BleConnectionCoordinator,
    private val sessionFactory: BleSessionCoordinatorFactory,
) : ExternalScanner, BleScannerListener {
    private var sessionCoordinator: BleScannerSessionCoordinator? = null
    private var mutableListener: ExternalScannerListener? = null
    private var lastConnectionState: ConnectionState? = null
    private var lastConfigurationState: ConfigurationState? = null
    private var closed = false

    init {
        connectionCoordinator.setListener(this)
    }

    override val devices: List<ScannerDevice>
        get() = connectionCoordinator.devices

    override val connectionState: ConnectionState
        get() = sessionCoordinator?.state?.connection?.asApiState()
            ?: connectionCoordinator.connectionState.asApiState()

    override val configurationState: ConfigurationState
        get() = sessionCoordinator?.state?.configuration
            ?: connectionCoordinator.configurationState

    override val diagnosticEvents: List<DiagnosticEvent>
        get() = sessionCoordinator?.state?.diagnostics
            ?: connectionCoordinator.state.diagnostics

    override val expectedFormat: ScanFormat?
        get() = sessionCoordinator?.state?.expectedFormat

    override val isReadyForScanning: Boolean
        get() = sessionCoordinator?.state?.isReadyForScanning == true

    /** Device currently owning the settings/recovery state machine. */
    val boundDevice: ScannerDevice?
        get() = sessionCoordinator?.device

    override var listener: ExternalScannerListener?
        get() = mutableListener
        set(value) {
            mutableListener = value
            if (value != null && !closed) {
                value.onConnectionStateChanged(connectionState)
                value.onConfigurationStateChanged(configurationState)
            }
        }

    override fun startDiscovery(): Boolean {
        if (closed) return false
        return sessionCoordinator?.startDiscovery() ?: connectionCoordinator.startDiscovery()
    }

    override fun stopDiscovery(): Boolean {
        if (closed) return false
        return sessionCoordinator?.stopDiscovery() ?: connectionCoordinator.stopDiscovery()
    }

    override fun connect(device: ScannerDevice): Boolean {
        if (closed || !bind(device)) return false
        return requireNotNull(sessionCoordinator).connect(device)
    }

    override fun disconnect(): Boolean {
        if (closed) return false
        return sessionCoordinator?.disconnect() ?: connectionCoordinator.disconnect()
    }

    override fun reconnectKnownDevice(): Boolean {
        if (closed) return false
        val device = connectionCoordinator.knownDevice
            ?: connectionCoordinator.loadKnownDevice()
            ?: return false
        if (!bind(device)) return false
        return requireNotNull(sessionCoordinator).reconnectKnownDevice()
    }

    override fun setExpectedFormat(format: ScanFormat?): Boolean {
        if (closed) return false
        val coordinator = sessionCoordinator ?: return false
        if (format == null) {
            return if (coordinator.isSessionActive) coordinator.endSession() else false
        }
        return if (coordinator.isSessionActive) {
            coordinator.setExpectedFormat(format)
        } else if (
            coordinator.state.connection.connectedDevice?.id == coordinator.device.id &&
            coordinator.state.symbology == BleSymbologySessionState.Ready
        ) {
            coordinator.startSession(format)
        } else {
            false
        }
    }

    /** Forward host lifecycle while retaining the selected device identity. */
    fun setApplicationActive(active: Boolean, atMillis: Long) {
        if (closed) return
        sessionCoordinator?.setApplicationActive(active, atMillis)
            ?: connectionCoordinator.setApplicationActive(active, atMillis)
    }

    /** Advance command/reconnect deadlines from the Android host scheduler. */
    fun tick(atMillis: Long): BleScannerSessionCoordinator.BleScannerTickResult? {
        if (closed) return null
        return sessionCoordinator?.tick(atMillis) ?: run {
            connectionCoordinator.tick(atMillis)
            null
        }
    }

    /** A timed-out settings command can resume only after physical link reset. */
    fun onTransportResetCompleted() {
        if (!closed) sessionCoordinator?.onTransportResetCompleted()
    }

    fun close() {
        if (closed) return
        closed = true
        mutableListener = null
        sessionCoordinator?.close()
        sessionCoordinator = null
        connectionCoordinator.setListener(null)
    }

    override fun onStateChanged(state: BleScannerState) {
        if (!closed) publish(state.connection.asApiState(), state.configuration)
    }

    override fun onScanPayload(payload: ScanPayload) {
        // An unbound connection coordinator is never ready, but retain the
        // privacy-safe typed boundary if a custom transport emits early data.
        if (!closed && isReadyForScanning) mutableListener?.onScanPayload(payload)
    }

    private fun bind(device: ScannerDevice): Boolean {
        val current = sessionCoordinator
        if (current?.device?.id == device.id) return true
        if (current != null && !canReplace(current)) return false

        current?.close()
        sessionCoordinator = null
        connectionCoordinator.setListener(this)
        val created = runCatching { sessionFactory.create(device) }.getOrNull() ?: return false
        if (created.device.id != device.id) {
            created.close()
            connectionCoordinator.setListener(this)
            return false
        }
        created.onPayload = { payload ->
            if (!closed) mutableListener?.onScanPayload(payload)
        }
        created.setListener { state ->
            if (!closed) {
                publish(state.connection.asApiState(), state.configuration)
            }
        }
        sessionCoordinator = created
        publish(created.state.connection.asApiState(), created.state.configuration)
        return true
    }

    private fun canReplace(current: BleScannerSessionCoordinator): Boolean =
        !current.isSessionActive &&
            connectionCoordinator.connectionState !is BleConnectionState.Connected &&
            connectionCoordinator.connectionState !is BleConnectionState.Connecting &&
            connectionCoordinator.connectionState !is BleConnectionState.Reconnecting

    private fun publish(connection: ConnectionState, configuration: ConfigurationState) {
        val currentListener = mutableListener
        if (connection != lastConnectionState) {
            lastConnectionState = connection
            currentListener?.onConnectionStateChanged(connection)
        }
        if (configuration != lastConfigurationState) {
            lastConfigurationState = configuration
            currentListener?.onConfigurationStateChanged(configuration)
        }
    }
}
