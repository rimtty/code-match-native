package jp.rimtty.codematch.scanner

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.DiagnosticEvent
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice

/** Release-safe placeholder until the real BLE adapter is delivered in M4. */
class UnavailableExternalScanner : ExternalScanner {
    override val devices: List<ScannerDevice> = emptyList()
    override val connectionState: ConnectionState =
        ConnectionState.Unavailable("Bluetooth scanner support is not installed")
    override val configurationState: ConfigurationState = ConfigurationState.Unavailable
    override val diagnosticEvents: List<DiagnosticEvent> = emptyList()
    override val expectedFormat: ScanFormat? = null
    override var listener: ExternalScannerListener? = null

    override fun startDiscovery(): Boolean = false
    override fun stopDiscovery(): Boolean = false
    override fun connect(device: ScannerDevice): Boolean = false
    override fun disconnect(): Boolean = false
    override fun reconnectKnownDevice(): Boolean = false
    override fun setExpectedFormat(format: ScanFormat?): Boolean = false
}
