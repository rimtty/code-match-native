package jp.rimtty.codematch

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.Process
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import jp.rimtty.codematch.core.data.HistoryRepository
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.AppSettings
import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.FailureSound
import jp.rimtty.codematch.core.model.MatchSession
import jp.rimtty.codematch.core.model.ScanCheckpointPhase
import jp.rimtty.codematch.core.model.ScanSessionCheckpoint
import jp.rimtty.codematch.core.model.SuccessSound
import jp.rimtty.codematch.di.DebugAppTestEntryPoint
import jp.rimtty.codematch.feature.scan.ScanPhase
import jp.rimtty.codematch.feature.scan.ScanUiAction
import jp.rimtty.codematch.feedback.FeedbackPlayer
import jp.rimtty.codematch.scan.ScanViewModel
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host-coordinated process-recreation evidence for the isolated recovery APK.
 *
 * The host invokes [seedCheckpoint] and [verifyCheckpoint] separately. It
 * starts the app, records its PID, force-stops only the isolated package, and
 * then invokes the verifier with that old PID. Keeping the two calls separate
 * makes the OS process boundary part of the evidence instead of simulating a
 * configuration change inside one process.
 *
 * This source is added only when `codematchProcessRecoveryTests=true`. The
 * package guard is intentionally the first operation in each test, and the
 * empty Compose rule does not launch an Activity. No camera/Bluetooth adapter
 * is started; seed injects the existing synthetic fixtures through the public
 * ScanViewModel action, while verify observes the real MainActivity UI.
 */
@RunWith(AndroidJUnit4::class)
class ProcessRecoveryInstrumentationTest {
    /** Does not launch MainActivity; verify starts it only after all guards. */
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun seedCheckpoint() {
        assertRecoveryTarget()
        assertRecoveryProcess()
        val arguments = RecoveryArguments.read(requirePreviousPid = false)
        val markerPreferences = markerPreferences()
        check(!markerPreferences.contains(MARKER_RUN_ID)) {
            "A previous process-recovery marker is still present"
        }
        check(!markerPreferences.contains(MARKER_SESSION_ID)) {
            "A previous process-recovery session marker is still present"
        }

        val dependencies = dependencies()
        val history = dependencies.historyRepository()
        // Never reset a user's or a previous run's active state. The isolated
        // package is expected to be clean before seed; an active session is a
        // safety failure, not an invitation to delete data.
        assertNull("seed requires no pre-existing active session", runBlocking {
            history.activeSession.first()
        })

        val settings = dependencies.settingsRepository()
        runBlocking { settings.update { EXPECTED_SETTINGS } }
        // DataStore is the product-owned source of truth, but Android's
        // per-app locale is the resource configuration used by a fresh
        // Application/Activity. Seed both through the same framework boundary
        // used by the in-app language setting so verify proves the full
        // startup path, not only a stored enum value.
        setEnglishFrameworkLocale()

        val owner = TestViewModelOwner()
        try {
            var createdViewModel: ScanViewModel? = null
            runOnMain { createdViewModel = createScanViewModel(dependencies, owner) }
            val viewModel = requireNotNull(createdViewModel)
            val sessionName = markerSessionName(arguments.runId)
            awaitViewModelInitialization(viewModel)
            dispatch(viewModel, ScanUiAction.SessionNameChanged(sessionName))
            dispatch(viewModel, ScanUiAction.StartSession)

            awaitSessionStarted(viewModel, sessionName)
            val session = awaitActiveSession(history, sessionName)
            writeMarker(markerPreferences, arguments.runId, session.id)

            when (arguments.recoveryCase) {
                RecoveryCase.WAITING_QR -> {
                    awaitState(viewModel) { state ->
                        state.phase == ScanPhase.WAITING_QR &&
                            state.qrPayload == null && state.matchedCount == 0
                    }
                }

                RecoveryCase.WAITING_CODE128 -> {
                    submitQr(viewModel)
                    awaitState(viewModel) { state ->
                        state.phase == ScanPhase.WAITING_CODE_128 &&
                            state.qrPayload == QR_PAYLOAD &&
                            state.matchedCount == 0
                    }
                }

                RecoveryCase.RESULT_MATCH -> {
                    submitQr(viewModel)
                    awaitState(viewModel) { state ->
                        state.phase == ScanPhase.WAITING_CODE_128
                    }
                    submitCameraCode128(viewModel, timestampMillis = 2_000L)
                    // Camera Code 128 input uses the production two-frame
                    // stabilizer. Both values are synthetic fixture data.
                    submitCameraCode128(viewModel, timestampMillis = 2_250L)
                    awaitState(viewModel) { state ->
                        state.phase == ScanPhase.RESULT &&
                            state.result == jp.rimtty.codematch.core.model.MatchResult.MATCH &&
                            state.qrPayload == QR_PAYLOAD &&
                            state.barcodePayload == BARCODE_PAYLOAD &&
                            state.matchedCount == 1
                    }
                }
            }

            val checkpoint = awaitCheckpoint(
                history = history,
                sessionId = session.id,
                recoveryCase = arguments.recoveryCase,
            )
            assertCheckpoint(arguments.recoveryCase, session.id, checkpoint)
            assertSessionEntryCount(history, session.id, expected = if (
                arguments.recoveryCase == RecoveryCase.RESULT_MATCH
            ) 1 else 0)
            assertSettings(settings)
        } finally {
            // ViewModelStore.clear cancels its persistence/counter jobs before
            // the host force-stops this process. No repository cleanup belongs
            // in seed; verify owns cleanup of this exact marked session.
            runOnMain { owner.viewModelStore.clear() }
        }
    }

