package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ConnectionState
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScannerListener
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import jp.rimtty.codematch.scanner.api.ScannerDevice
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
        coordinator.startSession()
        coordinator.submitScanPayload(ScanPayload.qr(qrPayload, InputSource.BLUETOOTH))
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)

        scanner.markDisconnected()

        assertEquals(InputSource.CAMERA, coordinator.inputSource)
        assertEquals(ScanPhase.WAITING_CODE_128, coordinator.state.phase)
        assertEquals(qrPayload, coordinator.state.qrPayload)
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
    fun coordinatorCanStartWithRestoredMatchCount() {
        val coordinator = ScanSessionCoordinator(TestScanner(), existingMatchedCount = 4)

        coordinator.startSession()

        assertEquals(4, coordinator.state.matchedCount)
    }

    private class TestScanner : ExternalScanner {
        private val device = ScannerDevice("test", "Test scanner")
        override var devices: List<ScannerDevice> = listOf(device)
        override var connectionState: ConnectionState = ConnectionState.Idle
        override var configurationState: ConfigurationState = ConfigurationState.Unavailable
        override var diagnosticEvents: List<jp.rimtty.codematch.scanner.api.DiagnosticEvent> = emptyList()
        override var expectedFormat: ScanFormat? = null
        override var listener: ExternalScannerListener? = null

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
            markReady()
            return true
        }

        override fun setExpectedFormat(format: ScanFormat?): Boolean {
            expectedFormat = format
            listener?.onConfigurationStateChanged(configurationState)
            return true
        }

        fun markReady() {
            connectionState = ConnectionState.Connected(device)
            configurationState = ConfigurationState.Ready
            listener?.onConnectionStateChanged(connectionState)
            listener?.onConfigurationStateChanged(configurationState)
        }

        fun markDisconnected() {
            connectionState = ConnectionState.Idle
            configurationState = ConfigurationState.Unavailable
            listener?.onConfigurationStateChanged(configurationState)
            listener?.onConnectionStateChanged(connectionState)
        }
    }
}
