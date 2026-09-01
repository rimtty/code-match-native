package jp.rimtty.codematch.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import jp.rimtty.codematch.R
import jp.rimtty.codematch.core.model.FailureSound
import jp.rimtty.codematch.core.model.SuccessSound
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The haptic cue paired with a user-visible feedback state. */
enum class FeedbackHaptic {
    MEDIUM,
    WARNING,
    SUCCESS,
    ERROR,
}

/** One synthesized tone, expressed independently of Android audio classes. */
data class FeedbackTone(
    val frequencyHz: Double,
    val durationMillis: Int,
    val amplitude: Float,
    val piercing: Boolean = false,
)

/**
 * Platform-neutral feedback contract shared by the player and JVM tests.
 *
 * Asset names are Android raw-resource names without an extension. A cue with
 * an asset has no synthesized tones; a synthesized cue has one or more tones.
 */
data class FeedbackCue(
    val tones: List<FeedbackTone>,
    val gapMillis: Int,
    val volumeScale: Float,
    val haptic: FeedbackHaptic,
    val assetName: String? = null,
) {
    init {
        require(tones.isNotEmpty() xor (assetName != null)) {
            "A feedback cue must use either tones or an audio asset"
        }
        require(gapMillis >= 0) { "gapMillis must not be negative" }
        require(volumeScale in 0f..1f) { "volumeScale must be between 0 and 1" }
    }

    /** Audio is intentionally suppressed at zero volume; haptic is not. */
    fun shouldPlayAudio(volume: Float): Boolean = volume > 0f &&
        (assetName != null || tones.isNotEmpty())
}

/**
 * iOS FeedbackPlayer.swift's frequency, timing, asset, and haptic contract.
 * Keep this object free of Android framework types so its values are
 * deterministic in local unit tests.
 */
object FeedbackContract {
    val scanAccepted = FeedbackCue(
        tones = listOf(
            FeedbackTone(
                frequencyHz = 1_567.98,
                durationMillis = 60,
                amplitude = 0.45f,
                piercing = true,
            ),
        ),
        gapMillis = 0,
        volumeScale = 0.6f,
        haptic = FeedbackHaptic.MEDIUM,
    )

    val invalidScan = FeedbackCue(
        tones = listOf(
            FeedbackTone(330.0, 90, 0.65f),
            FeedbackTone(330.0, 90, 0.65f),
        ),
        gapMillis = 60,
        volumeScale = 0.7f,
        haptic = FeedbackHaptic.WARNING,
    )

    fun success(sound: SuccessSound): FeedbackCue = when (sound) {
        SuccessSound.SAMPLE_1 -> asset("success1")
        SuccessSound.SAMPLE_2 -> asset("success2")
        SuccessSound.POS_BEEP -> synthesized(
            tones = listOf(FeedbackTone(2_600.0, 120, 0.95f, piercing = true)),
        )
        SuccessSound.DOUBLE_BEEP -> synthesized(
            tones = listOf(
                FeedbackTone(2_600.0, 80, 0.95f, piercing = true),
                FeedbackTone(2_600.0, 80, 0.95f, piercing = true),
            ),
            gapMillis = 60,
        )
        SuccessSound.CHIME -> synthesized(
            tones = listOf(
                FeedbackTone(523.25, 90, 0.80f),
                FeedbackTone(659.25, 90, 0.85f),
                FeedbackTone(783.99, 180, 0.90f),
            ),
            gapMillis = 35,
        )
    }