    @Test
    fun verifyCheckpoint() {
        assertRecoveryTarget()
        assertRecoveryProcess()
        val arguments = RecoveryArguments.read(requirePreviousPid = true)
        val marker = requireMarker(markerPreferences(), arguments.runId)
        val dependencies = dependencies()
        val history = dependencies.historyRepository()
        var ownsMarkedSession = false
        var scenario: ActivityScenario<MainActivity>? = null

        try {
            val active = runBlocking { history.activeSession.first() }
            assertNotNull("the marked session must survive process recreation", active)
            assertEquals(marker.sessionId, active?.id)
            ownsMarkedSession = active?.id == marker.sessionId

            val checkpoint = awaitCheckpoint(
                history = history,
                sessionId = marker.sessionId,
                recoveryCase = arguments.recoveryCase,
            )
            assertCheckpoint(arguments.recoveryCase, marker.sessionId, checkpoint)
            assertSessionEntryCount(
                history,
                marker.sessionId,
                expected = if (arguments.recoveryCase == RecoveryCase.RESULT_MATCH) 1 else 0,
            )
            assertSettings(dependencies.settingsRepository())
            assertEnglishFrameworkLocale()

            // This is deliberately after package, marker, Room, and DataStore
            // guards. ActivityScenario therefore cannot accidentally touch the
            // ordinary jp.rimtty.codematch installation on a bad invocation.
            scenario = ActivityScenario.launch(MainActivity::class.java)
            composeRule.waitForIdle()
            assertRestoredUi(arguments.recoveryCase, marker.sessionName)

            if (arguments.recoveryCase == RecoveryCase.RESULT_MATCH) {
                // Restored RESULT must not create a second history entry or
                // consume the enabled auto-advance timer. The checkpoint
                // intentionally restores no countdown, so remain on RESULT
                // beyond the persisted five-second delay and re-check Room.
                assertTrue(
                    composeRule.onAllNodesWithTag("scan_countdown")
                        .fetchSemanticsNodes()
                        .isEmpty(),
                )
                SystemClock.sleep(RESULT_SETTLE_MILLIS)
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("scan_result_card").assertIsDisplayed()
                composeRule.onNodeWithText("Match").assertIsDisplayed()
                assertSessionEntryCount(history, marker.sessionId, expected = 1)
            }
        } finally {
            scenario?.close()
            if (ownsMarkedSession) {
                // Delete only the UUID-marked session created by seed. Do not
                // clear shared data or touch a different active session.
                runBlocking { history.deleteSession(marker.sessionId) }
                markerPreferences().edit()
                    .remove(MARKER_RUN_ID)
                    .remove(MARKER_SESSION_ID)
                    .commit()
            }
        }
    }

