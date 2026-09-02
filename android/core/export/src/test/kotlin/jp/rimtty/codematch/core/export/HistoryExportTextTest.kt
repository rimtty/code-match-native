package jp.rimtty.codematch.core.export

import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchSession
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryExportTextTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun fileNameKeepsDisplayNameButRemovesPathAndReservedCharacters() {
        val session = MatchSession(
            id = "12345678-aaaa-bbbb-cccc-dddddddddddd",
            startedAt = 0L,
            name = "  morning/09:00\\report..pdf?  ",
        )

        val fileName = HistoryExportTextFormatter.fileName(session, AppLanguage.JAPANESE, utc)

        assertEquals("照合履歴_morning-0900-report_pdf.pdf", fileName)
        assertFalse(fileName.contains('/'))
        assertFalse(fileName.contains('\\'))
        assertFalse(fileName.contains(".."))
    }

    @Test
    fun unnamedSessionUsesLocalizedStartDateAsSafeFileNamePart() {
        val session = MatchSession(startedAt = 0L)

        val japanese = HistoryExportTextFormatter.fileName(session, AppLanguage.JAPANESE, utc)
        val english = HistoryExportTextFormatter.fileName(session, AppLanguage.ENGLISH, utc)

        assertTrue(japanese.startsWith("照合履歴_"))
        assertTrue(english.startsWith("MatchHistory_"))
        assertTrue(japanese.endsWith(".pdf"))
        assertTrue(english.endsWith(".pdf"))
        assertFalse(japanese.contains('/'))
        assertFalse(english.contains('/'))
    }

    @Test
    fun quantityAndTimeAreLocalizedAndNullQuantityIsDash() {
        assertEquals("12", HistoryExportTextFormatter.quantity(12.0, AppLanguage.JAPANESE))
        assertEquals("12.50", HistoryExportTextFormatter.quantity(12.5, AppLanguage.ENGLISH))
        assertEquals("-", HistoryExportTextFormatter.quantity(null, AppLanguage.JAPANESE))
        assertTrue(HistoryExportTextFormatter.time(0L, AppLanguage.ENGLISH, utc).isNotBlank())
        assertTrue(HistoryExportTextFormatter.dateTime(0L, AppLanguage.JAPANESE, utc).isNotBlank())
    }

    @Test
    fun boxCountUsesNaturalEnglishSingularAndPlural() {
        assertEquals("0 boxes", HistoryExportTextFormatter.boxCount(0, AppLanguage.ENGLISH))
        assertEquals("1 box", HistoryExportTextFormatter.boxCount(1, AppLanguage.ENGLISH))
        assertEquals("2 boxes", HistoryExportTextFormatter.boxCount(2, AppLanguage.ENGLISH))
        assertEquals("1箱", HistoryExportTextFormatter.boxCount(1, AppLanguage.JAPANESE))
        assertEquals("2箱", HistoryExportTextFormatter.boxCount(2, AppLanguage.JAPANESE))
    }
}
