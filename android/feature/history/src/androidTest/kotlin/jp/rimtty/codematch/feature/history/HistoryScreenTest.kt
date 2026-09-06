package jp.rimtty.codematch.feature.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.Destination
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
    fun englishSessionCountFitsOnOneLineWithoutClipping() {
        composeRule.setContent {
            HistoryScreen(
                sessions = listOf(MatchSession(
                    id = "count-layout",
                    startedAt = 1L,
                    entries = List(123) { MatchEntry(id = "box-$it", code = "PART") },
                )),
                language = AppLanguage.ENGLISH,
            )
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("123 boxes", useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertEquals(false, layouts.single().hasVisualOverflow)
    }

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
        val selectedSessionId = mutableStateOf<String?>("selected-session")
        val selected = MatchSession(
            id = "selected-session",
            startedAt = 2_000L,
            name = "Selected session",
        )
        composeRule.setContent {
            HistoryContent(
                sessions = listOf(selected),
                selectedSessionId = selectedSessionId.value,
                layoutMode = HistoryLayoutMode.EXPANDED,
                language = AppLanguage.ENGLISH,
            )
        }

        composeRule.onNodeWithTag(HistoryTestTags.SESSION_ROW)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))

        composeRule.runOnIdle { selectedSessionId.value = null }

        composeRule.onNodeWithTag(HistoryTestTags.SESSION_ROW)
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

    @Test
    fun moltecEntryDetailDisplaysAllParsedFields() {
        assertEquals(61, MOLTEC_QR_2.length)
        val entry = MatchEntry(
            code = "PAF1-15-422",
            qrPayload = MOLTEC_QR_2,
            barcodePayload = "PAF1-15-422@0NKD3C",
        )
        composeRule.setContent {
            HistoryEntryDetail(entry = entry, language = AppLanguage.ENGLISH)
        }

        composeRule.onNodeWithTag(HistoryTestTags.ENTRY_DETAIL).assertIsDisplayed()
        composeRule.onNodeWithText("Delivery information (QR)").assertIsDisplayed()
        composeRule.onNodeWithText("AK6805").assertIsDisplayed()
        composeRule.onNodeWithText("UAG5560").assertIsDisplayed()
        composeRule.onNodeWithText("FA2").assertIsDisplayed()
        composeRule.onNodeWithText("P59").assertIsDisplayed()
        composeRule.onNodeWithText("01FEM").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("120").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("09/08").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("00:00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("0NKD3C").performScrollTo().assertIsDisplayed()
        // The Sawai record has no delivery number, so its rows must stay away.
        composeRule.onAllNodesWithText("Card number").assertCountEquals(0)
    }

    @Test
    fun moltecGroupDetailShowsPerDeliveryNumberSummary() {
        assertEquals(61, MOLTEC_QR_2.length)
        assertEquals(61, MOLTEC_QR_3.length)
        val group = MatchSession(
            entries = listOf(
                MatchEntry(
                    id = "box-1",
                    code = "PAF1-15-422",
                    matchedAt = 1_000L,
                    qrPayload = MOLTEC_QR_2,
                    barcodePayload = "PAF1-15-422@0NKD3C",
                ),
                MatchEntry(
                    id = "box-2",
                    code = "PAF1-15-422",
                    matchedAt = 2_000L,
                    qrPayload = MOLTEC_QR_2,
                    barcodePayload = "PAF1-15-422@0NLL3C",
                ),
                MatchEntry(
                    id = "box-3",
                    code = "PAF1-15-422",
                    matchedAt = 3_000L,
                    qrPayload = MOLTEC_QR_3,
                    barcodePayload = "PAF1-15-422@0NMM3C",
                ),
            ),
        ).groupedEntries.single()
        composeRule.setContent {
            HistoryGroupDetail(group = group, language = AppLanguage.ENGLISH)
        }

        composeRule.onNodeWithTag(HistoryTestTags.GROUP_DETAIL).assertIsDisplayed()
        composeRule.onNodeWithText("Boxes per delivery number").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithTag(HistoryTestTags.DELIVERY_GROUP_ROW).assertCountEquals(2)
        composeRule.onNodeWithText("Delivery number UAG5560 (2 boxes, 240 pcs total)")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Delivery number UAG5561 (1 box, 60 pcs total)")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun sessionDetailAndRowShowDestination() {
        val session = MatchSession(
            id = "moltec-session",
            startedAt = 1_000L,
            endedAt = 2_000L,
            destination = Destination.MOLTEC,
            entries = listOf(
                MatchEntry(
                    id = "box-1",
                    code = "PAF1-15-422",
                    matchedAt = 1_100L,
                    qrPayload = MOLTEC_QR_2,
                    barcodePayload = "PAF1-15-422@0NKD3C",
                ),
                MatchEntry(
                    id = "box-2",
                    code = "PAF1-15-422",
                    matchedAt = 1_200L,
                    qrPayload = MOLTEC_QR_3,
                    barcodePayload = "PAF1-15-422@0NMM3C",
                ),
            ),
        )
        // The row and the overview are stacked at full width: the expanded
        // layout's list pane is too narrow to display the row's own text.
        composeRule.setContent {
            Column(Modifier.fillMaxSize()) {
                HistoryScreen(
                    sessions = listOf(session),
                    language = AppLanguage.ENGLISH,
                    modifier = Modifier.weight(1f),
                )
                HistorySessionDetail(
                    session = session,
                    language = AppLanguage.ENGLISH,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        composeRule.onNodeWithTag(HistoryTestTags.SESSION_DESTINATION, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("Moltec")
        // Scoped to the overview so the row's own "Moltec" cannot satisfy it.
        composeRule.onNodeWithTag(HistoryTestTags.SESSION_DETAIL)
            .performScrollToNode(hasText("Ship-to"))
        composeRule.onNodeWithTag(HistoryTestTags.SESSION_DETAIL)
            .performScrollToNode(hasText("Moltec"))
        composeRule.onNodeWithText("Ship-to").assertIsDisplayed()
        composeRule.onNodeWithTag(HistoryTestTags.SESSION_DETAIL)
            .performScrollToNode(hasText("Delivery numbers"))
        composeRule.onNodeWithText("Delivery numbers").assertIsDisplayed()
    }

    private companion object {
        // Trailing spaces are part of the fixed-position record; the length
        // assertions in the tests fail first if they are ever trimmed away.
        const val MOLTEC_QR_2 =
            "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
        const val MOLTEC_QR_3 =
            "AK6805PAF115422          UAG5561000FA2P5901FEM000006009080000"
    }
}
