package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice

/**
 * Production-facing facade for the SDK/UUID-neutral BLE core.
 *
 * The app consumes [ExternalScanner], while [BleScannerSessionCoordinator]
 * owns the stronger connection/settings handshake. A first non-null format
 * starts the fixed QR+Code128 physical mode, a later format change is only a
 * logical step, and null restores the complete pre-session inventory.
 */
class BleExternalScanner(
    private val coordinator: BleScannerSessionCoordinator,
) : ExternalScanner {
    private var mutableListener: ExternalScannerListener? = null
    private var lastConnectionState: ConnectionState? = null
    private var lastConfigurationState: ConfigurationState? = null

    init {
        coordinator.setListener { state -> publish(state) }
        coordinator.onPayload = { payload ->
            mutableListener?.onScanPayload(payload)
        }
    }

    override val devices: List<ScannerDevice>
        get() = coordinator.devices

    override val connectionState: ConnectionState
        get() = coordinator.state.connection.asApiState()

    override val configurationState: ConfigurationState
        get() = coordinator.state.configuration

    override val diagnosticEvents: List<DiagnosticEvent>
        get() = coordinator.state.diagnostics

    override val expectedFormat: ScanFormat?
        get() = coordinator.state.expectedFormat

    /** A connected-but-baseline scanner is not ready for scan callbacks. */
    override val isReadyForScanning: Boolean
        get() = coordinator.state.isReadyForScanning

    override var listener: ExternalScannerListener?
        get() = mutableListener
        set(value) {
            mutableListener = value
            if (value != null) {
                value.onConnectionStateChanged(connectionState)
                value.onConfigurationStateChanged(configurationState)
            }
        }

    override fun startDiscovery(): Boolean = coordinator.startDiscovery()

    override fun stopDiscovery(): Boolean = coordinator.stopDiscovery()

    override fun connect(device: ScannerDevice): Boolean = coordinator.connect(device)

    override fun disconnect(): Boolean = coordinator.disconnect()

    override fun reconnectKnownDevice(): Boolean = coordinator.reconnectKnownDevice()

    /**
     * Bridge the scanner API's nullable expected format to the physical
     * session boundary. Null is a restore request, not a second BLE mode.
     */
    override fun setExpectedFormat(format: ScanFormat?): Boolean {
        if (format == null) {
            return if (coordinator.isSessionActive) {
                coordinator.endSession()
            } else {
                false
            }
        }

        return if (coordinator.isSessionActive) {
            coordinator.setExpectedFormat(format)
        } else if (
            coordinator.state.connection.connectedDevice?.id == coordinator.device.id &&
            coordinator.state.symbology == BleSymbologySessionState.Ready
        ) {
            // The facade is the boundary used by the app's scanner contract;
            // callers should not need to know about startSession separately.
            coordinator.startSession(format)
        } else {
            false
        }
    }

    /** Forward host lifecycle without importing Android lifecycle types. */
    fun setApplicationActive(active: Boolean, atMillis: Long) {
        coordinator.setApplicationActive(active, atMillis)
    }

    /** Detach all callbacks owned by the facade and its coordinator. */
    fun close() {
        coordinator.close()
        mutableListener = null
    }

    private fun publish(state: BleScannerSessionState) {
        val connection = state.connection.asApiState()
        val configuration = state.configuration
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
