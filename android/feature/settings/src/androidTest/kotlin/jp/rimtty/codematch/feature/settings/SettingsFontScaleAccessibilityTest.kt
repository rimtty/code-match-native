package jp.rimtty.codematch.feature.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsFontScaleAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactSettingsKeepsGuidePreferencesAndLanguageActionsReachableAtLargeFontScales() {
        val actions = mutableListOf<SettingsUiAction>()
        val fontScale = mutableStateOf(FONT_SCALES.first())
        setCompactContent(fontScale) {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(setupGuideVisible = true),
                    onAction = actions::add,
                )
            }
        }

        FONT_SCALES.forEachIndexed { index, scale ->
            if (index > 0) {
                composeRule.runOnIdle {
                    fontScale.value = scale
                    actions.clear()
                }
            }

            composeRule.onNodeWithTag(SettingsTestTags.SETUP_NEXT)
                .performScrollTo()
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            composeRule.onNodeWithTag(SettingsTestTags.AUTO_ADVANCE_SWITCH)
                .performScrollTo()
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            composeRule.onAllNodesWithTag(SettingsTestTags.LANGUAGE_CHOICE)
                .get(1)
                .performScrollTo()
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()

            composeRule.runOnIdle {
                assertTrue(
                    "auto-advance action missing at fontScale=$scale",
                    actions.contains(SettingsUiAction.SetAutoAdvanceEnabled(true)),
                )
                assertTrue(
                    "language action missing at fontScale=$scale",
                    actions.contains(SettingsUiAction.SetLanguage(jp.rimtty.codematch.core.model.AppLanguage.ENGLISH)),
                )
            }
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
                    modifier = Modifier.size(
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
