package jp.rimtty.codematch.feature.scan

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.MatchResult
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanFontScaleAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactStartFormKeepsNameAndStartActionReachableAtLargeFontScales() {
        val actions = mutableListOf<ScanUiAction>()
        val fontScale = mutableStateOf(FONT_SCALES.first())
        setCompactContent(fontScale) {
            ScanScreen(
                state = ScanUiState(),
                onAction = actions::add,
            )
        }

        FONT_SCALES.forEachIndexed { index, scale ->
            if (index > 0) {
                composeRule.runOnIdle {
                    fontScale.value = scale
                    actions.clear()
                }
            }
            composeRule.onNodeWithTag("scan_session_name")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("scan_start_session")
                .performScrollTo()
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()

            composeRule.runOnIdle {
                assertTrue(
                    "start action missing at fontScale=$scale",
                    actions.contains(ScanUiAction.StartSession),
                )
            }
        }
    }

    @Test
    fun compactResultKeepsNextAndAutoAdvanceControlsReachableAtLargeFontScales() {
        val actions = mutableListOf<ScanUiAction>()
        val fontScale = mutableStateOf(FONT_SCALES.first())
        setCompactContent(fontScale) {
            ScanScreen(
                state = ScanUiState.fromSession(
                    session = ScanSessionState(
                        scan = ScanState.Result(
                            qrPayload = "QR-PAYLOAD",
                            barcodePayload = "BARCODE-PAYLOAD",
                            result = MatchResult.MATCH,
                            matchedCount = 1,
                        ),
                        autoAdvanceEnabled = true,
                        autoAdvanceDelay = AutoAdvanceDelay.THREE_SECONDS,
                        autoAdvanceSecondsRemaining = 2,
                    ),
                    sessionActive = true,
                ),
                onAction = actions::add,
            )
        }

        FONT_SCALES.forEachIndexed { index, scale ->
            if (index > 0) {
                composeRule.runOnIdle {
                    fontScale.value = scale
                    actions.clear()
                }
            }

            composeRule.onNodeWithTag("scan_manual_next")
                .performScrollTo()
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            composeRule.onNodeWithTag("scan_auto_delay_5")
                .performScrollTo()
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()

            composeRule.runOnIdle {
                assertTrue(
                    "manual next missing at fontScale=$scale",
                    actions.contains(ScanUiAction.ManualNext),
                )
                assertTrue(
                    "delay choice missing at fontScale=$scale",
                    actions.contains(ScanUiAction.SetAutoAdvanceDelay(AutoAdvanceDelay.FIVE_SECONDS)),
                )
            }
        }
    }

    @Test
    fun resultPartNumbersAppearBelowLabelsAtDeviceFontScale() {
        composeRule.setContent {
            ScanScreen(
                state = ScanUiState.fromSession(
                    session = ScanSessionState(scan = ScanState.Result(
                        qrPayload = "QR-PAYLOAD",
                        barcodePayload = "BARCODE-PAYLOAD",
                        result = MatchResult.MATCH,
                        matchedCount = 1,
                    )),
                    sessionActive = true,
                ),
                onAction = {},
            )
        }
        listOf("scan_result_qr_part", "scan_result_barcode_part").forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val label = composeRule.onNodeWithTag("$tag.label", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val value = composeRule.onNodeWithTag("$tag.value", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("value must be below label", value.top >= label.bottom)
        }
    }

    private fun setCompactContent(fontScale: MutableState<Float>, content: @Composable () -> Unit) {
        composeRule.setContent {
            val baseConfiguration = LocalConfiguration.current
            val baseDensity = LocalDensity.current
            val configuration = Configuration(baseConfiguration).apply {
                screenWidthDp = COMPACT_WIDTH_DP
                screenHeightDp = COMPACT_HEIGHT_DP
                this.fontScale = fontScale.value
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(baseDensity.density, fontScale.value),
            ) {
                Box(
                    modifier = androidx.compose.ui.Modifier.size(
                        width = COMPACT_WIDTH_DP.dp,
                        height = COMPACT_HEIGHT_DP.dp,
                    ),
                ) {
                    content()
                }
            }
        }
    }

    private companion object {
        val FONT_SCALES = listOf(1.3f, 2.0f)
        const val COMPACT_WIDTH_DP = 320
        const val COMPACT_HEIGHT_DP = 640
    }
}
