package jp.rimtty.codematch.scanner.inateck

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import jp.rimtty.codematch.scanner.ble.BleSymbologyCodec
import jp.rimtty.codematch.scanner.ble.ScannerSettingItem
import jp.rimtty.codematch.scanner.ble.SymbologySettingCommand
import jp.rimtty.codematch.scanner.ble.SymbologySnapshot

/**
 * Exact codec for the area/name/value inventory returned by Android SDK 2.0.0.
 *
 * Unlike the iOS compatibility codec, every item returned by the SDK method is
 * retained. The adapter must never silently omit a newly introduced barcode
 * setting because doing so would make exact post-session restoration
 * impossible. Required QR and Code 128 names are validated by the BLE core.
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
                item[key]?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            }
        }
        InateckAreaNameSettingsContract.normalizeInventory(maps) ?: return null
        return SymbologySnapshot(
            deviceId = deviceId,
            settings = maps.map { setting ->
                ScannerSettingItem(
                    name = setting.getValue("name"),
                    area = setting.getValue("area"),
                    value = setting.getValue("value").toInt(),
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
            require(identities.add(command.area to command.name)) {
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
