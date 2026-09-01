package jp.rimtty.codematch.feature.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateIsDisplayedWithHistorySemantics() {
        val emptyTitle = targetString(R.string.history_empty_title)
        composeRule.setContent { HistoryScreen(emptyList()) }

        composeRule.onNodeWithTag(HistoryTestTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText(emptyTitle).assertIsDisplayed()
    }

    @Test
    fun listIsNewestFirstAndSelectionDeleteUseIds() {
        val selected = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val sessions = listOf(
            MatchSession(id = "old", startedAt = 1L, name = "Old"),
            MatchSession(id = "new", startedAt = 2L, name = "New"),
        )
        composeRule.setContent {
            HistoryScreen(
                sessions = sessions,
                onSessionSelected = selected::add,
                onDeleteSession = deleted::add,
            )
        }

        composeRule.onNodeWithText("New").assertIsDisplayed().performClick()
        composeRule.onAllNodesWithTag("${HistoryTestTags.SESSION_ROW}.delete")
            .get(0)
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf("new"), selected)
        assertEquals(listOf("new"), deleted)
    }

    @Test
    fun entryDetailDisplaysParsedAndLegacyPayloadValues() {
        val qrParsed = targetString(R.string.history_qr_parsed)
        val entry = MatchEntry(
            code = "BCJH-52-81GG",
            qrPayload = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*",
            barcodePayload = "BCJH-52-81GG@1N5X0C",
        )
        composeRule.setContent {
            HistoryEntryDetail(entry = entry)
        }

        composeRule.onNodeWithTag(HistoryTestTags.ENTRY_DETAIL).assertIsDisplayed()
        composeRule.onNodeWithText(qrParsed).assertIsDisplayed()
        composeRule.onNodeWithText("DCLP675300").assertIsDisplayed()
        composeRule.onNodeWithText("1N5X0C").performScrollTo().assertIsDisplayed()
    }

    private fun targetString(resourceId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resourceId)
}
