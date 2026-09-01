package jp.rimtty.codematch.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryModelsTest {
    @Test
    fun groupedEntriesKeepFirstSeenPartOrderAndEveryDuplicate() {
        val entries = listOf(
            MatchEntry(id = "a", code = "BCJH5281GG", matchedAt = 100L, sequence = 0L),
            MatchEntry(id = "b", code = "OTHER00001", matchedAt = 110L, sequence = 1L),
            MatchEntry(id = "c", code = "BCJH5281GG", matchedAt = 120L, sequence = 2L),
        )

        val session = MatchSession(id = "session", entries = entries, name = "  午前  ")

        assertEquals(listOf("BCJH5281GG", "OTHER00001"), session.groupedEntries.map { it.code })
        assertEquals(2, session.groupedEntries.first().boxCount)
        assertEquals(listOf("a", "c"), session.groupedEntries.first().entries.map { it.id })
        assertEquals(listOf(0L, 2L), session.groupedEntries.first().entries.map { it.sequence })
        assertEquals(2, session.matchCount("BCJH5281GG"))
        assertEquals("午前", session.displayName)
        assertTrue(session.isActive)
        assertEquals(3, session.matchedCount)
    }

    @Test
    fun blankDisplayNameAndEndedStateAreRepresented() {
        val session = MatchSession(name = " \n\t", endedAt = 300L)

        assertEquals("", session.displayName)
        assertFalse(session.isActive)
        assertEquals(EndSessionOutcome.Ended("id", 300L), EndSessionOutcome.Ended("id", 300L))
    }
}
