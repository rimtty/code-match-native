package jp.rimtty.codematch.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryPdfFeedbackHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun retryActionIsAccessibleAndInvokesTheRetryCallback() {
        var retried = false
        composeRule.setContent {
            var feedback by remember {
                mutableStateOf<HistoryPdfFeedback?>(
                    HistoryPdfFeedback(
                        message = "Could not save the PDF.",
                        retry = { retried = true },
                    ),
                )
            }
            MaterialTheme {
                HistoryPdfFeedbackHost(
                    snackbarHostState = remember { SnackbarHostState() },
                    feedback = feedback,
                    retryLabel = "Retry",
                    onConsumed = { feedback = null },
                )
            }
        }

        composeRule.onNodeWithText("Could not save the PDF.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun feedbackWithoutRetryDoesNotOfferAnAction() {
        composeRule.setContent {
            var feedback by remember {
                mutableStateOf<HistoryPdfFeedback?>(
                    HistoryPdfFeedback(message = "Could not save the PDF.", retry = null),
                )
            }
            MaterialTheme {
                HistoryPdfFeedbackHost(
                    snackbarHostState = remember { SnackbarHostState() },
                    feedback = feedback,
                    retryLabel = "Retry",
                    onConsumed = { feedback = null },
                )
            }
        }

        composeRule.onNodeWithText("Could not save the PDF.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Retry").assertCountEquals(0)
    }
}
