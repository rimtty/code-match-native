package jp.rimtty.codematch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.history_screen_description),
        ).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.destination_settings),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.settings_screen_description),
        ).assertIsDisplayed()

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
        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.history_screen_description),
        ).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.history_screen_description),
        ).assertIsDisplayed()
    }
}
