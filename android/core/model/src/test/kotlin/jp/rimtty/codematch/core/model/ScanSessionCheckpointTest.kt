package jp.rimtty.codematch.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun destinationIsAdditiveAndDoesNotAffectValidation() {
        val waitingQr = ScanSessionCheckpoint(
            sessionId = "session",
            phase = ScanCheckpointPhase.WAITING_QR,
            matchedCount = 2,
        )

        // The field is purely additive, so a checkpoint written by an older
        // build must still be accepted at the unchanged contract version.
        assertEquals(1, ScanSessionCheckpoint.CURRENT_VERSION)
        assertNull(waitingQr.destination)
        assertTrue(waitingQr.isSupportedAndValid())
        Destination.entries.forEach { destination ->
            val locked = waitingQr.copy(destination = destination)
            assertEquals(ScanSessionCheckpoint.CURRENT_VERSION, locked.version)
            assertTrue(locked.isSupportedAndValid())
        }

        // Each phase keeps its own rules no matter which destination is set.
        assertFalse(
            waitingQr.copy(
                phase = ScanCheckpointPhase.WAITING_CODE_128,
                destination = Destination.MOLTEN,
            ).isSupportedAndValid(),
        )
        assertTrue(
            waitingQr.copy(
                phase = ScanCheckpointPhase.WAITING_CODE_128,
                qrPayload = "qr",
                destination = Destination.MOLTEN,
            ).isSupportedAndValid(),
        )
    }
}
