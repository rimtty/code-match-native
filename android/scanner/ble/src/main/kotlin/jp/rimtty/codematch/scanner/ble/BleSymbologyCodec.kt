package jp.rimtty.codematch.scanner.ble

import java.nio.charset.StandardCharsets

/**
 * Adapter-owned codec for a scanner's settings characteristic.
 *
 * [BleTransport] intentionally deals in raw bytes because an Android
 * BluetoothGatt implementation, a vendor SDK, and the observed iOS protocol
 * may all use different wire representations. The BLE core only consumes a
 * complete [SymbologySnapshot] and emits setting commands; it does not
 * assume that a byte payload is JSON, UTF-8, or any particular flag range.
 */
interface BleSymbologyCodec {
    /** Decode one complete device inventory returned by the settings read. */
    fun decodeSnapshot(
        deviceId: String,
        payload: ByteArray,
        capturedAtMillis: Long,
    ): SymbologySnapshot?

    /** Encode the complete command inventory for the settings write. */
    fun encodeCommands(commands: List<SymbologySettingCommand>): ByteArray
}

/**
 * Endpoint and wire codec selected by the platform/scanner adapter.
 *
 * The endpoint UUID is discovered by the adapter. [codec] is also selected by
 * the adapter when the scanner does not use the observed iOS representation.
 */
data class BleSymbologyProfile(
    val settingsCharacteristicUuid: String,
    /** The adapter must explicitly select the wire codec for this scanner. */
    val codec: BleSymbologyCodec,
) {
    init {
        require(settingsCharacteristicUuid.isNotBlank()) {
            "settingsCharacteristicUuid must be supplied by the adapter"
        }
    }
}

/**
 * Codec for the response/command representation observed in the iOS client.
 *
 * This is an explicitly selected compatibility codec for tests and the canonical iOS-shaped
 * JSON (`data`/`info` entries with `area`, `name`, and `value`). Android
 * adapters must inject their own codec when their scanner uses another byte
 * or flag representation.
 */
object IosObservedSymbologyCodec : BleSymbologyCodec {
    override fun decodeSnapshot(
        deviceId: String,
        payload: ByteArray,
        capturedAtMillis: Long,
    ): SymbologySnapshot? =
        SymbologySettings.parse(
            deviceId = deviceId,
            settingsJson = payload.toString(StandardCharsets.UTF_8),
            capturedAtMillis = capturedAtMillis,
        )

    override fun encodeCommands(commands: List<SymbologySettingCommand>): ByteArray =
        SymbologySettings.encodeCommands(commands).toByteArray(StandardCharsets.UTF_8)
}
