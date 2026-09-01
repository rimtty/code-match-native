package jp.rimtty.codematch.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScanModelsTest {
    @Test
    fun autoAdvanceDelaysMatchTheSwiftContract() {
        assertEquals(listOf(1, 3, 5), AutoAdvanceDelay.entries.map { it.seconds })
        assertEquals(AutoAdvanceDelay.THREE_SECONDS, AutoAdvanceSettings.DEFAULT_DELAY)
        assertFalse(AutoAdvanceSettings.DEFAULT_ENABLED)
        assertEquals("autoAdvanceOnMatch", AutoAdvanceSettings.ENABLED_KEY)
        assertEquals("autoAdvanceDelaySeconds", AutoAdvanceSettings.DELAY_SECONDS_KEY)
    }

    @Test
    fun resultAndExpectedCodeUseStableIdiomaticConstants() {
        assertEquals("MATCH", MatchResult.MATCH.name)
        assertEquals("MISMATCH", MatchResult.MISMATCH.name)
        assertEquals("QR", ExpectedCode.QR.name)
        assertEquals("BARCODE", ExpectedCode.BARCODE.name)
        assertEquals(true, ExpectedCode.QR.isQr)
        assertEquals(false, ExpectedCode.BARCODE.isQr)
    }

    @Test
    fun scanStepsReportThreeStageProgress() {
        assertEquals(1, ScanStep.QR.progress)
        assertEquals(2, ScanStep.BARCODE.progress)
        assertEquals(3, ScanStep.Result(MatchResult.MATCH).progress)
        assertEquals(MatchResult.MISMATCH, (ScanStep.Result(MatchResult.MISMATCH)).result)
    }
}
