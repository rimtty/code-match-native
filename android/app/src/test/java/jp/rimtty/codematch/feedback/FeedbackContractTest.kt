package jp.rimtty.codematch.feedback

import jp.rimtty.codematch.core.model.FailureSound
import jp.rimtty.codematch.core.model.SuccessSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackContractTest {
    @Test
    fun scanAcceptedMatchesIosContract() {
        val cue = FeedbackContract.scanAccepted

        assertEquals(1, cue.tones.size)
        assertEquals(1_567.98, cue.tones.single().frequencyHz, 0.001)
        assertEquals(60, cue.tones.single().durationMillis)
        assertEquals(0.45f, cue.tones.single().amplitude)
        assertTrue(cue.tones.single().piercing)
        assertEquals(0, cue.gapMillis)
        assertEquals(0.6f, cue.volumeScale)
        assertEquals(FeedbackHaptic.MEDIUM, cue.haptic)
    }

    @Test
    fun invalidScanUsesTwoWarningBeeps() {
        val cue = FeedbackContract.invalidScan

        assertEquals(2, cue.tones.size)
        assertTrue(cue.tones.all { it.frequencyHz == 330.0 })
        assertTrue(cue.tones.all { it.durationMillis == 90 })
        assertEquals(60, cue.gapMillis)
        assertEquals(0.7f, cue.volumeScale)
        assertEquals(FeedbackHaptic.WARNING, cue.haptic)
    }

    @Test
    fun successChoicesPreserveAssetsAndSynthesizedSequences() {
        val sample1 = FeedbackContract.success(SuccessSound.SAMPLE_1)
        val sample2 = FeedbackContract.success(SuccessSound.SAMPLE_2)
        assertEquals("success1", sample1.assetName)
        assertEquals("success2", sample2.assetName)

        val posBeep = FeedbackContract.success(SuccessSound.POS_BEEP)
        assertEquals(listOf(120), posBeep.tones.map { it.durationMillis })
        assertEquals(listOf(2_600.0), posBeep.tones.map { it.frequencyHz })
        assertTrue(posBeep.tones.single().piercing)

        val doubleBeep = FeedbackContract.success(SuccessSound.DOUBLE_BEEP)
        assertEquals(listOf(80, 80), doubleBeep.tones.map { it.durationMillis })
        assertEquals(60, doubleBeep.gapMillis)

        val chime = FeedbackContract.success(SuccessSound.CHIME)
        assertEquals(listOf(90, 90, 180), chime.tones.map { it.durationMillis })
        assertEquals(listOf(523.25, 659.25, 783.99), chime.tones.map { it.frequencyHz })
        assertEquals(35, chime.gapMillis)
        assertTrue(chime.tones.none { it.piercing })
        assertTrue(SuccessSound.entries.all { FeedbackContract.success(it).haptic == FeedbackHaptic.SUCCESS })
    }

    @Test
    fun failureChoicesPreserveAssetAndSynthesizedSequences() {
        assertEquals("fail1", FeedbackContract.failure(FailureSound.FAIL_SAMPLE).assetName)

        val buzzer = FeedbackContract.failure(FailureSound.BUZZER)
        assertEquals(listOf(160, 420), buzzer.tones.map { it.durationMillis })
        assertTrue(buzzer.tones.all { it.frequencyHz == 165.0 && it.piercing })
        assertEquals(70, buzzer.gapMillis)

        val alarm = FeedbackContract.failure(FailureSound.ALARM)
        assertEquals(4, alarm.tones.size)
        assertTrue(alarm.tones.all { it.frequencyHz == 980.0 && it.durationMillis == 110 && it.piercing })
        assertEquals(90, alarm.gapMillis)

        val descend = FeedbackContract.failure(FailureSound.DESCEND)
        assertEquals(listOf(440.0, 220.0), descend.tones.map { it.frequencyHz })
        assertEquals(listOf(180, 450), descend.tones.map { it.durationMillis })
        assertEquals(40, descend.gapMillis)
        assertTrue(FailureSound.entries.all { FeedbackContract.failure(it).haptic == FeedbackHaptic.ERROR })
    }

    @Test
    fun zeroVolumeSuppressesAudioButDoesNotChangeHapticContract() {
        SuccessSound.entries.forEach { sound ->
            val cue = FeedbackContract.success(sound)
            assertFalse(cue.shouldPlayAudio(0f))
            assertEquals(FeedbackHaptic.SUCCESS, cue.haptic)
        }
        assertFalse(FeedbackContract.scanAccepted.shouldPlayAudio(0f))
        assertEquals(FeedbackHaptic.MEDIUM, FeedbackContract.scanAccepted.haptic)
        assertFalse(FeedbackContract.invalidScan.shouldPlayAudio(0f))
        assertEquals(FeedbackHaptic.WARNING, FeedbackContract.invalidScan.haptic)
        FailureSound.entries.forEach { sound ->
            assertFalse(FeedbackContract.failure(sound).shouldPlayAudio(0f))
            assertEquals(FeedbackHaptic.ERROR, FeedbackContract.failure(sound).haptic)
        }
    }
}
