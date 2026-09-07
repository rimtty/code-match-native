package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.model.Destination
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
    fun destinationRoundTripsInEveryPhase() {
        val states = listOf(
            ScanState.WaitingQr(matchedCount = 2),
            ScanState.WaitingCode128(qrPayload = moltenQr, matchedCount = 2),
            ScanState.Result(
                qrPayload = moltenQr,
                barcodePayload = moltenTag,
                result = MatchResult.MATCH,
                matchedCount = 3,
            ),
        )

        for (scan in states) {
            val state = ScanSessionState(scan = scan, destination = Destination.MOLTEN)
            val checkpoint = state.toScanSessionCheckpoint("session")
            assertEquals(scan.phase.name, Destination.MOLTEN, checkpoint?.destination)
            assertEquals(
                scan.phase.name,
                Destination.MOLTEN,
                checkpoint?.toScanSessionState(false, stateDelay())?.destination,
            )
        }
    }

    @Test
    fun checkpointWithoutDestinationDerivesItFromAcceptedQr() {
        val checkpoint = ScanSessionCheckpoint(
            sessionId = "session",
            phase = ScanCheckpointPhase.WAITING_CODE_128,
            qrPayload = moltenQr,
            matchedCount = 0,
        )

        assertNull(checkpoint.destination)
        assertEquals(
            Destination.MOLTEN,
            checkpoint.toScanSessionState(false, stateDelay())?.destination,
        )

        val waitingQr = ScanSessionCheckpoint(
            sessionId = "session",
            phase = ScanCheckpointPhase.WAITING_QR,
            matchedCount = 0,
        )
        assertNull(waitingQr.toScanSessionState(false, stateDelay())?.destination)
    }

    @Test
    fun checkpointWithoutDestinationDerivesDensoFromAcceptedQr() {
        val checkpoint = ScanSessionCheckpoint(
            sessionId = "session",
            phase = ScanCheckpointPhase.WAITING_CODE_128,
            qrPayload = densoQr,
            matchedCount = 0,
        )

        assertNull(checkpoint.destination)
        assertEquals(
            Destination.DENSO,
            checkpoint.toScanSessionState(false, stateDelay())?.destination,
        )
    }

    @Test
    fun idleStateDoesNotCreateACheckpoint() {
        assertNull(ScanSessionState().toScanSessionCheckpoint("session"))
    }

    private fun stateDelay() = jp.rimtty.codematch.core.model.AutoAdvanceDelay.FIVE_SECONDS

    // Destination Molten; the trailing spaces are record data.
    private val moltenQr =
        "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
    private val moltenTag = "PAF1-15-422@0NKD3C"

    // Destination Denso: the real kanban of box 0140. The runs of spaces are
    // blank item values, so the literal must never be trimmed.
    private val densoQr =
        "JAMA501195000001021100021041011102112071210412406127041410214201144061520440205515015160151908520045210652606523105220640102208601507722000000024D850C01008D85045M      0140SWS    20260908S0010000720000009924543330454333M6"
}
