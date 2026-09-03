package jp.rimtty.codematch.scanner.ble

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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
    /** Stable adapter/profile identity used to bind persistent recovery data. */
    val identity: String,
) {
    init {
        require(settingsCharacteristicUuid.isNotBlank()) {
            "settingsCharacteristicUuid must be supplied by the adapter"
        }
        require(identity.isNotBlank()) {
            "identity must be supplied by the adapter"
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

/**
 * Codec for Inateck's documented cross-platform `flag`/`value` settings API.
 *
 * The published response is `{ "status": 0, "info": [...] }`, where every
 * item contains `name`, `flag`, and `value`; writes are a bare array containing
 * only numeric `flag` and `value`. This codec deliberately does not imply that
 * the currently published Android JAR or a particular scanner implements that
 * contract. A production adapter must still opt into this codec only after the
 * target device and SDK response have been observed.
 *
 * The documented JSON is an SDK API boundary, not a documented raw GATT wire
 * format. Consequently this codec may only be paired with a future SDK-backed
 * [BleTransport] that maps its logical read/write calls to those SDK methods.
 * It must not be paired with [AndroidBleTransport] unless physical observation
 * proves that a target scanner itself accepts the same bytes.
 *
 * [ScannerSettingItem.area] has no counterpart in this protocol, so the codec
 * uses a profile-local `flag:<number>` identity. It is never encoded on write
 * and must not be treated as an iOS area mapping.
 */
object InateckDocumentedFlagValueCodec : BleSymbologyCodec {
    private const val FIRST_SYMBOLOGY_FLAG = 2001
    private const val LAST_SYMBOLOGY_FLAG = 2028
    private val reservedKeys = setOf("name", "flag", "value")

    override fun decodeSnapshot(
        deviceId: String,
        payload: ByteArray,
        capturedAtMillis: Long,
    ): SymbologySnapshot? {
        if (deviceId.isBlank()) return null
        val json = decodeUtf8(payload) ?: return null
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: return null
        if (root.intValue("status") != 0) return null
        val info = root.get("info")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return null

        val settings = ArrayList<ScannerSettingItem>()
        val seenFlags = HashSet<Int>()
        for (element in info) {
            if (!element.isJsonObject) return null
            val item = element.asJsonObject
            val name = item.stringValue("name")?.takeIf(String::isNotBlank) ?: return null
            val flag = item.intValue("flag") ?: return null
            val value = item.intValue("value") ?: return null
            if (value !in 0..1 || !seenFlags.add(flag)) return null
            if (flag !in FIRST_SYMBOLOGY_FLAG..LAST_SYMBOLOGY_FLAG) continue

            val extras = item.entrySet()
                .filter { it.key !in reservedKeys }
                .associate { it.key to it.value.toString() }
            settings += ScannerSettingItem(
                name = name,
                area = "flag:$flag",
                value = value,
                flag = flag,
                extraFields = extras,
            )
        }
        if (settings.isEmpty()) return null
        return SymbologySnapshot(deviceId, settings, capturedAtMillis)
    }

    override fun encodeCommands(commands: List<SymbologySettingCommand>): ByteArray {
        require(commands.isNotEmpty()) { "settings commands must not be empty" }
        val seenFlags = HashSet<Int>()
        val array = JsonArray()
        commands.forEach { command ->
            val flag = requireNotNull(command.flag) {
                "documented flag/value commands require a device-reported flag"
            }
            require(flag in FIRST_SYMBOLOGY_FLAG..LAST_SYMBOLOGY_FLAG) {
                "documented flag/value commands require a symbology flag"
            }
            require(command.value in 0..1) { "symbology value must be 0 or 1" }
            require(seenFlags.add(flag)) { "symbology flags must be unique" }
            array.add(JsonObject().apply {
                addProperty("flag", flag)
                addProperty("value", command.value)
            })
        }
        return Gson().toJson(array).toByteArray(StandardCharsets.UTF_8)
    }

    private fun decodeUtf8(payload: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
    }.getOrNull()

    private fun JsonObject.stringValue(name: String): String? = get(name)?.let {
        if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else null
    }

    private fun JsonObject.intValue(name: String): Int? = get(name)?.let {
        runCatching {
            if (!it.isJsonPrimitive) return@runCatching null
            when {
                it.asJsonPrimitive.isNumber -> it.asString.toIntOrNull()
                it.asJsonPrimitive.isString -> it.asString.toIntOrNull()
                else -> null
            }
        }.getOrNull()
    }
}
