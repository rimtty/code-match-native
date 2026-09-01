package jp.rimtty.codematch.feature.history

import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchSession
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryUiTextTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun activeAndEndedSessionsHaveAccessibleSummaries() {
        val active = MatchSession(startedAt = 0L, name = "  Shift A  ")
        val ended = MatchSession(startedAt = 0L, endedAt = 1_000L, entries = emptyList())

        val activeSummary = HistoryUiText.sessionAccessibilitySummary(active, AppLanguage.JAPANESE, utc)
        val endedSummary = HistoryUiText.sessionAccessibilitySummary(ended, AppLanguage.ENGLISH, utc)

        assertTrue(activeSummary.contains("Shift A"))
        assertTrue(activeSummary.contains("照合中"))
        assertTrue(endedSummary.contains("Finished"))
        assertEquals("照合中のセッション", HistoryUiText.durationText(active, AppLanguage.JAPANESE))
        assertTrue(HistoryUiText.durationText(ended, AppLanguage.ENGLISH).contains("about 1 min"))
    }

    @Test
    fun labelsAndQuantitiesFollowSelectedLanguage() {
        assertEquals("照合履歴", HistoryUiText.labels(AppLanguage.JAPANESE).title)
        assertEquals("Match history", HistoryUiText.labels(AppLanguage.ENGLISH).title)
        assertEquals("12.50", HistoryUiText.quantity(12.5, AppLanguage.ENGLISH))
        assertEquals("-", HistoryUiText.quantity(null, AppLanguage.JAPANESE))
    }
}
