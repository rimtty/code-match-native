package jp.rimtty.codematch.core.export

import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchSession
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryExportTextTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun reportFileNameIsPrefixDestinationAndStartTimeNeverTheSessionName() {
        val session = MatchSession(
            id = "12345678-aaaa-bbbb-cccc-dddddddddddd",
            startedAt = 0L,
            name = "  morning/09:00\\report..pdf?  ",
            destination = Destination.SAWAI,
        )
        val labels = HistoryExportTextFormatter.labels(AppLanguage.JAPANESE)
        val startJa = HistoryExportTextFormatter.dateTime(0L, AppLanguage.JAPANESE, utc)
            .replace("/", "-").replace(":", "").replace(" ", "_")

        val history = HistoryExportTextFormatter.reportFileName(session, AppLanguage.JAPANESE, utc, labels.filePrefix)
        val inspection = HistoryExportTextFormatter.reportFileName(session, AppLanguage.JAPANESE, utc, labels.inspectionFilePrefix)
        val english = HistoryExportTextFormatter.reportFileName(
            session,
            AppLanguage.ENGLISH,
            utc,
            HistoryExportTextFormatter.labels(AppLanguage.ENGLISH).filePrefix,
        )
        val noDestination = HistoryExportTextFormatter.reportFileName(
            MatchSession(startedAt = 0L, name = "morning"),
            AppLanguage.JAPANESE,
            utc,
            labels.inspectionFilePrefix,
        )

        assertEquals("照合履歴レポート_澤井製作所_$startJa.pdf", history)
        assertEquals("検品レポート_澤井製作所_$startJa.pdf", inspection)
        assertTrue(english, english.startsWith("MatchHistoryReport_Sawai_Seisakusho_"))
        assertEquals("検品レポート_$startJa.pdf", noDestination)
        listOf(history, inspection, english, noDestination).forEach { name ->
            assertFalse(name, name.contains("morning"))
            assertFalse(name, name.contains('/'))
            assertFalse(name, name.contains(':'))
            assertFalse(name, name.contains(' '))
            assertFalse(name, name.contains(".."))
        }
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
    fun destinationNamesAreLocalized() {
        val japanese = HistoryExportTextFormatter.labels(AppLanguage.JAPANESE)
        val english = HistoryExportTextFormatter.labels(AppLanguage.ENGLISH)

        assertEquals("仕向地", japanese.destination)
        assertEquals("澤井製作所", japanese.destinationName(Destination.SAWAI))
        assertEquals("モルテン", japanese.destinationName(Destination.MOLTEN))
        assertEquals("デンソー", japanese.destinationName(Destination.DENSO))
        assertEquals("Ship-to", english.destination)
        assertEquals("Sawai Seisakusho", english.destinationName(Destination.SAWAI))
        assertEquals("Molten", english.destinationName(Destination.MOLTEN))
        assertEquals("Denso", english.destinationName(Destination.DENSO))
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
