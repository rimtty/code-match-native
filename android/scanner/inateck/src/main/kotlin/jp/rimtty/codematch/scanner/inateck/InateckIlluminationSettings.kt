package jp.rimtty.codematch.scanner.inateck

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** General-setting commands for the official Android SDK's inventory API. */
internal object InateckIlluminationSettings {
    private const val NAME = "lighting_lamp_control"

    data class Setting(val area: String, val value: Int)

    // Preserve the device-reported area. The sample SDK's area is not a
    // universal mapping from the standalone flag/value command API.
    fun read(inventory: List<Map<String, String>>): Setting? {
        val entries = inventory.filter { it["name"] == NAME }
        val entry = entries.singleOrNull() ?: return null
        if (entry.keys != setOf("area", "name", "value")) return null
        val area = entry["area"]?.takeIf { it.isNotBlank() } ?: return null
        val value = when (entry["value"]) {
            "0" -> 0
            "1" -> 1
            "2" -> 2
            else -> return null
        }
        return Setting(area, value)
    }

    /** ON illuminates during reading; OFF keeps the lamp off. */
    fun command(inventory: List<Map<String, String>>, enabled: Boolean): String? {
        val current = read(inventory) ?: return null
        return JsonArray().apply {
            add(JsonObject().apply {
                addProperty("area", current.area)
                addProperty("name", NAME)
                addProperty("value", if (enabled) "0" else "2")
            })
        }.toString()
    }

    fun confirmed(inventory: List<Map<String, String>>, area: String, enabled: Boolean): Boolean =
        read(inventory) == Setting(area, if (enabled) 0 else 2)
}
