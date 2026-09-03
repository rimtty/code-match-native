package jp.rimtty.codematch.scanner.inateck

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import jp.rimtty.codematch.scanner.ble.BleSymbologyCodec
import jp.rimtty.codematch.scanner.ble.ScannerSettingItem
import jp.rimtty.codematch.scanner.ble.SymbologySettingCommand
import jp.rimtty.codematch.scanner.ble.SymbologySnapshot

/**
 * Codec for the area/name/value inventory returned by Android SDK 2.0.0.
 *
 * The SDK returns general settings and barcode toggles in one list. The
 * contract validates the complete list, then this codec keeps only binary
 * symbology entries for the BLE session. General settings are never emitted
 * in a command, so values such as `volume=4` remain untouched on the device.
 */
internal object InateckAreaNameSymbologyCodec : BleSymbologyCodec {
    override fun decodeSnapshot(
        deviceId: String,
        payload: ByteArray,
        capturedAtMillis: Long,
    ): SymbologySnapshot? {
        if (deviceId.isBlank()) return null
        val text = strictUtf8(payload) ?: return null
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return null
        if (root.keySet() != setOf("data")) return null
        val data = root["data"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (data.size() == 0) return null
        val maps = data.map { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            if (item.keySet() != REQUIRED_KEYS) return null
            REQUIRED_KEYS.associateWith { key ->
                item[key]?.let { value ->
                    if (!value.isJsonPrimitive) return@let null
                    when {
                        value.asJsonPrimitive.isString -> value.asString
                        value.asJsonPrimitive.isNumber -> value.asNumber.toString()
                        else -> null
                    }
                } ?: return null
            }
        }
        val symbologies = InateckAreaNameSettingsContract.extractSymbologies(maps)
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return SymbologySnapshot(
            deviceId = deviceId,
            settings = symbologies.map { setting ->
                ScannerSettingItem(
                    name = setting.name,
                    area = setting.area,
                    value = setting.value.toInt(),
                )
            },
            capturedAtMillis = capturedAtMillis,
        )
    }

    override fun encodeCommands(commands: List<SymbologySettingCommand>): ByteArray {
        require(commands.isNotEmpty()) { "settings command must not be empty" }
        val identities = mutableSetOf<Pair<String, String>>()
        val array = JsonArray()
        commands.forEach { command ->
            require(command.value == 0 || command.value == 1) { "invalid setting value" }
            require(command.area.isNotBlank() && command.name.isNotBlank()) {
                "setting identity must not be blank"
            }
            require(InateckAreaNameSettingsContract.isSymbologyCommandName(command.name)) {
                "general settings must not be written by the symbology adapter"
            }
            require(identities.add(command.area to command.name.lowercase(Locale.ROOT))) {
                "duplicate setting identity"
            }
            array.add(JsonObject().apply {
                addProperty("area", command.area)
                addProperty("name", command.name)
                addProperty("value", command.value.toString())
            })
        }
        return Gson().toJson(array).toByteArray(StandardCharsets.UTF_8)
    }

    private fun strictUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private val REQUIRED_KEYS = setOf("area", "name", "value")
}
