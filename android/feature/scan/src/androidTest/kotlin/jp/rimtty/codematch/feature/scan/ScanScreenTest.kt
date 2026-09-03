package jp.rimtty.codematch.feature.scan

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScannerIssue
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
    fun dynamicScanGuidanceAndResultUsePoliteLiveRegions() {
        val state = mutableStateOf(
            ScanUiState.fromSession(
                session = ScanSessionState(scan = ScanState.WaitingQr()),
                sessionActive = true,
                message = "Ready to scan",
            ),
        )
        composeRule.setContent {
            ScanScreen(state = state.value, onAction = {})
        }

        composeRule.onNodeWithTag("scan_message")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

        composeRule.runOnIdle {
            state.value = ScanUiState.fromSession(
                session = ScanSessionState(
                    scan = ScanState.Result(
                        qrPayload = "QR",
                        barcodePayload = "BARCODE",
                        result = MatchResult.MISMATCH,
                    ),
                ),
                sessionActive = true,
            )
        }
        composeRule.onNodeWithTag("scan_result_card")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
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

    @Test
    fun bluetoothFallbackPreservesCurrentStepAndOffersRetryAndSettings() {
        val actions = mutableListOf<ScanUiAction>()
        var settingsOpenCount = 0
        val session = ScanSessionState(
            scan = ScanState.WaitingCode128(qrPayload),
            inputSource = InputSource.CAMERA,
        )
        composeRule.setContent {
            ScanScreen(
                state = ScanUiState.fromSession(
                    session = session,
                    sessionActive = true,
                    bluetoothIssue = ScannerIssue.POWERED_OFF,
                    bluetoothFallbackActive = true,
                ),
                onAction = actions::add,
                onOpenBluetoothSettings = { settingsOpenCount += 1 },
            )
        }

        composeRule.onNodeWithTag("scan_bluetooth_fallback")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getString(R.string.scan_bluetooth_fallback_powered_off),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("scan_bluetooth_reconnect").performClick()
        composeRule.onNodeWithTag("scan_bluetooth_open_settings").performClick()

        composeRule.runOnIdle {
            assertTrue(actions.contains(ScanUiAction.ReconnectBluetooth))
            assertEquals(1, settingsOpenCount)
        }
    }

    @Test
    fun bluetoothConfigurationStatusReturnsToSessionRestrictionAfterReady() {
        val language = mutableStateOf(AppLanguage.ENGLISH)
        val state = mutableStateOf(
            ScanUiState.fromSession(
                session = ScanSessionState(
                    scan = ScanState.WaitingQr(),
                    inputSource = InputSource.BLUETOOTH,
                ),
                sessionActive = true,
                bluetoothReady = true,
                bluetoothConfigurationState = ConfigurationState.Configuring,
            ),
        )
        composeRule.setContent {
            ScanScreen(
                state = state.value,
                onAction = {},
                language = language.value,
            )
        }

        composeRule.onNodeWithTag("scan_bluetooth_configuration_status")
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "Preparing scanner scan targets. Please wait.",
        ).assertIsDisplayed()

        composeRule.runOnIdle {
            state.value = state.value.copy(
                bluetoothConfigurationState = ConfigurationState.Ready,
            )
        }

        composeRule.onNodeWithText(
            "Scan targets: QR and Code 128 (comparison session)",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Scan a QR code").assertIsDisplayed()
        composeRule.onAllNodesWithText(
            "Preparing scanner scan targets. Please wait.",
        ).assertCountEquals(0)

        composeRule.runOnIdle {
            language.value = AppLanguage.JAPANESE
        }
        composeRule.onNodeWithText("読み取り対象：QR・Code 128（照合セッション）").assertIsDisplayed()
    }

    @Test
    fun bluetoothConfigurationFailureUsesGenericCopyWithoutRawReason() {
        val privateReason = "private adapter setting detail"
        composeRule.setContent {
            ScanScreen(
                state = ScanUiState.fromSession(
                    session = ScanSessionState(
                        scan = ScanState.WaitingQr(),
                        inputSource = InputSource.BLUETOOTH,
                    ),
                    sessionActive = true,
                    bluetoothReady = false,
                    bluetoothConfigurationState = ConfigurationState.Failed(privateReason),
                ),
                onAction = {},
                language = AppLanguage.ENGLISH,
            )
        }

        composeRule.onNodeWithText(
            "Scanner scan settings could not be verified. Continue with the camera.",
        ).assertIsDisplayed()
        composeRule.onAllNodesWithText(privateReason).assertCountEquals(0)
    }

    @Test
    fun configurationFallbackUsesTypedCopyWithoutRawReason() {
        val privateReason = "private adapter setting detail"
        composeRule.setContent {
            ScanScreen(
                state = ScanUiState.fromSession(
                    session = ScanSessionState(
                        scan = ScanState.WaitingCode128(qrPayload),
                        inputSource = InputSource.CAMERA,
                    ),
                    sessionActive = true,
                    bluetoothIssue = ScannerIssue.CONFIGURATION_FAILED,
                    bluetoothFallbackActive = true,
                ),
                onAction = {},
                language = AppLanguage.ENGLISH,
            )
        }

        composeRule.onNodeWithText(
            "Scanner scan settings could not be applied. Continue with the camera at the current step.",
        ).assertIsDisplayed()
        composeRule.onAllNodesWithText(privateReason).assertCountEquals(0)
    }
}
