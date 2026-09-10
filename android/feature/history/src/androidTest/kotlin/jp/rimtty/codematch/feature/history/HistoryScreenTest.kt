package jp.rimtty.codematch.feature.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
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
    fun shareAllHistoryActionIsDisabledWhileThereIsNothingToExport() {
        var shared = 0
        composeRule.setContent {
            HistoryScreen(
                sessions = emptyList(),
                language = AppLanguage.ENGLISH,
                onShareAllHistory = { shared += 1 },
            )
        }

        composeRule.onNodeWithTag(HistoryTestTags.SHARE_ALL)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Share all history")
            .assertIsNotEnabled()
            .performClick()

        assertEquals(0, shared)
    }

    @Test
    fun shareAllHistoryActionExportsEverySessionAndIsLabelledInJapanese() {
        var shared = 0
        composeRule.setContent {
            HistoryScreen(
                sessions = listOf(
                    MatchSession(id = "old", startedAt = 1L, name = "Old"),
                    MatchSession(id = "new", startedAt = 2L, name = "New"),
                ),
                onShareAllHistory = { shared += 1 },
            )
        }

        composeRule.onNodeWithTag(HistoryTestTags.SHARE_ALL)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals("照合履歴をすべて共有")
            .assertIsEnabled()
            .performClick()

        assertEquals(1, shared)
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
    fun moltenEntryDetailDisplaysAllParsedFields() {
        assertEquals(61, MOLTEN_QR_2.length)
        val entry = MatchEntry(
            code = "PAF1-15-422",
            qrPayload = MOLTEN_QR_2,
            barcodePayload = "PAF1-15-422@0NKD3C",
        )
        composeRule.setContent {
            HistoryEntryDetail(entry = entry, language = AppLanguage.ENGLISH)
        }

        composeRule.onNodeWithTag(HistoryTestTags.ENTRY_DETAIL).assertIsDisplayed()
        composeRule.onNodeWithText("Delivery information (QR)").assertIsDisplayed()
        // The detail is a LazyColumn; scroll the list itself so each row is
        // fully inside the compact CI emulator viewport before asserting.
        listOf("AK6805", "UAG5560", "FA2", "P59", "01FEM", "120", "09/08", "00:00", "0NKD3C")
            .forEach { text ->
                composeRule.onNodeWithTag(HistoryTestTags.ENTRY_DETAIL)
                    .performScrollToNode(hasText(text))
                composeRule.onNodeWithText(text).assertIsDisplayed()
            }
        // The Sawai record has no delivery number, so its rows must stay away.
        composeRule.onAllNodesWithText("Card number").assertCountEquals(0)
    }

    @Test
    fun densoEntryDetailDisplaysAllParsedFields() {
        assertEquals(221, DENSO_QR_0140.length)
        val entry = MatchEntry(
            code = "860150-7722",
            qrPayload = DENSO_QR_0140,
            barcodePayload = "860150-7722@1DZ50O",
        )
        composeRule.setContent {
            HistoryEntryDetail(entry = entry, language = AppLanguage.JAPANESE)
        }

        composeRule.onNodeWithTag(HistoryTestTags.ENTRY_DETAIL).assertIsDisplayed()
        // The detail is a LazyColumn; scroll the list itself so each row is
        // fully inside the compact CI emulator viewport before asserting.
        composeRule.onNodeWithTag(HistoryTestTags.ENTRY_DETAIL)
            .performScrollToNode(hasText("860150-7722"))
        composeRule.onAllNodesWithText("860150-7722").onFirst().assertIsDisplayed()
        listOf("0140", "2026/09/08", "S001", "72", "SWS", "9924543330", "M6", "C01008-45")
            .forEach { text ->
                composeRule.onNodeWithTag(HistoryTestTags.ENTRY_DETAIL)
                    .performScrollToNode(hasText(text))
                composeRule.onNodeWithText(text).assertIsDisplayed()
            }
        // The lenient Sawai parser also accepts this payload, so its rows must
        // never appear for a Denso kanban.
        composeRule.onAllNodesWithText("カード番号").assertCountEquals(0)
    }

    @Test
    fun densoSessionDetailAndRowShowDestination() {
        val session = MatchSession(
            id = "denso-session",
            startedAt = 1_000L,
            endedAt = 2_000L,
            destination = Destination.DENSO,
            entries = listOf(
                MatchEntry(
                    id = "box-1",
                    code = "860150-7722",
                    matchedAt = 1_100L,
                    qrPayload = DENSO_QR_0140,
                    barcodePayload = "860150-7722@1DZ50O",
                ),
            ),
        )
        // The row and the overview are stacked at full width: the expanded
        // layout's list pane is too narrow to display the row's own text.
        composeRule.setContent {
            Column(Modifier.fillMaxSize()) {
                HistoryScreen(
                    sessions = listOf(session),
                    language = AppLanguage.JAPANESE,
                    modifier = Modifier.weight(1f),
                )
                HistorySessionDetail(
                    session = session,
                    language = AppLanguage.JAPANESE,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        composeRule.onNodeWithTag(HistoryTestTags.SESSION_DESTINATION, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("デンソー")
        composeRule.onNodeWithTag(HistoryTestTags.SESSION_DETAIL)
            .performScrollToNode(hasText("仕向地"))
        composeRule.onNodeWithText("仕向地").assertIsDisplayed()
        // Delivery numbers stay Molten-only.
        composeRule.onAllNodesWithText("納品番号数").assertCountEquals(0)
    }

    @Test
    fun moltenGroupDetailShowsPerDeliveryNumberSummary() {
        assertEquals(61, MOLTEN_QR_2.length)
        assertEquals(61, MOLTEN_QR_3.length)
        val group = MatchSession(
            entries = listOf(
                MatchEntry(
                    id = "box-1",
                    code = "PAF1-15-422",
                    matchedAt = 1_000L,
                    qrPayload = MOLTEN_QR_2,
                    barcodePayload = "PAF1-15-422@0NKD3C",
                ),
                MatchEntry(
                    id = "box-2",
                    code = "PAF1-15-422",
                    matchedAt = 2_000L,
                    qrPayload = MOLTEN_QR_2,
                    barcodePayload = "PAF1-15-422@0NLL3C",
                ),
                MatchEntry(
                    id = "box-3",
                    code = "PAF1-15-422",
                    matchedAt = 3_000L,
                    qrPayload = MOLTEN_QR_3,
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
            id = "molten-session",
            startedAt = 1_000L,
            endedAt = 2_000L,
            destination = Destination.MOLTEN,
            entries = listOf(
                MatchEntry(
                    id = "box-1",
                    code = "PAF1-15-422",
                    matchedAt = 1_100L,
                    qrPayload = MOLTEN_QR_2,
                    barcodePayload = "PAF1-15-422@0NKD3C",
                ),
                MatchEntry(
                    id = "box-2",
                    code = "PAF1-15-422",
                    matchedAt = 1_200L,
                    qrPayload = MOLTEN_QR_3,
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
            .assertTextEquals("Molten")
        // Scoped to the overview so the row's own "Molten" cannot satisfy it.
        composeRule.onNodeWithTag(HistoryTestTags.SESSION_DETAIL)
            .performScrollToNode(hasText("Ship-to"))
        composeRule.onNodeWithTag(HistoryTestTags.SESSION_DETAIL)
            .performScrollToNode(hasText("Molten"))
        composeRule.onNodeWithText("Ship-to").assertIsDisplayed()
        composeRule.onNodeWithTag(HistoryTestTags.SESSION_DETAIL)
            .performScrollToNode(hasText("Delivery numbers"))
        composeRule.onNodeWithText("Delivery numbers").assertIsDisplayed()
    }

    @Test
    fun sessionDetailOffersInspectionAndMatchHistoryPdfRowsInJapanese() {
        val session = MatchSession(
            id = "sawai-session",
            startedAt = 1_000L,
            endedAt = 2_000L,
            destination = Destination.SAWAI,
            entries = listOf(
                MatchEntry(
                    id = "box-1",
                    code = "BCJH-52-81GG",
                    matchedAt = 1_100L,
                    qrPayload = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*",
                    barcodePayload = "BCJH-52-81GG@1N5X0C",
                ),
            ),
        )
        val saved = mutableListOf<String>()
        val shared = mutableListOf<String>()
        composeRule.setContent {
            HistorySessionDetail(
                session = session,
                language = AppLanguage.JAPANESE,
                onSavePdf = { saved += "history" },
                onSharePdf = { shared += "history" },
                onSaveInspectionReport = { saved += "inspection" },
                onShareInspectionReport = { shared += "inspection" },
            )
        }

        val detail = composeRule.onNodeWithTag(HistoryTestTags.SESSION_DETAIL)
        detail.performScrollToNode(hasText("検品レポート"))
        composeRule.onNodeWithText("検品レポート").assertIsDisplayed()
        detail.performScrollToNode(hasText("照合履歴レポート"))
        composeRule.onNodeWithText("照合履歴レポート").assertIsDisplayed()
        composeRule.onNodeWithTag(HistoryTestTags.SAVE_INSPECTION_REPORT).performScrollTo().performClick()
        composeRule.onNodeWithTag(HistoryTestTags.SHARE_INSPECTION_REPORT).performScrollTo().performClick()
        composeRule.onNodeWithTag(HistoryTestTags.SAVE_PDF).performScrollTo().performClick()
        composeRule.onNodeWithTag(HistoryTestTags.SHARE_PDF).performScrollTo().performClick()
        assertEquals(listOf("inspection", "history"), saved)
        assertEquals(listOf("inspection", "history"), shared)
    }

    private companion object {
        // Trailing spaces are part of the fixed-position record; the length
        // assertions in the tests fail first if they are ever trimmed away.
        const val MOLTEN_QR_2 =
            "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
        // A real デンソー kanban; see shared/test-fixtures/matching-cases.json.
        const val DENSO_QR_0140 =
            "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"
        const val MOLTEN_QR_3 =
            "AK6805PAF115422          UAG5561000FA2P5901FEM000006009080000"
    }
}
