package jp.rimtty.codematch.history

import jp.rimtty.codematch.feature.history.HistoryLayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryNavigationSelectionTest {
    @Test
    fun compactBackPopsBoxThenGroupThenSession() {
        val selection = HistoryNavigationSelection(
            sessionId = "session",
            groupCode = "part",
            entryId = "box",
        )

        val afterBox = selection.pop()
        val afterGroup = afterBox.pop()
        val afterSession = afterGroup.pop()

        assertEquals(
            HistoryNavigationSelection("session", "part", null),
            afterBox,
        )
        assertEquals(
            HistoryNavigationSelection("session", null, null),
            afterGroup,
        )
        assertEquals(HistoryNavigationSelection(), afterSession)
    }

    @Test
    fun expandedBackOnlyConsumesNestedDetailsBecauseListRemainsVisible() {
        assertFalse(
            HistoryNavigationSelection(sessionId = "session")
                .canNavigateBack(HistoryLayoutMode.EXPANDED),
        )
        assertTrue(
            HistoryNavigationSelection(sessionId = "session", groupCode = "part")
                .canNavigateBack(HistoryLayoutMode.EXPANDED),
        )
    }

    @Test
    fun compactSessionOverviewConsumesBackToTheList() {
        assertTrue(
            HistoryNavigationSelection(sessionId = "session")
                .canNavigateBack(HistoryLayoutMode.COMPACT),
        )
    }
}
