package jp.rimtty.codematch.feature.history

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.rimtty.codematch.core.model.MatchEntry
import jp.rimtty.codematch.core.model.MatchSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryFontScaleAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactSessionDetailKeepsGroupsAndPdfActionsReachableAtLargeFontScales() {
        val selectedGroups = mutableListOf<String>()
        var saved = 0
        var shared = 0
        val fontScale = mutableStateOf(FONT_SCALES.first())
        val session = sampleSession()
        setCompactContent(fontScale) {
            HistoryContent(
                sessions = listOf(session),
                selectedSessionId = session.id,
                layoutMode = HistoryLayoutMode.COMPACT,
                onGroupSelected = selectedGroups::add,
                onSavePdf = { saved += 1 },
                onSharePdf = { shared += 1 },
            )
        }

        FONT_SCALES.forEachIndexed { index, scale ->
            if (index > 0) {
                composeRule.runOnIdle {
                    fontScale.value = scale
                    saved = 0
                    shared = 0
                    selectedGroups.clear()
                }
            }
            val detail = composeRule.onNodeWithTag(HistoryTestTags.SESSION_DETAIL)
            detail.performScrollToNode(hasTestTag(HistoryTestTags.SAVE_PDF))
            composeRule.onNodeWithTag(HistoryTestTags.SAVE_PDF)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            detail.performScrollToNode(hasTestTag(HistoryTestTags.SHARE_PDF))
            composeRule.onNodeWithTag(HistoryTestTags.SHARE_PDF)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            detail.performScrollToNode(hasTestTag(HistoryTestTags.GROUP_ROW))
            composeRule.onNodeWithTag(HistoryTestTags.GROUP_ROW)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()

            assertEquals("fontScale=$scale", 1, saved)
            assertEquals("fontScale=$scale", 1, shared)
            assertEquals("fontScale=$scale", listOf("PART-1"), selectedGroups)
        }
    }

    @Test
    fun compactGroupDetailKeepsBoxSelectionReachableAtLargeFontScales() {
        val selectedEntries = mutableListOf<String>()
        val fontScale = mutableStateOf(FONT_SCALES.first())
        val group = sampleSession().groupedEntries.single()
        setCompactContent(fontScale) {
            HistoryGroupDetail(
                group = group,
                onEntrySelected = selectedEntries::add,
            )
        }

        FONT_SCALES.forEachIndexed { index, scale ->
            if (index > 0) {
                composeRule.runOnIdle {
                    fontScale.value = scale
                    selectedEntries.clear()
                }
            }
            composeRule.onNodeWithTag(HistoryTestTags.GROUP_DETAIL)
                .performScrollToNode(hasTestTag(HistoryTestTags.BOX_ROW))
            composeRule.onAllNodesWithTag(HistoryTestTags.BOX_ROW)
                .get(0)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()

            assertEquals("fontScale=$scale", listOf("entry-1"), selectedEntries)
        }
    }

    @Test
    fun expandedHistoryKeepsBothPanesAndPdfActionsReachableAtLargeFontScales() {
        var saved = 0
        val fontScale = mutableStateOf(FONT_SCALES.first())
        val session = sampleSession()
        setCompactContent(fontScale, widthDp = EXPANDED_WIDTH_DP) {
            HistoryContent(
                sessions = listOf(session),
                selectedSessionId = session.id,
                layoutMode = HistoryLayoutMode.EXPANDED,
                onSavePdf = { saved += 1 },
            )
        }

        FONT_SCALES.forEachIndexed { index, scale ->
            if (index > 0) {
                composeRule.runOnIdle {
                    fontScale.value = scale
                    saved = 0
                }
            }
            // The wide constraint exercises the real expanded branch. The
            // host viewport may be narrower than the synthetic 840dp canvas,
            // so the pane nodes are asserted for composition and actionability
            // rather than viewport visibility here.
            composeRule.onNodeWithTag(HistoryTestTags.SESSION_ROW).assertExists()
            composeRule.onNodeWithTag(HistoryTestTags.SESSION_ROW)
                .assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithTag(HistoryTestTags.SESSION_DETAIL)
                .assertExists()
                .performScrollToNode(hasTestTag(HistoryTestTags.SAVE_PDF))
            composeRule.onNodeWithTag(HistoryTestTags.SAVE_PDF)
                .assertExists()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            assertEquals("fontScale=$scale", 1, saved)
        }
    }

    private fun setCompactContent(
        fontScale: MutableState<Float>,
        widthDp: Int = COMPACT_WIDTH_DP,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            val baseConfiguration = LocalConfiguration.current
            val baseDensity = LocalDensity.current
            val configuration = Configuration(baseConfiguration).apply {
                screenWidthDp = widthDp
                screenHeightDp = COMPACT_HEIGHT_DP
                this.fontScale = fontScale.value
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(baseDensity.density, fontScale.value),
            ) {
                Box(
                    modifier = Modifier.size(
                        width = widthDp.dp,
                        height = COMPACT_HEIGHT_DP.dp,
                    ),
                ) {
                    content()
                }
            }
        }
    }

    private fun sampleSession() = MatchSession(
        id = "font-scale-session",
        startedAt = 1_000L,
        endedAt = 2_000L,
        name = "午前の検査セッション",
        entries = listOf(
            MatchEntry(id = "entry-1", code = "PART-1", matchedAt = 1_100L),
            MatchEntry(id = "entry-2", code = "PART-1", matchedAt = 1_200L),
        ),
    )

    private companion object {
        val FONT_SCALES = listOf(1.3f, 2.0f)
        const val COMPACT_WIDTH_DP = 320
        const val COMPACT_HEIGHT_DP = 640
        const val EXPANDED_WIDTH_DP = 840
    }
}
