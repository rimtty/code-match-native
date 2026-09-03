package jp.rimtty.codematch.feature.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.rimtty.codematch.core.model.AppLanguage
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
        composeRule.setContent {
            HistoryScreen(emptyList(), language = AppLanguage.ENGLISH)
        }

        composeRule.onNodeWithTag(HistoryTestTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText("No history yet").assertIsDisplayed()
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
        val entry = MatchEntry(
            code = "BCJH-52-81GG",
            qrPayload = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*",
            barcodePayload = "BCJH-52-81GG@1N5X0C",
        )
        composeRule.setContent {
            HistoryEntryDetail(entry = entry, language = AppLanguage.ENGLISH)
        }

        composeRule.onNodeWithTag(HistoryTestTags.ENTRY_DETAIL).assertIsDisplayed()
        composeRule.onNodeWithText("Delivery information (QR)").assertIsDisplayed()
        composeRule.onNodeWithText("DCLP675300").assertIsDisplayed()
        composeRule.onNodeWithText("1N5X0C").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun expandedLayoutKeepsListAndDetailVisibleForWideWindows() {
        val session = MatchSession(
            id = "session",
            startedAt = 1_000L,
            endedAt = 2_000L,
            name = "Morning",
            entries = listOf(
                MatchEntry(
                    id = "box-1",
                    code = "BCJH-52-81GG",
                    matchedAt = 1_500L,
                ),
            ),
        )
        composeRule.setContent {
            HistoryContent(
                sessions = listOf(session),
                selectedSessionId = session.id,
                layoutMode = HistoryLayoutMode.EXPANDED,
            )
        }

        composeRule.onNodeWithTag(HistoryTestTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(HistoryTestTags.SESSION_DETAIL).assertIsDisplayed()
        composeRule.onNodeWithTag(HistoryTestTags.SESSION_ROW).assertIsDisplayed()
        composeRule.onNodeWithTag(HistoryTestTags.SESSION_ROW)
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        composeRule.onNodeWithTag("${HistoryTestTags.SESSION_ROW}.delete")
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun expandedSessionListExposesSelectionStateToAccessibilityServices() {
        val selected = MatchSession(
            id = "selected-session",
            startedAt = 2_000L,
            name = "Selected session",
        )
        val other = MatchSession(
            id = "other-session",
            startedAt = 1_000L,
            name = "Other session",
        )
        composeRule.setContent {
            HistoryContent(
                sessions = listOf(other, selected),
                selectedSessionId = selected.id,
                layoutMode = HistoryLayoutMode.EXPANDED,
                language = AppLanguage.ENGLISH,
            )
        }

        composeRule.onAllNodesWithTag(HistoryTestTags.SESSION_ROW)
            .get(0)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        composeRule.onAllNodesWithTag(HistoryTestTags.SESSION_ROW)
            .get(1)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected"))
    }

    @Test
    fun englishBoxCountsUseSingularAndPluralAndRedrawInJapanese() {
        val language = mutableStateOf(AppLanguage.ENGLISH)
        val session = MatchSession(
            id = "plural-session",
            startedAt = 1_000L,
            endedAt = 2_000L,
            entries = listOf(
                MatchEntry(id = "one", code = "ONE", matchedAt = 1_100L),
                MatchEntry(id = "two-a", code = "TWO", matchedAt = 1_200L),
                MatchEntry(id = "two-b", code = "TWO", matchedAt = 1_300L),
            ),
        )
        composeRule.setContent {
            HistorySessionDetail(session = session, language = language.value)
        }

        composeRule.onNodeWithText("1 box").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("2 boxes").performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle { language.value = AppLanguage.JAPANESE }

        composeRule.onNodeWithText("1箱").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("2箱").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("1 box").assertCountEquals(0)
        composeRule.onAllNodesWithText("2 boxes").assertCountEquals(0)
    }
}