    fun failure(sound: FailureSound): FeedbackCue = when (sound) {
        FailureSound.FAIL_SAMPLE -> asset("fail1", FeedbackHaptic.ERROR)
        FailureSound.BUZZER -> synthesized(
            tones = listOf(
                FeedbackTone(165.0, 160, 0.95f, piercing = true),
                FeedbackTone(165.0, 420, 0.95f, piercing = true),
            ),
            gapMillis = 70,
            haptic = FeedbackHaptic.ERROR,
        )
        FailureSound.ALARM -> synthesized(
            tones = List(4) { FeedbackTone(980.0, 110, 0.92f, piercing = true) },
            gapMillis = 90,
            haptic = FeedbackHaptic.ERROR,
        )
        FailureSound.DESCEND -> synthesized(
            tones = listOf(
                FeedbackTone(440.0, 180, 0.92f, piercing = true),
                FeedbackTone(220.0, 450, 0.95f, piercing = true),
            ),
            gapMillis = 40,
            haptic = FeedbackHaptic.ERROR,
        )
    }

    private fun asset(
        name: String,
        haptic: FeedbackHaptic = FeedbackHaptic.SUCCESS,
    ): FeedbackCue = FeedbackCue(
        tones = emptyList(),
        gapMillis = 0,
        volumeScale = 1.0f,
        haptic = haptic,
        assetName = name,
    )

    private fun synthesized(
        tones: List<FeedbackTone>,
        gapMillis: Int = 0,
        haptic: FeedbackHaptic = FeedbackHaptic.SUCCESS,
    ): FeedbackCue = FeedbackCue(
        tones = tones,
        gapMillis = gapMillis,
        volumeScale = 1.0f,
        haptic = haptic,
    )
}

/**
 * Plays the feedback contract without doing audio work on the main thread.
 *
 * A new cue interrupts an earlier cue, matching the iOS audio player's
 * `.interrupts` scheduling behavior. Haptic dispatch is independent of volume
 * so a 0% volume setting still communicates all four states by vibration.
 */
@Singleton
class FeedbackPlayer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private class MediaPlayback(val player: MediaPlayer) {
        val released = AtomicBoolean(false)

