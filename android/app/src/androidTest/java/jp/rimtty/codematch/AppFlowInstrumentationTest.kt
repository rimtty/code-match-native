package jp.rimtty.codematch

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import jp.rimtty.codematch.di.DebugAppTestEntryPoint
import jp.rimtty.codematch.feature.history.HistoryTestTags
import jp.rimtty.codematch.feature.settings.SettingsTestTags
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.AppSettings
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.fake.FakeExternalScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Application-level evidence for flows that cross navigation, ViewModels,
 * repositories, and the debug Fake scanner.
 *
 * The Fake is reached only through [DebugAppTestEntryPoint], which exists in
 * the debug source set. Payloads are injected on the UI thread and are never
 * written to logs or diagnostics. This keeps the tests deterministic without
 * making a claim about real camera frames or a production BLE adapter.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dependencies: DebugAppTestEntryPoint
        get() = EntryPointAccessors.fromApplication(
            targetContext.applicationContext,
            DebugAppTestEntryPoint::class.java,
        )

    private val fakeScanner: FakeExternalScanner
        get() = dependencies.externalScanner() as FakeExternalScanner

    private fun onNodeWithTag(tag: String) = composeRule.onNodeWithTag(tag)

    private fun onAllNodesWithTag(tag: String) = composeRule.onAllNodesWithTag(tag)

    private fun onNodeWithText(text: String) = composeRule.onNodeWithText(text)

    private fun onAllNodesWithText(text: String) = composeRule.onAllNodesWithText(text)

    @Before
    fun resetApplicationState() {
        clearRepositories()
        resetFakeScanner()
        resetApplicationLocale()

        // Navigation and saveable destination state are intentionally tested
        // elsewhere. Recreate after cleanup so this class starts with the
        // current persisted defaults even when another test left a route or
        // locale in the retained Activity state.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @After
    fun restoreApplicationState() {
        // Leave the shared test process in the product defaults for the other
        // connected tests. The next test also performs the same reset before
        // its Activity is recreated.
        clearRepositories()
        resetFakeScanner()
        resetApplicationLocale()
    }

    @Test
    fun fakeScannerConnectsCameraSwitchRejectsReverseOrderAndRecordsTwoMatches() {
        openDestination(R.string.destination_settings)

        // Settings uses the same app-owned Fake instance that ScanViewModel
        // observes. Discovery and connection therefore exercise the real app
        // graph, not a feature-only state fixture.
        openGuideAtFirstStep()
        repeat(3) {
            onNodeWithTag(SettingsTestTags.SETUP_NEXT)
                .performScrollTo()
                .performClick()
        }
        waitForTag(SettingsTestTags.SCANNER_SECTION)
        onNodeWithTag(SettingsTestTags.DISCOVERY)
            .performScrollTo()
            .performClick()
        waitForTag(SettingsTestTags.DEVICE_ROW)
        onNodeWithTag(SettingsTestTags.DEVICE_ROW)
            .performScrollTo()
            .performClick()
        onNodeWithTag(SettingsTestTags.CONNECT)
            .performScrollTo()
            .performClick()
        onNodeWithTag(SettingsTestTags.SCANNER_STATUS)
            .performScrollTo()
            .assertTextContains("接続済み")

        openDestination(R.string.destination_scan)
        onNodeWithTag("scan_session_name")
            .performTextInput("app flow")
        onNodeWithTag("scan_start_session").performClick()
        waitForTag("scan_input_source_picker")
        onNodeWithTag("scan_input_bluetooth").assertIsSelected()

        // Match the Swift UI flow: switch away from Bluetooth and back before
        // exercising the scanner callback order.
        onNodeWithTag("scan_input_camera").performClick()
        onNodeWithTag("scan_input_camera").assertIsSelected()
        onNodeWithTag("scan_camera_stage").assertIsDisplayed()
        onNodeWithTag("scan_input_bluetooth").performClick()
        onNodeWithTag("scan_input_bluetooth").assertIsSelected()

        emitBluetooth(
            ScanPayload.code128(
                value = barcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 1_000L,
            ),
        )
        waitForTag("scan_message")
        onNodeWithText("QRコードを読み取ってください").assertIsDisplayed()

        emitBluetooth(
            ScanPayload.qr(
                value = qrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 2_000L,
            ),
        )
        onNodeWithText("Code 128を読み取ってください").assertIsDisplayed()

        // The Fake duplicate gate is crossed because the repeated Bluetooth
        // callbacks are one second apart.
        emitBluetooth(
            ScanPayload.code128(
                value = barcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 3_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = barcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 4_000L,
            ),
        )
        waitForTag("scan_result_card")
        assertSessionCount(1)

        // Reset to the QR step and prove that a mismatching Code 128 is kept
        // visible but does not change the persisted count.
        onNodeWithTag("scan_manual_next").performClick()
        emitBluetooth(
            ScanPayload.qr(
                value = qrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 5_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = mismatchBarcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 6_000L,
            ),
        )
        waitForTag("scan_result_card")
        onNodeWithText("不一致").assertIsDisplayed()
        assertSessionCount(1)

        // A second manual next keeps the same session and allows a duplicate
        // box to be recorded. This covers the persisted count rather than
        // only the transient result card.
        onNodeWithTag("scan_manual_next").performClick()
        emitBluetooth(
            ScanPayload.qr(
                value = qrPayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 7_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = barcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 8_000L,
            ),
        )
        emitBluetooth(
            ScanPayload.code128(
                value = barcodePayload,
                source = InputSource.BLUETOOTH,
                timestampMillis = 9_000L,
            ),
        )
        waitForTag("scan_result_card")
        assertSessionCount(2)

        onNodeWithTag("scan_end_session").performClick()
        onNodeWithText(composeRule.activity.getString(R.string.end_session_confirm)).performClick()
        waitForTag("scan_start_session")

        openDestination(R.string.destination_history)
        waitForTag(HistoryTestTags.SESSION_ROW)
        onNodeWithTag(HistoryTestTags.SESSION_ROW).assertIsDisplayed()
    }

    @Test
    fun settingsGuideAndFakeScannerReconnectAreConnectedThroughTheApp() {
        openDestination(R.string.destination_settings)
        openGuideAtFirstStep()

        onNodeWithTag(
            SettingsTestTags.setupEnlarge(
                jp.rimtty.codematch.feature.settings.BluetoothScannerSetupCode.ENTER_SETUP,
            ),
        ).performScrollTo().performClick()
        waitForTag(SettingsTestTags.SETUP_FULLSCREEN_CLOSE)
        onNodeWithTag(SettingsTestTags.SETUP_FULLSCREEN_CLOSE).performClick()
        onNodeWithTag(SettingsTestTags.SETUP_NEXT).performScrollTo().performClick()
        waitForTag(SettingsTestTags.SETUP_GUIDE_STEP_2)
        onNodeWithTag(SettingsTestTags.SETUP_NEXT).performScrollTo().performClick()
        waitForTag(SettingsTestTags.SETUP_GUIDE_STEP_3)
        onNodeWithTag(SettingsTestTags.SETUP_NEXT).performScrollTo().performClick()

        waitForTag(SettingsTestTags.SCANNER_SECTION)
        onNodeWithTag(SettingsTestTags.DISCOVERY)
            .performScrollTo()
            .performClick()
        waitForTag(SettingsTestTags.DEVICE_ROW)
        onNodeWithTag(SettingsTestTags.DEVICE_ROW)
            .performScrollTo()
            .performClick()
        onNodeWithTag(SettingsTestTags.CONNECT)
            .performScrollTo()
            .performClick()
        onNodeWithTag(SettingsTestTags.SCANNER_STATUS)
            .performScrollTo()
            .assertTextContains("接続済み")

        onNodeWithTag(SettingsTestTags.DISCONNECT)
            .performScrollTo()
            .performClick()
        waitForTag(SettingsTestTags.RECONNECT)
        onNodeWithTag(SettingsTestTags.RECONNECT)
            .performScrollTo()
            .performClick()
        onNodeWithTag(SettingsTestTags.SCANNER_STATUS)
            .performScrollTo()
            .assertTextContains("接続済み")
    }

    @Test
    fun languageSelectionPersistsAcrossActivityRecreation() {
        openDestination(R.string.destination_settings)
        onNodeWithTag(SettingsTestTags.LANGUAGE)
            .performScrollTo()
        onAllNodesWithTag(SettingsTestTags.LANGUAGE_CHOICE)
            .get(1)
            .performClick()

        waitForText("Display language")
        onNodeWithText("Display language").performScrollTo().assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        openDestination(R.string.destination_settings)
        onNodeWithTag(SettingsTestTags.LANGUAGE).performScrollTo()
        onAllNodesWithTag(SettingsTestTags.LANGUAGE_CHOICE).get(1).assertIsSelected()
        onNodeWithText("Display language").assertIsDisplayed()

    }

    @Test
    fun enabledOneSecondAutoAdvanceMovesFromResultToNextQrInRealTime() {
        openDestination(R.string.destination_settings)
        onNodeWithTag(SettingsTestTags.AUTO_ADVANCE_SWITCH)
            .performScrollTo()
            .performClick()
        onNodeWithTag(SettingsTestTags.AUTO_ADVANCE_SWITCH).assertIsOn()
        onAllNodesWithTag(SettingsTestTags.DELAY_CHOICE)
            .get(0)
            .performScrollTo()
            .performClick()

        openDestination(R.string.destination_scan)
        onNodeWithTag("scan_start_session").performClick()
        waitForTag("scan_waiting_card")

        emitCamera(
            ScanPayload.qr(
                value = qrPayload,
                source = InputSource.CAMERA,
                timestampMillis = 100_000L,
            ),
        )
        emitCamera(
            ScanPayload.code128(
                value = barcodePayload,
                source = InputSource.CAMERA,
                timestampMillis = 101_000L,
            ),
        )
        emitCamera(
            ScanPayload.code128(
                value = barcodePayload,
                source = InputSource.CAMERA,
                timestampMillis = 102_000L,
            ),
        )
        waitForTag("scan_result_card")
        waitForTag("scan_countdown")
        assertSessionCount(1)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("scan_countdown").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithTag("scan_waiting_card").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("QRコードを読み取ってください").assertIsDisplayed()
        assertSessionCount(1)
    }

    private fun openDestination(destinationRes: Int) {
        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(destinationRes),
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitForIdle()
    }

    private fun normalizeGuideToFirstStep() {
        waitForTag(SettingsTestTags.SETUP_GUIDE)
        while (composeRule.onAllNodesWithTag(SettingsTestTags.SETUP_GUIDE_STEP_1)
                .fetchSemanticsNodes().isEmpty()
        ) {
            onNodeWithTag(SettingsTestTags.SETUP_PREVIOUS)
                .performScrollTo()
                .performClick()
            composeRule.waitForIdle()
        }
    }

    private fun openGuideAtFirstStep() {
        if (composeRule.onAllNodesWithTag(SettingsTestTags.SETUP_GUIDE)
                .fetchSemanticsNodes().isEmpty()
        ) {
            onNodeWithTag(SettingsTestTags.SETUP_GUIDE_OPEN)
                .performScrollTo()
                .performClick()
        }
        normalizeGuideToFirstStep()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            } catch (_: IllegalStateException) {
                // Per-app locale changes recreate MainActivity asynchronously.
                // Treat the short no-hierarchy window as not ready yet.
                false
            }
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            } catch (_: IllegalStateException) {
                // Per-app locale changes recreate MainActivity asynchronously.
                false
            }
        }
    }

    private fun assertSessionCount(expected: Int) {
        onNodeWithTag("scan_session_count")
            .assertTextEquals("${expected}件照合済み")
    }

    private fun emitBluetooth(payload: ScanPayload) = emit(payload)

    private fun emitCamera(payload: ScanPayload) = emit(payload)

    private fun emit(payload: ScanPayload) {
        var delivered = false
        composeRule.runOnIdle {
            delivered = fakeScanner.emitPayload(payload)
        }
        assertTrue("deterministic scan callback was not delivered", delivered)
        composeRule.waitForIdle()
    }

    private fun clearRepositories() {
        val history = dependencies.historyRepository()
        val settings = dependencies.settingsRepository()
        runBlocking {
            val ids = history.sessions.first().map { it.id }
            history.deleteSessions(ids)
            settings.update { AppSettings() }
        }
    }

    private fun resetFakeScanner() {
        // connect -> disconnect clears the Fake's duplicate gate even when a
        // preceding test used camera-source callbacks without a BLE session.
        composeRule.runOnIdle {
            fakeScanner.connect(fakeScanner.defaultDevice)
            fakeScanner.disconnect()
            fakeScanner.clearDiagnostics()
        }
        composeRule.waitForIdle()
    }

    private fun resetApplicationLocale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                targetContext.getSystemService(LocaleManager::class.java).applicationLocales =
                    LocaleList.forLanguageTags(AppLanguage.JAPANESE.code)
            } else {
                AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(
                        AppLanguage.JAPANESE.code,
                    ),
                )
            }
        }
    }

    private companion object {
        const val qrPayload =
            "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
        const val barcodePayload = "BCJH-52-81GG@1N5X0C"
        const val mismatchBarcodePayload = "BCJH-55-81GG@1KVV0C"
    }
}
