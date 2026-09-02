package jp.rimtty.codematch.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSessionCheckpointTest {
    @Test
    fun waitingQrCheckpointContainsNoAcceptedValues() {
        assertTrue(
            ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_QR,
                matchedCount = 2,
            ).isSupportedAndValid(),
        )
    }

    @Test
    fun waitingCode128RequiresTheAcceptedQr() {
        assertFalse(
            ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_CODE_128,
            ).isSupportedAndValid(),
        )
        assertTrue(
            ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_CODE_128,
                qrPayload = "qr",
            ).isSupportedAndValid(),
        )
    }

    @Test
    fun resultRequiresBothPayloadsAndAResult() {
        assertFalse(
            ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.RESULT,
                qrPayload = "qr",
                barcodePayload = "barcode",
            ).isSupportedAndValid(),
        )
        assertTrue(
            ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.RESULT,
                qrPayload = "qr",
                barcodePayload = "barcode",
                result = MatchResult.MATCH,
                matchedCount = 1,
            ).isSupportedAndValid(),
        )
    }

    @Test
    fun futureVersionAndNegativeCountAreRejected() {
        assertFalse(
            ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_QR,
                version = ScanSessionCheckpoint.CURRENT_VERSION + 1,
            ).isSupportedAndValid(),
        )
        assertFalse(
            ScanSessionCheckpoint(
                sessionId = "session",
                phase = ScanCheckpointPhase.WAITING_QR,
                matchedCount = -1,
            ).isSupportedAndValid(),
        )
    }
}
