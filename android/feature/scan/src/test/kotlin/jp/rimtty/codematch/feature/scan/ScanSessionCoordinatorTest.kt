package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.core.model.ScanCheckpointInputSource
import jp.rimtty.codematch.core.model.ScanCheckpointPhase
import jp.rimtty.codematch.core.model.ScanSessionCheckpoint
import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice
import jp.rimtty.codematch.scanner.api.ScannerIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSessionCoordinatorTest {
    private val qrPayload =
        "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
    private val barcodePayload = "BCJH-52-81GG@1N5X0C"

    @Test
    fun readyBluetoothIsSelectedAtSessionStart() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)

        coordinator.startSession()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
    }

    @Test
    fun baselineReadyBluetoothStartsRestrictionBeforePayloadReady() {
        val scanner = TestScanner().apply {
            requireExpectedFormatForPayloadReadiness = true
            markReady()
        }
        val coordinator = ScanSessionCoordinator(scanner)

        assertTrue(scanner.isReadyToStartSession)
        assertTrue(!scanner.isReadyForScanning)

        coordinator.startSession()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
        assertTrue(scanner.isReadyForScanning)
    }

    @Test
    fun coordinatorUsesFanOutWithoutReplacingAnExistingScannerObserver() {
        val scanner = TestScanner().apply { markReady() }
        val legacyStates = mutableListOf<ConnectionState>()
        val settingsStates = mutableListOf<ConnectionState>()
        scanner.listener = object : ExternalScannerListener {
            override fun onConnectionStateChanged(state: ConnectionState) {
                legacyStates += state
            }
        }
        val settingsObserver = object : ExternalScannerListener {
            override fun onConnectionStateChanged(state: ConnectionState) {
                settingsStates += state
            }
        }
        assertTrue(scanner.addListener(settingsObserver))

        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()
        scanner.markDisconnected()

        assertTrue(legacyStates.contains(ConnectionState.Idle))
        assertTrue(settingsStates.contains(ConnectionState.Idle))
        assertEquals(InputSource.CAMERA, coordinator.inputSource)

        coordinator.dispose()
        assertTrue(scanner.removeListener(settingsObserver))
    }

    @Test
    fun explicitCameraChoiceWinsOverLaterReadyCallbacks() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()

        assertTrue(coordinator.selectInputSource(InputSource.CAMERA))
        scanner.markReady()

        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertTrue(coordinator.cameraWasSelectedByUser)
    }

    @Test
    fun disconnectFallsBackToCameraWithoutDiscardingCurrentQrStep() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        var fallbackRequests = 0
        coordinator.onBluetoothFallback = { fallbackRequests++ }
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH))
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)

        scanner.markDisconnected()

        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)
        assertEquals(qrPayload, coordinator.state.qrPayload)
        assertEquals(1, fallbackRequests)
    }

    @Test
    fun configurationFailureKeepsTypedIssueWhenBaselineRestoreMakesScannerReady() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        var fallbackIssue = ScannerIssue.NONE
        var fallbackRequests = 0
        coordinator.onBluetoothFallbackIssue = { fallbackIssue = it }
        coordinator.onBluetoothFallback = { fallbackRequests++ }
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH))

        scanner.markConfigurationFailed("scanner settings rejected")

        assertEquals(ScannerIssue.CONFIGURATION_FAILED, fallbackIssue)
        assertEquals(1, fallbackRequests)
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)
        assertEquals(qrPayload, coordinator.state.qrPayload)
        // The adapter baseline is ready again, but an unverified session must
        // not be promoted back to Bluetooth until an explicit reconnect.
        assertEquals(ConfigurationState.Ready, scanner.configurationState)
    }

    @Test
    fun explicitAsynchronousReconnectAllowsLaterReadyPromotion() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH))
        scanner.markConfigurationFailed("scanner settings rejected")
        scanner.reconnectSynchronously = false

        assertTrue(coordinator.reconnectKnownDevice())
        assertEquals(InputSource.CAMERA, coordinator.inputSource)

        scanner.markReady()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)
        assertEquals(qrPayload, coordinator.state.qrPayload)
    }

    @Test
    fun manualCameraSelectionDoesNotRequestAutomaticFallbackStart() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        var fallbackRequests = 0
        coordinator.onBluetoothFallback = { fallbackRequests++ }
        coordinator.startSession()

        assertTrue(coordinator.selectInputSource(InputSource.CAMERA))

        assertEquals(0, fallbackRequests)
    }

    @Test
    fun cameraCode128UsesStrictStabilizationBeforeDispatch() {
        val coordinator = ScanSessionCoordinator(TestScanner())
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, timestampMillis = 0L))

        assertNull(
            coordinator.submitScanPayload(
                ScanPayload.code128(barcodePayload, timestampMillis = 250L),
            ),
        )
        val accepted = coordinator.submitScanPayload(
            ScanPayload.code128(barcodePayload, timestampMillis = 1_749L),
        )

        assertEquals(ScanPhase.RESULT, accepted?.state?.phase)
    }

    @Test
    fun backgroundStopsScannerAndForegroundResumesCurrentFormat() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()

        coordinator.onBackgrounded()
        assertTrue(coordinator.isBackgrounded)
        assertNull(scanner.expectedFormat)
        assertEquals(ScanPhase.WAITING_QR, coordinator.state.phase)

        coordinator.onForegrounded()
        assertTrue(!coordinator.isBackgrounded)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
        assertEquals(ScanPhase.WAITING_QR, coordinator.state.phase)
    }

    @Test
    fun delayedCameraPayloadIsIgnoredWhileBackgrounded() {
        val coordinator = ScanSessionCoordinator(TestScanner())
        coordinator.startSession()
        coordinator.onBackgrounded()

        val result = coordinator.submitScanPayload(
            ScanPayload.qr(qrPayload, timestampMillis = 100L),
        )

        assertNull(result)
        assertEquals(ScanPhase.WAITING_QR, coordinator.state.phase)
        assertNull(coordinator.state.qrPayload)
    }

    @Test
    fun coordinatorCanStartWithRestoredMatchCount() {
        val coordinator = ScanSessionCoordinator(TestScanner(), existingMatchedCount = 4)

        coordinator.startSession()

        assertEquals(4, coordinator.state.matchedCount)
    }

    @Test
    fun restoredWaitingCode128KeepsStepAndFallsBackToCameraWhenBluetoothIsUnavailable() {
        val coordinator = ScanSessionCoordinator(
            scanner = TestScanner(),
            existingMatchedCount = 3,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_CODE_128,
                qrPayload = qrPayload,
                matchedCount = 3,
                inputSource = ScanCheckpointInputSource.BLUETOOTH,
            ),
        )

        coordinator.startSession()

        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)
        assertEquals(qrPayload, coordinator.state.qrPayload)
        assertEquals(3, coordinator.state.matchedCount)
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertEquals(InputSource.CAMERA, coordinator.state.inputSource)
        assertNull(coordinator.lastEffects.filterIsInstance<ScanEffect.RecordMatch>().firstOrNull())
    }

    @Test
    fun restoredBluetoothFallbackPromotesBackWhenReadyWithoutLosingQr() {
        val scanner = TestScanner()
        val coordinator = ScanSessionCoordinator(
            scanner = scanner,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_CODE_128,
                qrPayload = qrPayload,
                matchedCount = 2,
                inputSource = ScanCheckpointInputSource.BLUETOOTH,
            ),
        )

        coordinator.startSession()
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertEquals(qrPayload, coordinator.state.qrPayload)

        scanner.markReady()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)
        assertEquals(qrPayload, coordinator.state.qrPayload)
        assertEquals(2, coordinator.state.matchedCount)
    }

    @Test
    fun restoredBluetoothWaitsForKnownDeviceConnectionInsteadOfFallingBackImmediately() {
        val scanner = TestScanner().apply { markConnecting() }
        var fallbackRequests = 0
        val coordinator = ScanSessionCoordinator(
            scanner = scanner,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_QR,
                matchedCount = 1,
                inputSource = ScanCheckpointInputSource.BLUETOOTH,
            ),
        )
        coordinator.onBluetoothFallback = { fallbackRequests++ }

        coordinator.startSession()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(InputSource.BLUETOOTH, coordinator.state.inputSource)
        assertEquals(0, fallbackRequests)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)

        scanner.markReady()

        assertEquals(InputSource.BLUETOOTH, coordinator.inputSource)
        assertEquals(0, fallbackRequests)
        assertEquals(ScanFormat.QR, scanner.expectedFormat)
    }

    @Test
    fun explicitRestoredCameraChoiceStaysCameraAfterBluetoothBecomesReady() {
        val scanner = TestScanner().apply { markReady() }
        val coordinator = ScanSessionCoordinator(
            scanner = scanner,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_QR,
                inputSource = ScanCheckpointInputSource.CAMERA,
                cameraWasSelectedByUser = true,
            ),
        )

        coordinator.startSession()
        scanner.markReady()

        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertTrue(coordinator.cameraWasSelectedByUser)
    }

    @Test
    fun selectingAlreadyActiveCameraPersistsIntentAndSurvivesReadyCallback() {
        val scanner = TestScanner()
        val coordinator = ScanSessionCoordinator(scanner)
        coordinator.startSession()

        // The source is already camera, but this selection changes the policy
        // bit that must survive process recreation.
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        coordinator.selectInputSource(InputSource.CAMERA)
        val checkpoint = coordinator.state.toScanSessionCheckpoint(
            sessionId = "session",
            cameraWasSelectedByUser = coordinator.cameraWasSelectedByUser,
        )
        assertTrue(checkpoint?.cameraWasSelectedByUser == true)

        val restoredScanner = TestScanner()
        val restored = ScanSessionCoordinator(
            scanner = restoredScanner,
            restoredCheckpoint = checkpoint,
        )
        restored.startSession()
        restoredScanner.markReady()

        assertEquals(InputSource.CAMERA, restored.inputSource)
        assertTrue(restored.cameraWasSelectedByUser)
    }

    @Test
    fun restoredResultDoesNotReplayMatchOrRestartAutoAdvance() {
        val coordinator = ScanSessionCoordinator(
            scanner = TestScanner(),
            autoAdvanceEnabled = true,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.RESULT,
                qrPayload = qrPayload,
                barcodePayload = barcodePayload,
                result = MatchResult.MATCH,
                matchedCount = 1,
                inputSource = ScanCheckpointInputSource.CAMERA,
            ),
        )

        val reduction = coordinator.startSession()

        assertEquals(ScanPhase.RESULT, coordinator.state.phase)
        assertEquals(MatchResult.MATCH, coordinator.state.result)
        assertNull(coordinator.state.autoAdvanceSecondsRemaining)
        assertTrue(reduction.effects.none { it is ScanEffect.RecordMatch })
        assertTrue(reduction.effects.none { it is ScanEffect.AutoAdvanceStarted })
    }

    @Test
    fun unsupportedCheckpointFallsBackToWaitingQrWithExistingCount() {
        val coordinator = ScanSessionCoordinator(
            scanner = TestScanner(),
            existingMatchedCount = 5,
            restoredCheckpoint = ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.RESULT,
                qrPayload = "qr",
                barcodePayload = "barcode",
                result = MatchResult.MATCH,
                matchedCount = 5,
                version = ScanSessionCheckpoint.CURRENT_VERSION + 1,
            ),
        )

        coordinator.startSession()

        assertEquals(ScanPhase.WAITING_QR, coordinator.state.phase)
        assertEquals(5, coordinator.state.matchedCount)
        assertEquals(InputSource.CAMERA, coordinator.inputSource)
    }

    @Test
    fun rereadQrCheckpointRestoresWaitingQrInsteadOfTheOldAcceptedQr() {
        val coordinator = ScanSessionCoordinator(TestScanner())
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload))
        coordinator.rereadQr()

        val checkpoint = coordinator.state.toScanSessionCheckpoint("session")
        val restored = ScanSessionCoordinator(
            scanner = TestScanner(),
            restoredCheckpoint = checkpoint,
        )
        restored.startSession()

        assertEquals(ScanPhase.WAITING_QR, restored.state.phase)
        assertNull(restored.state.qrPayload)
    }

    @Test
    fun manualNextCheckpointRestoresWaitingQrWithoutReplayingTerminalMatch() {
        val coordinator = ScanSessionCoordinator(TestScanner())
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload))
        coordinator.submitScanPayload(ScanPayload.code128(barcodePayload, timestampMillis = 300L))
        coordinator.submitScanPayload(ScanPayload.code128(barcodePayload, timestampMillis = 400L))
        assertEquals(ScanPhase.RESULT, coordinator.state.phase)
        coordinator.manualNext()

        val checkpoint = coordinator.state.toScanSessionCheckpoint("session")
        val restored = ScanSessionCoordinator(
            scanner = TestScanner(),
            restoredCheckpoint = checkpoint,
        )
        val start = restored.startSession()

        assertEquals(ScanPhase.WAITING_QR, restored.state.phase)
        assertEquals(1, restored.state.matchedCount)
        assertTrue(start.effects.none { it is ScanEffect.RecordMatch })
    }

    private class TestScanner : ExternalScanner {
        private val device = ScannerDevice("test", "Test scanner")
        override var devices: List<ScannerDevice> = listOf(device)
        override var connectionState: ConnectionState = ConnectionState.Idle
        override var configurationState: ConfigurationState = ConfigurationState.Unavailable
        override var diagnosticEvents: List<jp.rimtty.codematch.scanner.api.DiagnosticEvent> = emptyList()
        override var expectedFormat: ScanFormat? = null
        override var listener: ExternalScannerListener? = null
        var reconnectSynchronously: Boolean = true
        var requireExpectedFormatForPayloadReadiness: Boolean = false
        override val isReadyForScanning: Boolean
            get() = super.isReadyForScanning &&
                (!requireExpectedFormatForPayloadReadiness || expectedFormat != null)

        override fun startDiscovery(): Boolean = true
        override fun stopDiscovery(): Boolean = true

        override fun connect(device: ScannerDevice): Boolean {
            markReady()
            return true
        }

        override fun disconnect(): Boolean {
            markDisconnected()
            return true
        }

        override fun reconnectKnownDevice(): Boolean {
            if (reconnectSynchronously) markReady()
            return true
        }

        override fun setExpectedFormat(format: ScanFormat?): Boolean {
            expectedFormat = format
            if (format == null && connectionState.connectedDevice != null) {
                configurationState = ConfigurationState.Ready
            }
            listener?.onConfigurationStateChanged(configurationState)
            return true
        }

        fun markReady() {
            connectionState = ConnectionState.Connected(device)
            configurationState = ConfigurationState.Ready
            listener?.onConnectionStateChanged(connectionState)
            listener?.onConfigurationStateChanged(configurationState)
        }

        fun markConnecting() {
            connectionState = ConnectionState.Connecting(device)
            configurationState = ConfigurationState.Unavailable
            listener?.onConnectionStateChanged(connectionState)
            listener?.onConfigurationStateChanged(configurationState)
        }

        fun markDisconnected() {
            connectionState = ConnectionState.Idle
            configurationState = ConfigurationState.Unavailable
            listener?.onConfigurationStateChanged(configurationState)
            listener?.onConnectionStateChanged(connectionState)
        }

        fun markConfigurationFailed(reason: String) {
            configurationState = ConfigurationState.Failed(reason)
            listener?.onConfigurationStateChanged(configurationState)
        }
    }
}
