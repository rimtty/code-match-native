package jp.rimtty.codematch.scanner.inateck

import jp.rimtty.codematch.scanner.ble.BleTransportReadiness

/** SDK device identity kept outside the application-facing scanner API. */
internal data class InateckSdkDevice(
    val id: String,
    val name: String,
)

/**
 * Narrow seam around the official Inateck Android SDK.
 *
 * The adapter deliberately exposes neither raw command replies nor vendor
 * exceptions to UI/diagnostics. Scan bytes have a separate callback and are
 * consumed only by the payload decoder.
 */
internal interface InateckSdkGateway {
    val readiness: BleTransportReadiness

    fun startDiscovery(
        onDevice: (InateckSdkDevice) -> Unit,
        onFinished: () -> Unit,
    ): Boolean

    fun stopDiscovery(): Boolean

    fun connect(
        deviceId: String,
        onScanBytes: (ByteArray) -> Unit,
        onDisconnected: (unexpected: Boolean) -> Unit,
        completion: (Result<Unit>) -> Unit,
    ): Boolean

    fun disconnect(deviceId: String, completion: (Result<Unit>) -> Unit): Boolean

    fun readSettings(
        deviceId: String,
        completion: (Result<List<Map<String, String>>>) -> Unit,
    ): Boolean

    fun writeSettings(
        deviceId: String,
        commandJson: String,
        completion: (Result<Unit>) -> Unit,
    ): Boolean

    fun close()
}
