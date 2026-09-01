package jp.rimtty.codematch.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import jp.rimtty.codematch.core.model.FailureSound
import jp.rimtty.codematch.core.model.SuccessSound

/** Lightweight M2 feedback bridge; bundled iOS audio parity is completed in the audio phase. */
@Singleton
class FeedbackPlayer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun playSuccess(sound: SuccessSound, volume: Float, includeHaptic: Boolean = false) {
        playTone(successTone(sound), volume)
        if (includeHaptic) vibrate(longArrayOf(0, 45))
    }

    fun playFailure(sound: FailureSound, volume: Float, includeHaptic: Boolean = false) {
        playTone(failureTone(sound), volume)
        if (includeHaptic) vibrate(longArrayOf(0, 90, 50, 90))
    }

    private fun playTone(tone: Int, volume: Float) {
        val percent = (volume.coerceIn(0f, 1f) * 100f).toInt()
        if (percent == 0) return
        runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, percent).apply {
                startTone(tone, TONE_DURATION_MILLIS)
                android.os.Handler(context.mainLooper).postDelayed(
                    { runCatching { release() } },
                    TONE_DURATION_MILLIS + RELEASE_DELAY_MILLIS,
                )
            }
        }
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun successTone(sound: SuccessSound): Int = when (sound) {
        SuccessSound.SAMPLE_1 -> ToneGenerator.TONE_PROP_BEEP
        SuccessSound.SAMPLE_2 -> ToneGenerator.TONE_PROP_BEEP2
        SuccessSound.POS_BEEP -> ToneGenerator.TONE_CDMA_PIP
        SuccessSound.DOUBLE_BEEP -> ToneGenerator.TONE_PROP_ACK
        SuccessSound.CHIME -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
    }

    private fun failureTone(sound: FailureSound): Int = when (sound) {
        FailureSound.FAIL_SAMPLE -> ToneGenerator.TONE_PROP_NACK
        FailureSound.BUZZER -> ToneGenerator.TONE_SUP_BUSY
        FailureSound.ALARM -> ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK
        FailureSound.DESCEND -> ToneGenerator.TONE_CDMA_NETWORK_BUSY
    }

    private companion object {
        const val TONE_DURATION_MILLIS = 260
        const val RELEASE_DELAY_MILLIS = 80L
    }
}
