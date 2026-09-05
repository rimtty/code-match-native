package jp.rimtty.codematch.scanner.inateck

import com.google.gson.JsonParser
import java.util.Locale

/**
 * Contract of the published Android 2.0.0 JAR's area/name/value API.
 *
 * `getSettingInfo()` returns both barcode toggles and general settings such as
 * volume. General settings are deliberately not represented by
 * [SettingTriple], because the symbology session must never write them back.
 * The area/name API has no independent type marker for a future barcode. The
 * adapter therefore uses only the known vendor symbology names; unrecognized
 * entries are treated like general settings and are never written.
 */
internal object InateckAreaNameSettingsContract {
    private val requiredKeys = setOf("area", "name", "value")

    fun parseCommand(commandJson: String): Set<SettingTriple>? = runCatching {
        val root = JsonParser.parseString(commandJson).asJsonArray
        if (root.size() == 0) return@runCatching null
        val identities = mutableSetOf<SettingIdentity>()
        val settings = root.map { element ->
            val objectValue = element.asJsonObject
            if (objectValue.keySet() != requiredKeys) return@runCatching null
            val area = jsonString(objectValue["area"])?.takeIf(String::isNotBlank)
                ?: return@runCatching null
            val name = jsonString(objectValue["name"])?.takeIf(String::isNotBlank)
                ?: return@runCatching null
            if (!isSymbologyName(name)) return@runCatching null
            val value = binaryValue(jsonString(objectValue["value"]))
                ?: return@runCatching null
            if (!identities.add(identity(area, name))) return@runCatching null
            SettingTriple(area = area, name = name, value = value)
        }.toSet()
        settings.takeIf { it.size == root.size() }
    }.getOrNull()

    /**
     * Returns only binary symbology entries from a complete SDK inventory.
     *
     * Every SDK entry must still have exactly the documented three keys and a
     * non-blank area/name/value. This prevents a malformed or duplicated
     * general entry from making the inventory ambiguous. Only entries
     * identified as symbology must have a value of `0` or `1`; values such as
     * `volume=4` are accepted and omitted from the returned set.
     */
    fun extractSymbologies(settings: List<Map<String, String>>): List<SettingTriple>? {
        val identities = mutableSetOf<SettingIdentity>()
        val symbologies = mutableListOf<SettingTriple>()
        for (item in settings) {
            if (item.keys != requiredKeys) return null
            val area = item["area"]?.takeIf(String::isNotBlank) ?: return null
            val name = item["name"]?.takeIf(String::isNotBlank) ?: return null
            val value = item["value"]?.takeIf(String::isNotBlank) ?: return null
            if (!identities.add(identity(area, name))) return null
            if (!isSymbologyName(name)) continue
            if (binaryValue(value) == null) return null
            symbologies += SettingTriple(area = area, name = name, value = value)
        }
        return symbologies
    }

    /**
     * Compatibility view used by callers that compare unordered inventories.
     * General settings are intentionally absent from this result.
     */
    fun normalizeInventory(settings: List<Map<String, String>>): Set<SettingTriple>? =
        extractSymbologies(settings)?.toSet()

    /**
     * Checks that every requested symbology is present in a fresh full
     * inventory with the requested value and no additional known symbologies.
     * A changed inventory cannot validate an older full snapshot.
     * Other general settings are ignored;
     * they are not part of a symbology command and may be returned by the SDK
     * before or after a write.
     */
    fun containsRequestedSymbologies(
        settings: List<Map<String, String>>,
        requested: Set<SettingTriple>,
    ): Boolean {
        if (requested.isEmpty()) return false
        val actual = extractSymbologies(settings) ?: return false
        val actualByIdentity = actual.associateBy { identity(it.area, it.name) }
        if (actualByIdentity.size != requested.size) return false
        return requested.all { expected ->
            actualByIdentity[identity(expected.area, expected.name)]?.value == expected.value
        }
    }

    /** Returns whether an outbound area/name command is a barcode toggle. */
    internal fun isSymbologyCommandName(name: String): Boolean = isSymbologyName(name)

    private fun isSymbologyName(name: String): Boolean =
        name.lowercase(Locale.ROOT) in KNOWN_SYMBOLOGY_NAMES

    private fun binaryValue(value: String?): String? = value?.takeIf { it == "0" || it == "1" }

    private fun jsonString(element: com.google.gson.JsonElement?): String? = element?.let {
        if (!it.isJsonPrimitive) return@let null
        when {
            it.asJsonPrimitive.isString -> it.asString
            it.asJsonPrimitive.isNumber -> it.asNumber.toString()
            else -> null
        }
    }

    data class SettingTriple(val area: String, val name: String, val value: String)

    private data class SettingIdentity(val area: String, val name: String)

    private fun identity(area: String, name: String): SettingIdentity =
        SettingIdentity(area, name.lowercase(Locale.ROOT))

    /** Names used by the scanner SDK/iOS integration that do not all end in `_on`. */
    private val KNOWN_SYMBOLOGY_NAMES = setOf(
        "codabar_on", "iata25_on", "interleaved25_on", "matrix25_on", "standard25_on",
        "code39_on", "code93_on", "code128_on", "ean_8_on", "ean_13_on", "upc_a_on",
        "upc_e0_on", "msi_on", "code11_on", "chinese_post_on", "upc_e1_on",
        "aztec_on", "maxicode_on", "hanxin_on", "datamatrix_on", "qrcode_on",
        "pdf417_on", "gs1_128", "rss14_composite_on", "rss_14_composite_on", "plessey_on",
        "telepen_on", "rss_14_on", "rss_expanded_on", "rss_limited_on", "symb_128_on",
        "usps_on", "usps_fedex",
    )
}
