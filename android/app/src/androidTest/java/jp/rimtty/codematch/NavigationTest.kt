package jp.rimtty.codematch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.rimtty.codematch.feature.history.HistoryTestTags
import jp.rimtty.codematch.feature.settings.SettingsTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun allTopLevelDestinationsCanBeSelected() {
        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.destination_history),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithTag(HistoryTestTags.SCREEN).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.destination_settings),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.SCREEN).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.destination_scan),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.scan_screen_description),
        ).assertIsDisplayed()
    }

    @Test
    fun selectedDestinationSurvivesActivityRecreation() {
        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.destination_history),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithTag(HistoryTestTags.SCREEN).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HistoryTestTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun settingsGuideConsumesSystemBackBeforeActivityBack() {
        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.destination_settings),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.SETUP_GUIDE).assertIsDisplayed()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SettingsTestTags.SCREEN).assertIsDisplayed()
        composeRule.onAllNodesWithTag(SettingsTestTags.SETUP_GUIDE)
            .assertCountEquals(0)
    }
}
