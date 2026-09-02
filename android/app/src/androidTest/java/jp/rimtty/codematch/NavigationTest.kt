package jp.rimtty.codematch

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import jp.rimtty.codematch.di.DebugAppTestEntryPoint
import jp.rimtty.codematch.feature.history.HistoryTestTags
import jp.rimtty.codematch.feature.settings.SettingsTestTags
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val dependencies: DebugAppTestEntryPoint
        get() = EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            DebugAppTestEntryPoint::class.java,
        )

    private var seededSessionId: String? = null
    private lateinit var seededSessionName: String
    private lateinit var seededCode: String

    @After
    fun deleteOnlyTheSeededSession() {
        val id = seededSessionId ?: return
        runBlocking { dependencies.historyRepository().deleteSession(id) }
        seededSessionId = null
    }

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
    fun historySelectionSurvivesActivityRecreationAndDestinationSwitches() {
        seedFinishedHistorySession()
        openHistoryAndSelectBox()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        waitForTag(HistoryTestTags.ENTRY_DETAIL)

        openDestination(R.string.destination_settings)
        openDestination(R.string.destination_scan)
        openDestination(R.string.destination_history)
        waitForTag(HistoryTestTags.ENTRY_DETAIL)
    }

    @Test
    fun compactHistorySystemBackPopsBoxGroupSessionAndList() {
        seedFinishedHistorySession()
        openHistoryAndSelectBox()

        pressSystemBack()
        waitForTag(HistoryTestTags.GROUP_DETAIL)
        composeRule.onAllNodesWithTag(HistoryTestTags.ENTRY_DETAIL).assertCountEquals(0)

        pressSystemBack()
        waitForTag(HistoryTestTags.SESSION_DETAIL)
        composeRule.onAllNodesWithTag(HistoryTestTags.GROUP_DETAIL).assertCountEquals(0)

        pressSystemBack()
        waitForTag(HistoryTestTags.SESSION_ROW)
        composeRule.onNodeWithText(seededSessionName).assertIsDisplayed()
        composeRule.onAllNodesWithTag(HistoryTestTags.SESSION_DETAIL).assertCountEquals(0)
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

    private fun seedFinishedHistorySession() {
        assumeTrue(
            "History navigation fixture requires a compact window",
            composeRule.activity.resources.configuration.screenWidthDp < 840,
        )
        assumeTrue(
            "History navigation fixture never mutates a pre-existing active session",
            runBlocking { dependencies.historyRepository().activeSession.first() == null },
        )
        val suffix = UUID.randomUUID().toString().take(8)
        seededSessionName = "Navigation fixture $suffix"
        seededCode = "NAV-$suffix"
        val repository = dependencies.historyRepository()
        seededSessionId = runBlocking {
            repository.beginSession(
                name = seededSessionName,
                at = System.currentTimeMillis(),
            )
        }
        val id = requireNotNull(seededSessionId)
        runBlocking {
            check(repository.recordMatch(seededCode, at = System.currentTimeMillis()) == 1)
            check(repository.recordMatch(seededCode, at = System.currentTimeMillis() + 1L) == 2)
            repository.endSession(id, at = System.currentTimeMillis() + 2L)
        }
    }

    private fun openHistoryAndSelectBox() {
        openDestination(R.string.destination_history)
        waitForTag(HistoryTestTags.SESSION_ROW)
        composeRule.onNodeWithText(seededSessionName)
            .performScrollTo()
            .performClick()
        waitForTag(HistoryTestTags.SESSION_DETAIL)

        composeRule.onNodeWithTag(HistoryTestTags.GROUP_ROW)
            .performScrollTo()
            .performClick()
        waitForTag(HistoryTestTags.GROUP_DETAIL)

        composeRule.onAllNodesWithTag(HistoryTestTags.BOX_ROW)
            .get(0)
            .performScrollTo()
            .performClick()
        waitForTag(HistoryTestTags.ENTRY_DETAIL)
    }

    private fun openDestination(destinationRes: Int) {
        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(destinationRes),
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitForIdle()
    }

    private fun pressSystemBack() {
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
