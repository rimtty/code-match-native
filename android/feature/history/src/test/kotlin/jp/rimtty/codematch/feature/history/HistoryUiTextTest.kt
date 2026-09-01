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
        val japanese = resourceKeys(resourceFile("values"))
        val english = resourceKeys(resourceFile("values-en"))

        assertEquals(japanese.toSet(), english.toSet())
        assertEquals(japanese.size, japanese.toSet().size)
        assertEquals(english.size, english.toSet().size)
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

    private fun resourceKeys(file: File): List<String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        return listOf("string", "plurals").flatMap { resourceType ->
            val nodes = document.getElementsByTagName(resourceType)
            (0 until nodes.length).map { index ->
                (nodes.item(index) as Element).getAttribute("name")
            }
        }
    }
}
