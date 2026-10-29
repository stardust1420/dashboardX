package com.karan.dashboardx.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.karan.dashboardx.DashRuntime
import com.karan.dashboardx.alerts.Warning
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Warning chime: 880 Hz then 660 Hz, 120 ms each with 10 ms fades, on one prebuilt static
 * [AudioTrack] (`USAGE_ASSISTANCE_SONIFICATION`), so it mixes over music without taking focus.
 * Plays whatever the engine-sound setting is. A chime that arrives while one is still sounding is
 * skipped rather than stacked. Never throws; failures go to `DashRuntime.diag`.
 *
 * Called from render threads (via `DashRuntime.onChime`), so [play] is cheap after the first call.
 */
object Chime {
    private const val SAMPLE_RATE = 44100
    private const val TONE_MS = 120
    private const val FADE_MS = 10
    private const val LEVEL = 0.5f
    private const val TONE_SAMPLES = SAMPLE_RATE * TONE_MS / 1000
    private const val TOTAL_SAMPLES = TONE_SAMPLES * 2
    private const val DURATION_NS = TONE_MS * 2 * 1_000_000L
    private const val GUARD_NS = 30_000_000L // slack for output latency before allowing the next chime

    private var track: AudioTrack? = null // guarded by this
    private var lastStartNs = 0L // guarded by this
    private var started = false // guarded by this

    /** The two-tone waveform. Pure; exposed for inspection. */
    internal fun buildSamples(): FloatArray {
        val out = FloatArray(TOTAL_SAMPLES)
        val fade = SAMPLE_RATE * FADE_MS / 1000
        for (tone in 0..1) {
            val hz = if (tone == 0) 880.0 else 660.0
            for (i in 0 until TONE_SAMPLES) {
                val env = min(1f, min(i, TONE_SAMPLES - 1 - i).toFloat() / fade)
                out[tone * TONE_SAMPLES + i] = (sin(2 * PI * hz * i / SAMPLE_RATE) * env * LEVEL).toFloat()
            }
        }
        return out
    }

    @Suppress("UNUSED_PARAMETER")
    fun play(ctx: Context, w: Warning) {
        try {
            synchronized(this) {
                val now = System.nanoTime()
                if (started && now - lastStartNs < DURATION_NS + GUARD_NS) return // still sounding
                val t = track ?: build() ?: return
                track = t
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
                t.reloadStaticData()
                t.play()
                lastStartNs = now
                started = true
            }
            DashRuntime.diag.add("chime: $w")
        } catch (e: Exception) {
            log("play failed", e)
            synchronized(this) {
                try {
                    track?.release()
                } catch (e2: Exception) {
                    // Already broken; dropping it so the next chime rebuilds.
                }
                track = null
            }
        }
    }

    private fun build(): AudioTrack? = try {
        val samples = buildSamples()
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 4)
            .build()
        val wrote = t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        if (wrote != samples.size || t.state != AudioTrack.STATE_INITIALIZED) {
            DashRuntime.diag.add("chime: static track setup failed (wrote $wrote, state ${t.state})")
            t.release()
            null
        } else {
            t
        }
    } catch (e: Exception) {
        log("build failed", e)
        null
    }

    private fun log(what: String, e: Exception) {
        try {
            DashRuntime.diag.add("chime: $what: ${e.javaClass.simpleName}: ${e.message}")
        } catch (e2: Exception) {
        }
    }
}
