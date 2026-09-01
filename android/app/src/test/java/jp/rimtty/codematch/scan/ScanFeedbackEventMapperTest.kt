package jp.rimtty.codematch.scan

import jp.rimtty.codematch.core.model.MatchResult
import jp.rimtty.codematch.feature.scan.InvalidScanReason
import jp.rimtty.codematch.feature.scan.ScanEffect
import jp.rimtty.codematch.scanner.api.ScanFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanFeedbackEventMapperTest {
    @Test
    fun acceptedQrProducesOnlyAcceptedCue() {
        assertEquals(
            listOf(ScanFeedbackEvent.SCAN_ACCEPTED),
            ScanFeedbackEventMapper.map(
                effects = listOf(
                    ScanEffect.ScanAccepted,
                    ScanEffect.ExpectFormat(ScanFormat.CODE_128),
                ),
                result = null,
            ),
        )
    }

    @Test
    fun matchedBarcodeProducesOnlyOneTerminalMatchCue() {
        val effects = listOf(
            ScanEffect.ScanAccepted,
            ScanEffect.RecordMatch(
                qrPayload = "qr",
                barcodePayload = "barcode",
                code = "CODE",
                matchNumber = 1,
            ),
        )

        assertEquals(
            listOf(ScanFeedbackEvent.MATCH),
            ScanFeedbackEventMapper.map(effects, MatchResult.MATCH),
        )
    }

    @Test
    fun mismatchedBarcodeProducesOnlyOneTerminalMismatchCue() {
        assertEquals(
            listOf(ScanFeedbackEvent.MISMATCH),
            ScanFeedbackEventMapper.map(
                effects = listOf(ScanEffect.ScanAccepted, ScanEffect.AutoAdvanceCancelled),
                result = MatchResult.MISMATCH,
            ),
        )
    }

    @Test
    fun invalidInputProducesOnlyInvalidCue() {
        assertEquals(
            listOf(ScanFeedbackEvent.INVALID_SCAN),
            ScanFeedbackEventMapper.map(
                effects = listOf(
                    ScanEffect.InvalidScan(ScanFormat.QR, InvalidScanReason.WRONG_ORDER),
                ),
                result = null,
            ),
        )
    }

    @Test
    fun duplicateTerminalEffectsStillProduceOneTerminalCue() {
        val effects = listOf(
            ScanEffect.ScanAccepted,
            ScanEffect.ScanAccepted,
            ScanEffect.RecordMatch("qr", "barcode", "CODE", 1),
            ScanEffect.RecordMatch("qr", "barcode", "CODE", 1),
        )

        assertEquals(
            listOf(ScanFeedbackEvent.MATCH),
            ScanFeedbackEventMapper.map(effects, MatchResult.MATCH),
        )
    }
}
