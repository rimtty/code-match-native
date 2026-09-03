package jp.rimtty.codematch

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.rimtty.codematch.navigation.CodeMatchBackHandler
import org.junit.Assert.assertFalse
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

    @Test
    fun disabledPredictiveHandlerDoesNotHandleDispatcherBackPress() {
        var handled = false
        var fallbackHandled = false
        var dispatcher: androidx.activity.OnBackPressedDispatcher? = null
        composeRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CodeMatchBackHandler(
                enabled = false,
                onBack = { handled = true },
            )
        }

        composeRule.runOnIdle {
            dispatcher?.addCallback(
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        fallbackHandled = true
                    }
                },
            )
            dispatcher?.onBackPressed()
        }
        composeRule.waitForIdle()

        assertFalse(handled)
        assertTrue(fallbackHandled)
    }

    @Test
    fun cancelledPredictiveGestureDoesNotInvokeBackCallback() {
        var handled = false
        var dispatcher: androidx.activity.OnBackPressedDispatcher? = null
        composeRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CodeMatchBackHandler(
                enabled = true,
                onBack = { handled = true },
            )
        }

        val start = BackEventCompat(
            touchX = 0f,
            touchY = 0f,
            progress = 0.1f,
            swipeEdge = BackEventCompat.EDGE_LEFT,
        )
        composeRule.runOnIdle {
            dispatcher?.dispatchOnBackStarted(start)
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            dispatcher?.dispatchOnBackProgressed(
                BackEventCompat(
                    touchX = 0f,
                    touchY = 0f,
                    progress = 0.6f,
                    swipeEdge = BackEventCompat.EDGE_LEFT,
                ),
            )
            dispatcher?.dispatchOnBackCancelled()
        }
        composeRule.waitForIdle()

        assertFalse(handled)
    }
}
