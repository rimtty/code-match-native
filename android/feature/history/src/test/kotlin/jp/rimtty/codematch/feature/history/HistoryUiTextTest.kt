package jp.rimtty.codematch.feature.history

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class HistoryUiTextTest {
    @Test
    fun activeAndEndedSessionsHaveAccessibleSummaries() {
        val active = MatchSession(startedAt = 0L, name = "  Shift A  ")
        val ended = MatchSession(startedAt = 0L, endedAt = 1_000L, entries = emptyList())

        val activeSummary = HistoryUiText.sessionAccessibilitySummary(
            session = active,
            date = "2026-09-02 00:00",
            count = "0 boxes",
            status = "Session in progress",
            separator = " | ",
        )
        val endedSummary = HistoryUiText.sessionAccessibilitySummary(
            session = ended,
            date = "2026-09-02 00:00",
            count = "0 boxes",
            status = "Finished",
            separator = " | ",
        )

        assertTrue(activeSummary.contains("Shift A"))
        assertTrue(activeSummary.contains("Session in progress"))
        assertTrue(endedSummary.contains("Finished"))
        assertEquals(null, HistoryUiText.durationMinutes(active))
        assertEquals(1L, HistoryUiText.durationMinutes(ended))
    }

    @Test
    fun quantitiesRemainAndroidFreeAndFollowSelectedLanguage() {
        assertEquals("12.50", HistoryUiText.quantity(12.5, AppLanguage.ENGLISH))
        assertEquals("-", HistoryUiText.quantity(null, AppLanguage.JAPANESE))
    }

    @Test
    fun japaneseAndEnglishHistoryResourcesHaveTheSameKeys() {
        val japanese = resourceShape(resourceFile("values"))
        val english = resourceShape(resourceFile("values-en"))

        assertEquals(japanese.keys, english.keys)
        assertEquals(japanese.keys.size, japanese.keys.toSet().size)
        assertEquals(english.keys.size, english.keys.toSet().size)
        assertEquals(japanese.pluralQuantities.keys, english.pluralQuantities.keys)
        assertTrue(japanese.pluralQuantities.values.all { "other" in it })
        assertTrue(english.pluralQuantities.values.all { setOf("one", "other") == it })
        assertEquals(japanese.formatTokens, english.formatTokens)
    }

    private fun resourceFile(directory: String): File {
        val relativePath = "src/main/res/$directory/strings.xml"
        val projectFile = File(relativePath)
        if (projectFile.isFile) return projectFile

        var root = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(root, "android/feature/history/$relativePath")
            if (candidate.isFile) return candidate
            root = root.parentFile ?: break
        }
        error("Unable to locate history resource: $relativePath")
    }

    private data class ResourceShape(
        val keys: Set<String>,
        val pluralQuantities: Map<String, Set<String>>,
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
        val pluralQuantities = entries
            .filter { (type, _) -> type == "plurals" }
            .associate { (_, element) ->
                element.getAttribute("name") to (0 until element.childNodes.length)
                    .mapNotNull { index ->
                        (element.childNodes.item(index) as? Element)
                            ?.takeIf { it.tagName == "item" }
                            ?.getAttribute("quantity")
                    }
                    .toSet()
            }
        val formatTokens = entries.associate { (type, element) ->
            "$type:${element.getAttribute("name")}" to FORMAT_TOKEN_PATTERN
                .findAll(element.textContent.orEmpty())
                .map { it.value }
                .distinct()
                .sorted()
                .toList()
        }
        return ResourceShape(keys, pluralQuantities, formatTokens)
    }

    private companion object {
        val FORMAT_TOKEN_PATTERN: Regex = Regex("%\\d+\\$[a-zA-Z]")
    }
}