    private fun assertRecoveryTarget() {
        val actualPackage = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .packageName
        assertEquals(RECOVERY_TARGET_PACKAGE, actualPackage)
        assertNotEquals(PRODUCT_TARGET_PACKAGE, actualPackage)
    }

    private fun assertRecoveryProcess() {
        assertEquals(RECOVERY_TARGET_PACKAGE, Application.getProcessName())
    }

    private fun dependencies(): DebugAppTestEntryPoint {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return EntryPointAccessors.fromApplication(
            context.applicationContext,
            DebugAppTestEntryPoint::class.java,
        )
    }

    private fun markerPreferences() = InstrumentationRegistry.getInstrumentation()
        .targetContext
        .getSharedPreferences(MARKER_PREFERENCES, Context.MODE_PRIVATE)

    private fun setEnglishFrameworkLocale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        instrumentation.runOnMainSync {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val localeManager = requireNotNull(
                    targetContext.getSystemService(LocaleManager::class.java),
                )
                localeManager.applicationLocales = LocaleList.forLanguageTags(
                    AppLanguage.ENGLISH.code,
                )
            } else {
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(AppLanguage.ENGLISH.code),
                )
            }
        }
        assertEnglishFrameworkLocale()
    }

    private fun writeMarker(
        preferences: android.content.SharedPreferences,
        runId: UUID,
        sessionId: String,
    ) {
        check(
            preferences.edit()
                .putString(MARKER_RUN_ID, runId.toString())
                .putString(MARKER_SESSION_ID, sessionId)
                .commit(),
        ) { "Could not persist the process-recovery marker" }
    }

    private fun requireMarker(
        preferences: android.content.SharedPreferences,
        runId: UUID,
    ): RecoveryMarker {
        val markerRunId = requireNotNull(preferences.getString(MARKER_RUN_ID, null)) {
            "The process-recovery run marker is missing"
        }
        val sessionId = requireNotNull(preferences.getString(MARKER_SESSION_ID, null)) {
            "The process-recovery session marker is missing"
        }
        assertEquals(runId.toString(), markerRunId)
        assertTrue(sessionId.isNotBlank())
        return RecoveryMarker(runId = runId, sessionId = sessionId)
    }

    private fun createScanViewModel(
        dependencies: DebugAppTestEntryPoint,
        owner: TestViewModelOwner,
    ): ScanViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ScanViewModel::class.java)
                return ScanViewModel(
                    historyRepository = dependencies.historyRepository(),
                    settingsRepository = dependencies.settingsRepository(),
                    scanner = dependencies.externalScanner(),
                    feedbackPlayer = FeedbackPlayer(context),
                ) as T
            }
        }
        return ViewModelProvider(owner, factory)[ScanViewModel::class.java]
    }

    private fun dispatch(viewModel: ScanViewModel, action: ScanUiAction) {
        runOnMain { viewModel.onAction(action) }
    }

    private fun runOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun awaitViewModelInitialization(viewModel: ScanViewModel) {
        try {
            awaitState(viewModel) { state ->
                !state.sessionActive &&
                    state.phase == ScanPhase.IDLE &&
                    state.autoAdvanceEnabled == EXPECTED_SETTINGS.autoAdvanceEnabled &&
                    state.autoAdvanceDelay == EXPECTED_SETTINGS.autoAdvanceDelay
            }
        } catch (error: Throwable) {
            val state = viewModel.state.value
            throw AssertionError(
                "ScanViewModel initialization timed out: " +
                    "phase=${state.phase}, sessionActive=${state.sessionActive}, " +
                    "expectedSettings=" +
                    (state.autoAdvanceEnabled == EXPECTED_SETTINGS.autoAdvanceEnabled &&
                        state.autoAdvanceDelay == EXPECTED_SETTINGS.autoAdvanceDelay),
                error,
            )
        }
    }

    private fun awaitSessionStarted(viewModel: ScanViewModel, sessionName: String) {
        try {
            awaitState(viewModel) { state ->
                state.sessionActive && state.phase != ScanPhase.IDLE &&
                    state.sessionName == sessionName
            }
        } catch (error: Throwable) {
            val state = viewModel.state.value
            throw AssertionError(
                "Scan session start timed out: " +
                    "phase=${state.phase}, sessionActive=${state.sessionActive}, " +
                    "sessionNameMatches=${state.sessionName == sessionName}, " +
                    "sessionNameDraftMatches=${state.sessionNameDraft == sessionName}",
                error,
            )
        }
    }

    private fun submitQr(viewModel: ScanViewModel) {
        dispatch(
            viewModel,
            ScanUiAction.ScanReceived(
                ScanPayload.qr(
                    value = QR_PAYLOAD,
                    source = InputSource.CAMERA,
                    timestampMillis = 1_000L,
                ),
            ),
        )
    }

    private fun submitCameraCode128(viewModel: ScanViewModel, timestampMillis: Long) {
        dispatch(
            viewModel,
            ScanUiAction.ScanReceived(
                ScanPayload.code128(
                    value = BARCODE_PAYLOAD,
                    source = InputSource.CAMERA,
                    timestampMillis = timestampMillis,
                ),
            ),
        )
    }

    private fun assertRestoredUi(recoveryCase: RecoveryCase, sessionName: String) {
        composeRule.waitUntil(20_000L) {
            try {
                when (recoveryCase) {
                    RecoveryCase.RESULT_MATCH ->
                        composeRule.onAllNodesWithText("Match", substring = false)
                            .fetchSemanticsNodes().isNotEmpty()

                    RecoveryCase.WAITING_QR,
                    RecoveryCase.WAITING_CODE128,
                    -> composeRule.onAllNodesWithText(
                        when (recoveryCase) {
                            RecoveryCase.WAITING_QR -> "Scan a QR code"
                            RecoveryCase.WAITING_CODE128 -> "Scan a Code 128 barcode"
                            RecoveryCase.RESULT_MATCH -> error("unreachable")
                        },
                        substring = false,
                    ).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (_: IllegalStateException) {
                // The per-app English locale can recreate MainActivity during
                // the first startup pass. Wait for the fresh hierarchy.
                false
            }
        }

        composeRule.onNodeWithTag("scan_screen_title")
            .assertTextEquals(sessionName)
        when (recoveryCase) {
            RecoveryCase.WAITING_QR -> {
                composeRule.onNodeWithText("Scan a QR code").assertIsDisplayed()
                composeRule.onNodeWithTag("scan_session_count")
                    .assertTextEquals("0 matches checked")
            }

            RecoveryCase.WAITING_CODE128 -> {
                composeRule.onNodeWithText("Scan a Code 128 barcode").assertIsDisplayed()
                composeRule.onNodeWithTag("scan_session_count")
                    .assertTextEquals("0 matches checked")
            }

            RecoveryCase.RESULT_MATCH -> {
                composeRule.onNodeWithText("Match").assertIsDisplayed()
                composeRule.onNodeWithTag("scan_result_qr_part")
                    .assertContentDescriptionContains("BCJH-52-81GG", substring = true)
                composeRule.onNodeWithTag("scan_result_barcode_part")
                    .assertContentDescriptionContains("BCJH-52-81GG", substring = true)
                composeRule.onNodeWithTag("scan_session_match_count")
                    .assertTextEquals("1")
            }
        }
    }

    private fun assertCheckpoint(
        recoveryCase: RecoveryCase,
        sessionId: String,
        checkpoint: ScanSessionCheckpoint,
    ) {
        assertEquals(sessionId, checkpoint.sessionId)
        assertEquals(InputSource.CAMERA.name, checkpoint.inputSource.name)
        assertFalse(checkpoint.cameraWasSelectedByUser)
        when (recoveryCase) {
            RecoveryCase.WAITING_QR -> {
                assertEquals(ScanCheckpointPhase.WAITING_QR, checkpoint.phase)
                assertNull(checkpoint.qrPayload)
                assertNull(checkpoint.barcodePayload)
                assertNull(checkpoint.result)
                assertEquals(0, checkpoint.matchedCount)
            }

            RecoveryCase.WAITING_CODE128 -> {
                assertEquals(ScanCheckpointPhase.WAITING_CODE_128, checkpoint.phase)
                assertEquals(QR_PAYLOAD, checkpoint.qrPayload)
                assertNull(checkpoint.barcodePayload)
                assertNull(checkpoint.result)
                assertEquals(0, checkpoint.matchedCount)
            }

            RecoveryCase.RESULT_MATCH -> {
                assertEquals(ScanCheckpointPhase.RESULT, checkpoint.phase)
                assertEquals(QR_PAYLOAD, checkpoint.qrPayload)
                assertEquals(BARCODE_PAYLOAD, checkpoint.barcodePayload)
                assertEquals(
                    jp.rimtty.codematch.core.model.MatchResult.MATCH,
                    checkpoint.result,
                )
                assertEquals(1, checkpoint.matchedCount)
            }
        }
    }

    private fun assertSessionEntryCount(
        history: HistoryRepository,
        sessionId: String,
        expected: Int,
    ) {
        val session = runBlocking { history.getSession(sessionId) }
        assertNotNull(session)
        assertEquals(expected, session?.entries?.size)
    }

    private fun assertSettings(settings: jp.rimtty.codematch.core.data.SettingsRepository) {
        val actual = runBlocking { settings.settings.first() }
        assertEquals(EXPECTED_SETTINGS.autoAdvanceEnabled, actual.autoAdvanceEnabled)
        assertEquals(EXPECTED_SETTINGS.autoAdvanceDelaySeconds, actual.autoAdvanceDelaySeconds)
        assertEquals(EXPECTED_SETTINGS.feedbackVolume, actual.feedbackVolume, 0.0001f)
        assertEquals(EXPECTED_SETTINGS.successSound, actual.successSound)
        assertEquals(EXPECTED_SETTINGS.failureSound, actual.failureSound)
        assertEquals(EXPECTED_SETTINGS.language, actual.language)
    }

    private fun assertEnglishFrameworkLocale() {
        val languageTag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireNotNull(
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getSystemService(LocaleManager::class.java),
            ).applicationLocales.toLanguageTags()
        } else {
            androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
        }
        assertEquals(AppLanguage.ENGLISH.code, languageTag)
    }

    private fun awaitState(
        viewModel: ScanViewModel,
        predicate: (jp.rimtty.codematch.feature.scan.ScanUiState) -> Boolean,
    ) = runBlocking {
        withTimeout(CHECKPOINT_TIMEOUT_MILLIS) {
            viewModel.state.first(predicate)
        }
    }

    private fun awaitActiveSession(
        history: HistoryRepository,
        sessionName: String,
    ): MatchSession = runBlocking {
        withTimeout(CHECKPOINT_TIMEOUT_MILLIS) {
            requireNotNull(history.activeSession.first { it?.name == sessionName })
        }
    }

    private fun awaitCheckpoint(
        history: HistoryRepository,
        sessionId: String,
        recoveryCase: RecoveryCase,
    ): ScanSessionCheckpoint = runBlocking {
        withTimeout(CHECKPOINT_TIMEOUT_MILLIS) {
            var matchingCheckpoint: ScanSessionCheckpoint? = null
            while (matchingCheckpoint == null) {
                val candidate = history.getScanCheckpoint(sessionId)
                if (candidate != null &&
                    checkpointMatchesCase(sessionId, recoveryCase, candidate)
                ) {
                    matchingCheckpoint = candidate
                } else {
                    delay(25L)
                }
            }
            requireNotNull(matchingCheckpoint)
        }
    }

    private fun checkpointMatchesCase(
        sessionId: String,
        recoveryCase: RecoveryCase,
        checkpoint: ScanSessionCheckpoint,
    ): Boolean {
        if (checkpoint.sessionId != sessionId ||
            checkpoint.inputSource != jp.rimtty.codematch.core.model.ScanCheckpointInputSource.CAMERA ||
            checkpoint.cameraWasSelectedByUser
        ) {
            return false
        }
        return when (recoveryCase) {
            RecoveryCase.WAITING_QR ->
                checkpoint.phase == ScanCheckpointPhase.WAITING_QR &&
                    checkpoint.qrPayload == null &&
                    checkpoint.barcodePayload == null &&
                    checkpoint.result == null &&
                    checkpoint.matchedCount == 0

            RecoveryCase.WAITING_CODE128 ->
                checkpoint.phase == ScanCheckpointPhase.WAITING_CODE_128 &&
                    checkpoint.qrPayload == QR_PAYLOAD &&
                    checkpoint.barcodePayload == null &&
                    checkpoint.result == null &&
                    checkpoint.matchedCount == 0

            RecoveryCase.RESULT_MATCH ->
                checkpoint.phase == ScanCheckpointPhase.RESULT &&
                    checkpoint.qrPayload == QR_PAYLOAD &&
                    checkpoint.barcodePayload == BARCODE_PAYLOAD &&
                    checkpoint.result == jp.rimtty.codematch.core.model.MatchResult.MATCH &&
                    checkpoint.matchedCount == 1
        }
    }

    private fun markerSessionName(runId: UUID): String = "process-recovery-${runId}"

    private class TestViewModelOwner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    private data class RecoveryMarker(
        val runId: UUID,
        val sessionId: String,
    ) {
        val sessionName: String get() = "process-recovery-$runId"
    }

    private enum class RecoveryCase {
        WAITING_QR,
        WAITING_CODE128,
        RESULT_MATCH,
        ;

        companion object {
            fun parse(raw: String): RecoveryCase = when (raw) {
                "waiting_qr" -> WAITING_QR
                "waiting_code128" -> WAITING_CODE128
                "result_match" -> RESULT_MATCH
                else -> error("Unsupported recoveryCase")
            }
        }
    }

    private data class RecoveryArguments(
        val recoveryCase: RecoveryCase,
        val runId: UUID,
        val previousPid: Int?,
    ) {
        companion object {
            fun read(requirePreviousPid: Boolean): RecoveryArguments {
                val bundle: Bundle = InstrumentationRegistry.getArguments()
                val recoveryCase = RecoveryCase.parse(
                    requireNotNull(bundle.getString("recoveryCase")) {
                        "recoveryCase is required"
                    },
                )
                val runIdText = requireNotNull(bundle.getString("recoveryRunId")) {
                    "recoveryRunId is required"
                }
                val runId = runCatching { UUID.fromString(runIdText) }
                    .getOrElse { error("recoveryRunId must be a UUID") }
                val previousPid = bundle.getString("previousPid")?.let {
                    it.toIntOrNull().also { pid ->
                        require(pid != null && pid > 0) { "previousPid must be positive" }
                    }
                }
                if (requirePreviousPid) {
                    require(previousPid != null) { "previousPid is required for verify" }
                    require(previousPid != Process.myPid()) {
                        "previousPid must belong to the process killed by the host"
                    }
                }
                return RecoveryArguments(recoveryCase, runId, previousPid)
            }
        }
    }

    companion object {
        private const val RECOVERY_TARGET_PACKAGE = "jp.rimtty.codematch.recoverytest"
        private const val PRODUCT_TARGET_PACKAGE = "jp.rimtty.codematch"
        private const val MARKER_PREFERENCES = "process_recovery_test_marker"
        private const val MARKER_RUN_ID = "runId"
        private const val MARKER_SESSION_ID = "sessionId"
        private const val CHECKPOINT_TIMEOUT_MILLIS = 15_000L
        private const val RESULT_SETTLE_MILLIS = 5_500L

        private val EXPECTED_SETTINGS = AppSettings(
            autoAdvanceEnabled = true,
            autoAdvanceDelaySeconds = AutoAdvanceDelay.FIVE_SECONDS.seconds,
            feedbackVolume = 0.37f,
            successSound = SuccessSound.CHIME,
            failureSound = FailureSound.DESCEND,
            language = AppLanguage.ENGLISH,
        )

        private const val QR_PAYLOAD =
            "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
        private const val BARCODE_PAYLOAD = "BCJH-52-81GG@1N5X0C"
    }
}
