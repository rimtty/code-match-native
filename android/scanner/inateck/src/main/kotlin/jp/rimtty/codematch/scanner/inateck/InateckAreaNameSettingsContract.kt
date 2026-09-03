package jp.rimtty.codematch.scanner.inateck

import com.google.gson.JsonParser

/** Strict contract of the published Android 2.0.0 JAR's area/name/value API. */
internal object InateckAreaNameSettingsContract {
    private val requiredKeys = setOf("area", "name", "value")

    fun parseCommand(commandJson: String): Set<SettingTriple>? = runCatching {
        val root = JsonParser.parseString(commandJson).asJsonArray
        if (root.size() == 0) return@runCatching null
        val identities = mutableSetOf<Pair<String, String>>()
        val settings = root.map { element ->
            val objectValue = element.asJsonObject
            if (objectValue.keySet() != requiredKeys) return@runCatching null
            val value = objectValue["value"].asString
            if (value != "0" && value != "1") return@runCatching null
            val area = objectValue["area"].asString.takeIf(String::isNotBlank)
                ?: return@runCatching null
            val name = objectValue["name"].asString.takeIf(String::isNotBlank)
                ?: return@runCatching null
            if (!identities.add(area to name)) return@runCatching null
            SettingTriple(
                area = area,
                name = name,
                value = value,
            )
        }.toSet()
        settings.takeIf { it.size == root.size() }
    }.getOrNull()

    fun normalizeInventory(settings: List<Map<String, String>>): Set<SettingTriple>? =
        settings.mapTo(mutableSetOf()) { item ->
            if (item.keys != requiredKeys) return null
            val value = item["value"]?.takeIf { it == "0" || it == "1" } ?: return null
            SettingTriple(
                area = item["area"]?.takeIf(String::isNotBlank) ?: return null,
                name = item["name"]?.takeIf(String::isNotBlank) ?: return null,
                value = value,
            )
        }.takeIf { normalized ->
            normalized.size == settings.size &&
                normalized.map { it.area to it.name }.toSet().size == settings.size
        }

    data class SettingTriple(val area: String, val name: String, val value: String)
}
