package jp.rimtty.codematch

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.rimtty.codematch.navigation.CodeMatchBackHandler
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun predictiveHandlerAlsoHandlesDispatcherBackPress() {
        var handled = false
        var dispatcher: androidx.activity.OnBackPressedDispatcher? = null
        composeRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CodeMatchBackHandler(
                enabled = true,
                onBack = { handled = true },
            )
        }

        composeRule.runOnIdle {
            dispatcher?.onBackPressed()
        }
        composeRule.waitForIdle()

        assertTrue(handled)
    }
}