        @Volatile
        var deadline: ScheduledFuture<*>? = null
    }

    private val audioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "codematch-feedback-audio").apply { isDaemon = true }
    }
    private val mediaDeadlineExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "codematch-feedback-deadline").apply { isDaemon = true }
        }
    private val generation = AtomicLong(0)

    @Volatile
    private var currentTrack: AudioTrack? = null

    @Volatile
    private var currentMediaPlayback: MediaPlayback? = null

    fun playScanAccepted(volume: Float) {
        play(FeedbackContract.scanAccepted, volume)
    }

    fun playInvalidScan(volume: Float = 1.0f) {
        play(FeedbackContract.invalidScan, volume)
    }

    fun playSuccess(
        sound: SuccessSound,
        volume: Float,
        includeHaptic: Boolean = true,
    ) {
        play(FeedbackContract.success(sound), volume, includeHaptic)
    }

    fun playFailure(
        sound: FailureSound,
        volume: Float,
        includeHaptic: Boolean = true,
    ) {
        play(FeedbackContract.failure(sound), volume, includeHaptic)
    }

    private fun play(cue: FeedbackCue, volume: Float, includeHaptic: Boolean = true) {
        if (includeHaptic) playHaptic(cue.haptic)

        val token = generation.incrementAndGet()
        // Keep all MediaPlayer/AudioTrack operations off the caller (normally
        // the main/UI) thread. The ordered stop task still interrupts the
        // previous cue before this cue starts on the single audio executor.
        audioExecutor.execute {
            if (token != generation.get()) return@execute
            stopCurrentAudio()
            if (!cue.shouldPlayAudio(volume)) return@execute

            if (cue.assetName != null) {
                playAsset(cue.assetName, volume.coerceIn(0f, 1f), token)
            } else {
                playSynthesized(cue, volume.coerceIn(0f, 1f), token)
            }
        }
    }

    private fun playAsset(name: String, volume: Float, token: Long) {
        val resourceId = when (name) {
            "success1" -> R.raw.success1
            "success2" -> R.raw.success2
            "fail1" -> R.raw.fail1
            else -> return
        }
        val player = runCatching { MediaPlayer.create(context, resourceId) }.getOrNull() ?: return
        val playback = MediaPlayback(player)
        if (token != generation.get()) {
            releaseMediaPlayback(playback)
            return
        }
        val listenersInstalled = runCatching {
            player.setVolume(volume, volume)
            player.setOnCompletionListener {
                releaseMediaPlaybackAsync(playback)
            }
            player.setOnErrorListener { _, _, _ ->
                releaseMediaPlaybackAsync(playback)
                true
            }
        }.isSuccess
        if (!listenersInstalled) {
            releaseMediaPlayback(playback)
            return
        }
        val shouldRelease = synchronized(this) {
            if (token != generation.get()) {
                true
            } else {
                currentMediaPlayback = playback
                false
            }
        }
        if (shouldRelease) {
            releaseMediaPlayback(playback)
            return
        }

        val durationMillis = runCatching { player.duration.toLong() }
            .getOrDefault(0L)
            .coerceAtLeast(0L)
        playback.deadline = runCatching {
            mediaDeadlineExecutor.schedule(
                { releaseMediaPlayback(playback) },
                durationMillis + AUDIO_COMPLETION_MARGIN_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }.getOrNull()
        if (playback.deadline == null) {
            releaseMediaPlayback(playback)
            return
        }
        if (token != generation.get()) {
            releaseMediaPlayback(playback)
            return
        }

        runCatching { player.start() }
            .onFailure { releaseMediaPlayback(playback) }
    }

    /** MediaPlayer callbacks may be delivered on the main looper. */
    private fun releaseMediaPlaybackAsync(playback: MediaPlayback) {
        runCatching {
            audioExecutor.execute { releaseMediaPlayback(playback) }
        }.onFailure {
            // The deadline/new-cue paths remain responsible for cleanup if
            // the executor is no longer accepting callback work.
        }
    }

    private fun playSynthesized(cue: FeedbackCue, volume: Float, token: Long) {
        val samples = buildSamples(cue, volume)
        if (samples.isEmpty() || token != generation.get()) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(1)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(max(samples.size * 2, minBufferSize))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: return

        if (token != generation.get()) {
            runCatching { track.release() }
            return
        }
        val written = runCatching { track.write(samples, 0, samples.size) }.getOrDefault(-1)
        if (written != samples.size || token != generation.get()) {
            runCatching { track.release() }
            return
        }
        synchronized(this) {
            if (token != generation.get()) {
                runCatching { track.release() }
                return
            }
            currentTrack = track
        }
        try {
            track.play()
            val durationMillis = cue.tones.sumOf { it.durationMillis } +
                cue.gapMillis * max(cue.tones.size - 1, 0)
            val deadlineNanos = System.nanoTime() +
                (durationMillis + AUDIO_COMPLETION_MARGIN_MILLIS) * 1_000_000L
            // Some devices keep MODE_STATIC in PLAYSTATE_PLAYING after the
            // final frame. Use both playback head and a hard deadline so every
            // cue is released even when that state is sticky.
            while (
                token == generation.get() &&
                track.playbackHeadPosition < samples.size &&
                System.nanoTime() < deadlineNanos
            ) {
                Thread.sleep(AUDIO_POLL_MILLIS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: RuntimeException) {
            // A transient audio-route failure must not affect scan state.
        } finally {
            synchronized(this) {
                if (currentTrack === track) currentTrack = null
            }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun buildSamples(cue: FeedbackCue, volume: Float): ShortArray {
        val gapFrames = (cue.gapMillis * SAMPLE_RATE / 1_000.0).roundToInt()
        val toneFrames = cue.tones.map {
            (it.durationMillis * SAMPLE_RATE / 1_000.0).roundToInt()
        }
        val totalFrames = toneFrames.sum() + max(cue.tones.size - 1, 0) * gapFrames
        if (totalFrames <= 0) return ShortArray(0)

        val samples = ShortArray(totalFrames)
        var offset = 0
        val scaledVolume = (volume * cue.volumeScale).coerceIn(0f, 1f)
        cue.tones.forEachIndexed { index, tone ->
            val frameCount = toneFrames[index]
            val edgeFrames = min(
                (ENVELOPE_MILLIS * SAMPLE_RATE / 1_000.0).roundToInt(),
                max(frameCount / 2, 1),
            )
            for (frame in 0 until frameCount) {
                val attack = min(frame.toFloat() / edgeFrames, 1f)
                val release = min((frameCount - frame - 1).toFloat() / edgeFrames, 1f)
                val envelope = max(min(attack, release), 0f)
                val phase = 2.0 * PI * tone.frequencyHz * frame / SAMPLE_RATE
                var sample = sin(phase)
                if (tone.piercing) {
                    sample += sin(phase * 3.0) / 3.0 + sin(phase * 5.0) / 5.0
                    sample *= 0.85
                }
                val scaled = (sample * tone.amplitude * envelope * scaledVolume)
                    .coerceIn(-1.0, 1.0)
                samples[offset + frame] = (scaled * Short.MAX_VALUE).roundToInt().toShort()
            }
            offset += frameCount
            if (index < cue.tones.lastIndex) offset += gapFrames
        }
        return samples
    }

    private fun playHaptic(haptic: FeedbackHaptic) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator() || !isHapticFeedbackEnabled()) return

        val effect = when (haptic) {
            FeedbackHaptic.MEDIUM -> VibrationEffect.createOneShot(
                MEDIUM_HAPTIC_MILLIS,
                VibrationEffect.DEFAULT_AMPLITUDE,
            )
            FeedbackHaptic.WARNING -> VibrationEffect.createWaveform(
                longArrayOf(0, WARNING_HAPTIC_MILLIS, WARNING_GAP_MILLIS, WARNING_HAPTIC_MILLIS),
                -1,
            )
            FeedbackHaptic.SUCCESS -> VibrationEffect.createOneShot(
                SUCCESS_HAPTIC_MILLIS,
                VibrationEffect.DEFAULT_AMPLITUDE,
            )
            FeedbackHaptic.ERROR -> VibrationEffect.createWaveform(
                longArrayOf(0, ERROR_HAPTIC_MILLIS, ERROR_GAP_MILLIS, ERROR_HAPTIC_MILLIS),
                -1,
            )
        }
        runCatching { vibrator.vibrate(effect) }
    }

    /** Android's vibrator service also applies the device's global haptic policy. */
    @Suppress("DEPRECATION") // HAPTIC_FEEDBACK_ENABLED is the system user setting on API 31+.
    private fun isHapticFeedbackEnabled(): Boolean = runCatching {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1,
        ) != 0
    }.getOrDefault(true)

    private fun stopCurrentAudio() {
        val track: AudioTrack?
        val mediaPlayback: MediaPlayback?
        synchronized(this) {
            track = currentTrack
            currentTrack = null
            mediaPlayback = currentMediaPlayback
            currentMediaPlayback = null
        }
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        mediaPlayback?.let(::releaseMediaPlayback)
    }

    /** Idempotent release shared by completion, error, deadline, and new cues. */
    private fun releaseMediaPlayback(playback: MediaPlayback) {
        if (!playback.released.compareAndSet(false, true)) return
        synchronized(this) {
            if (currentMediaPlayback === playback) currentMediaPlayback = null
        }
        playback.deadline?.cancel(false)
        runCatching { playback.player.stop() }
        runCatching { playback.player.release() }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val ENVELOPE_MILLIS = 6
        const val AUDIO_POLL_MILLIS = 10L
        const val AUDIO_COMPLETION_MARGIN_MILLIS = 250L
        const val MEDIUM_HAPTIC_MILLIS = 45L
        const val WARNING_HAPTIC_MILLIS = 90L
        const val WARNING_GAP_MILLIS = 60L
        const val SUCCESS_HAPTIC_MILLIS = 55L
        const val ERROR_HAPTIC_MILLIS = 90L
        const val ERROR_GAP_MILLIS = 50L
    }
}
