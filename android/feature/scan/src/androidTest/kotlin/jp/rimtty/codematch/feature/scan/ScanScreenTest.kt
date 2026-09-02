package jp.rimtty.codematch.feature.scan

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.scanner.api.InputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val qrPayload =
        "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private val barcodePayload = "BCJH-52-81GG@1N5X0C"

    @Test
    fun startFormSendsNameAndStartAction() {
        val actions = mutableListOf<ScanUiAction>()
        composeRule.setContent {
            ScanScreen(ScanUiState(), actions::add)
        }

        composeRule.onNodeWithTag("scan_session_name").performTextInput("午前便")
        composeRule.onNodeWithTag("scan_start_session").performClick()

        composeRule.runOnIdle {
            assertEquals(ScanUiAction.SessionNameChanged("午前便"), actions.first())
            assertTrue(actions.contains(ScanUiAction.StartSession))
        }
    }

    @Test
    fun waitingStateShowsStepperInputPickerAndRereadAction() {
        val actions = mutableListOf<ScanUiAction>()
        val session = ScanSessionState(
            scan = ScanState.WaitingCode128(qrPayload),
            inputSource = InputSource.CAMERA,
        )
        composeRule.setContent {
            ScanScreen(
                state = ScanUiState.fromSession(
                    session = session,
                    sessionActive = true,
                    bluetoothReady = true,
                ),
                onAction = actions::add,
            )
        }

        composeRule.onNodeWithTag("scan_stepper").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_input_source_picker").assertIsDisplayed()
        composeRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getString(R.string.scan_wait_code128_title),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("scan_reread_qr")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertTrue(actions.contains(ScanUiAction.RereadQr))
        }
    }

    @Test
    fun resultShowsBothPartNumbersCountdownAndManualNext() {
        val actions = mutableListOf<ScanUiAction>()
        val session = ScanSessionState(
            scan = ScanState.Result(
                qrPayload = qrPayload,
                barcodePayload = barcodePayload,
                result = MatchResult.MATCH,
                matchedCount = 1,
            ),
            autoAdvanceEnabled = true,
            autoAdvanceSecondsRemaining = 3,
        )
        composeRule.setContent {
            ScanScreen(
                ScanUiState.fromSession(session, sessionActive = true),
                actions::add,
            )
        }

        composeRule.onNodeWithTag("scan_result_card").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_result_qr_part").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_result_barcode_part").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_countdown").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_manual_next").performClick()

        composeRule.runOnIdle {
            assertTrue(actions.contains(ScanUiAction.ManualNext))
        }
    }

    @Test
    fun duplicateResultExplainsThatBoxWasNotCountedAndDoesNotCountdown() {
        val session = ScanSessionState(
            scan = ScanState.Result(
                qrPayload = qrPayload,
                barcodePayload = barcodePayload,
                result = MatchResult.DUPLICATE,
                matchedCount = 1,
            ),
            autoAdvanceEnabled = true,
        )
        composeRule.setContent {
            ScanScreen(
                ScanUiState.fromSession(session, sessionActive = true),
                onAction = {},
            )
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(
            context.getString(R.string.scan_status_duplicate),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.scan_result_duplicate_description),
        ).assertIsDisplayed()
        composeRule.onAllNodesWithTag("scan_countdown").assertCountEquals(0)
    }

    @Test
    fun debugDemoToolsRequireExplicitOptIn() {
        val hiddenActions = mutableListOf<ScanUiAction>()
        val debugState = ScanUiState(
            sessionActive = true,
            session = ScanSessionState(scan = ScanState.WaitingQr()),
            debugDemoEnabled = true,
        )
        val showDebugTools = mutableStateOf(false)
        composeRule.setContent {
            ScanScreen(
                debugState,
                hiddenActions::add,
                showDebugDemoTools = showDebugTools.value,
            )
        }
        composeRule.onAllNodesWithTag("scan_debug_demo_tools").assertCountEquals(0)

        composeRule.runOnIdle { showDebugTools.value = true }
        composeRule.onNodeWithTag("scan_debug_demo_tools").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("scan_demo_match").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertTrue(hiddenActions.contains(ScanUiAction.DemoMatch))
        }
    }

    @Test
    fun languageOverrideRendersEnglishAndRecomposesInJapanese() {
        val language = mutableStateOf(AppLanguage.ENGLISH)
        composeRule.setContent {
            ScanScreen(
                state = ScanUiState(),
                onAction = {},
                language = language.value,
            )
        }

        composeRule.onNodeWithText("Start a comparison").assertIsDisplayed()

        composeRule.runOnIdle { language.value = AppLanguage.JAPANESE }

        composeRule.onNodeWithText("照合を開始").assertIsDisplayed()
        composeRule.onAllNodesWithText("Start a comparison").assertCountEquals(0)
    }
}
