package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.core.model.ScanCheckpointInputSource
import jp.rimtty.codematch.core.model.ScanCheckpointPhase
import jp.rimtty.codematch.core.model.ScanSessionCheckpoint
import jp.rimtty.codematch.scanner.api.InputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCheckpointMappingTest {
    @Test
    fun waitingCode128RoundTripsAcceptedQrAndInputIntent() {
        val state = ScanSessionState(
            scan = ScanState.WaitingCode128(qrPayload = "qr", matchedCount = 4),
            inputSource = InputSource.BLUETOOTH,
            initialMatchedCount = 4,
        )

        val checkpoint = state.toScanSessionCheckpoint(
            sessionId = "session",
            cameraWasSelectedByUser = false,
        )
        assertEquals(ScanCheckpointPhase.WAITING_CODE_128, checkpoint?.phase)
        assertEquals(ScanCheckpointInputSource.BLUETOOTH, checkpoint?.inputSource)
        assertTrue(checkpoint?.isSupportedAndValid() == true)
        assertEquals(state, checkpoint?.toScanSessionState(false, state.autoAdvanceDelay))
    }

    @Test
    fun resultRoundTripNeverRestoresCountdown() {
        val checkpoint = ScanSessionCheckpoint(
            sessionId = "session",
            phase = ScanCheckpointPhase.RESULT,
            qrPayload = "qr",
            barcodePayload = "barcode",
            result = MatchResult.MISMATCH,
            matchedCount = 2,
        )

        val restored = checkpoint.toScanSessionState(true, stateDelay())

        assertEquals(ScanPhase.RESULT, restored?.phase)
        assertEquals(MatchResult.MISMATCH, restored?.result)
        assertNull(restored?.autoAdvanceSecondsRemaining)
        assertEquals(2, restored?.matchedCount)
    }

    @Test
    fun idleStateDoesNotCreateACheckpoint() {
        assertNull(ScanSessionState().toScanSessionCheckpoint("session"))
    }

    private fun stateDelay() = jp.rimtty.codematch.core.model.AutoAdvanceDelay.FIVE_SECONDS
}
