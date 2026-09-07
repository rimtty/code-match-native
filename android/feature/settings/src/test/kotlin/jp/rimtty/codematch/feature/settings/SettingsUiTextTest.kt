package jp.rimtty.codematch.feature.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * JVM parity gate for the settings copy, mirroring `HistoryUiTextTest`.
 *
 * Android lint's `MissingTranslation` already fails a build with a missing
 * key, but it runs only on the app's lint task and says nothing about format
 * tokens. A `%1$d` present in one language and absent in the other would
 * otherwise crash only when that language is selected at runtime.
 */
class SettingsUiTextTest {
    @Test
    fun japaneseAndEnglishSettingsResourcesHaveTheSameKeys() {
        val japanese = resourceShape(resourceFile("values"))
        val english = resourceShape(resourceFile("values-en"))

        assertEquals(japanese.keys, english.keys)
        assertEquals(japanese.keys.size, japanese.keys.toSet().size)
        assertEquals(english.keys.size, english.keys.toSet().size)
        assertEquals(japanese.formatTokens, english.formatTokens)
    }

    @Test
    fun theScanLogCardHasCopyForEveryControlItRenders() {
        val japanese = resourceShape(resourceFile("values")).keys

        listOf(
            "settings_scan_log_title",
            "settings_scan_log_note",
            "settings_scan_log_count",
            "settings_scan_log_share",
            "settings_scan_log_save",
            "settings_scan_log_clear",
            "settings_scan_log_clear_confirm_title",
            "settings_scan_log_clear_confirm_message",
            "settings_scan_log_clear_confirm",
            "settings_scan_log_clear_cancel",
            "settings_scan_log_subject",
            "settings_scan_log_saved",
            "settings_scan_log_save_failed",
            "settings_scan_log_share_failed",
        ).forEach { key ->
            assertTrue(key, "string:$key" in japanese)
        }
    }

    private fun resourceFile(directory: String): File {
        val relativePath = "src/main/res/$directory/strings.xml"
        val projectFile = File(relativePath)
        if (projectFile.isFile) return projectFile

        var root = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(root, "android/feature/settings/$relativePath")
            if (candidate.isFile) return candidate
            root = root.parentFile ?: break
        }
        error("Unable to locate settings resource: $relativePath")
    }

    private data class ResourceShape(
        val keys: Set<String>,
        val formatTokens: Map<String, List<String>>,
    )

    private fun resourceShape(file: File): ResourceShape {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val entries = listOf("string", "plurals").flatMap { resourceType ->
            val nodes = document.getElementsByTagName(resourceType)
            (0 until nodes.length).map { index ->
                resourceType to (nodes.item(index) as Element)
            }
        }
        val keys = entries.map { (type, element) -> "$type:${element.getAttribute("name")}" }.toSet()
        val formatTokens = entries.associate { (type, element) ->
            "$type:${element.getAttribute("name")}" to FORMAT_TOKEN_PATTERN
                .findAll(element.textContent.orEmpty())
                .map { it.value }
                .distinct()
                .sorted()
                .toList()
        }
        return ResourceShape(keys, formatTokens)
    }

    private companion object {
        val FORMAT_TOKEN_PATTERN: Regex = Regex("%\\d+\\$[a-zA-Z]")
    }
}
