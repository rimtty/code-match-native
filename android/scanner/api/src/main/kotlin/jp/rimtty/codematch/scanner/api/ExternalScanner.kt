package jp.rimtty.codematch.scanner.api

/** Listener used by UI/application coordinators without imposing coroutines. */
interface ExternalScannerListener {
    fun onConnectionStateChanged(state: ConnectionState) {}
    fun onConfigurationStateChanged(state: ConfigurationState) {}
    fun onScanPayload(payload: ScanPayload) {}
}

typealias ScannerListener = ExternalScannerListener

/**
 * Platform-neutral contract for camera/Bluetooth scanner adapters.
 *
 * Production camera and BLE implementations can be asynchronous, while the
 * Fake implementation is synchronous and deterministic. No Android camera or
 * Bluetooth type crosses this boundary.
 */
interface ExternalScanner {
    val devices: List<ScannerDevice>
    val connectionState: ConnectionState
    val configurationState: ConfigurationState
    val diagnosticEvents: List<DiagnosticEvent>
    val connectedDevice: ScannerDevice?
        get() = connectionState.connectedDevice
    val isConnected: Boolean
        get() = connectionState.isConnected
    val isReadyForScanning: Boolean
        get() = isConnected && configurationState.isReady
    val expectedFormat: ScanFormat?

    var listener: ExternalScannerListener?

    fun startDiscovery(): Boolean
    fun stopDiscovery(): Boolean
    fun connect(device: ScannerDevice): Boolean
    fun disconnect(): Boolean
    fun reconnectKnownDevice(): Boolean
    fun setExpectedFormat(format: ScanFormat?): Boolean

    // Naming aliases keep the contract easy to bridge from the Swift service
    // and from adapters that call discovery a scan.
    fun discover(): Boolean = startDiscovery()
    fun stopScanning(): Boolean = stopDiscovery()
    fun reconnectPreferredDevice(): Boolean = reconnectKnownDevice()
    fun setExpectedCode(format: ScanFormat?): Boolean = setExpectedFormat(format)
}
