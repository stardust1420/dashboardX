package com.karan.dashboardx.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.karan.dashboardx.DashRuntime

/**
 * Plays [EngineSynth] live over whatever else is playing (music ducks, it does not pause).
 *
 * - [enabled] is false at construction. Turning it on requests
 *   `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` and starts an audio thread that owns a streaming float
 *   [AudioTrack] and feeds it ~10 ms blocks from the latest [update]. Turning it off abandons focus;
 *   the thread fades out over 300 ms, then stops and releases the track and exits.
 * - A null rpm (or no [update] for [STALE_MS]) fades to silence over 300 ms, and back up when
 *   rpm returns. After [IDLE_FOCUS_MS] of silence the audio focus is abandoned (music un-ducks);
 *   it is requested again when rpm returns.
 * - If the audio thread dies (AudioTrack write error, build failure or exception), the sound is
 *   switched off ([enabled] becomes false), focus is abandoned, the reason is logged and
 *   [onStateChanged] is posted to the main thread so the UI can refresh its Sound icon.
 * - Volume follows `DashRuntime.settings.soundVolume`, re-read every block.
 * - Nothing here throws to the caller; failures go to `DashRuntime.diag`.
 *
 * Threading: [enabled], [update] and [release] may be called from any thread ([update] is called
 * from the car render thread). Only the audio thread touches the [AudioTrack].
 */
class EngineSoundController(ctx: Context) {
    private class Input(val rpm: Double?, val throttle: Double?, val atNs: Long)

    private val audioManager: AudioManager? = try {
        ctx.getSystemService(AudioManager::class.java)
    } catch (e: Exception) {
        log("getSystemService(AudioManager) failed", e)
        null
    }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** 1 = full, 0.3 = we were asked to duck, 0 = focus lost or denied. Written by focus callbacks. */
    @Volatile private var focusGain = 1f
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        focusGain = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> 1f
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> DUCKED_GAIN
            else -> 0f // AUDIOFOCUS_LOSS, AUDIOFOCUS_LOSS_TRANSIENT (calls, other games)
        }
        DashRuntime.diag.add("engine sound: focus change $change")
    }
    private val focusRequest: AudioFocusRequest? = try {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    } catch (e: Exception) {
        log("AudioFocusRequest build failed", e)
        null
    }

    @Volatile private var input = Input(null, null, 0L)

    /** Posted to the main thread when [enabled] changes on its own (the audio thread failed). */
    @Volatile var onStateChanged: (() -> Unit)? = null
    private val main: Handler? = try {
        Handler(Looper.getMainLooper())
    } catch (e: Exception) {
        log("main Handler failed", e)
        null
    }

    private val lock = Any()
    private var wantOn = false // guarded by lock
    private var released = false // guarded by lock
    private var focusHeld = false // guarded by lock; we hold a focus request
    private var worker: Thread? = null // guarded by lock; non-null while the audio thread lives
    @Volatile private var stopNow = false // release(): exit without fading

    var enabled: Boolean
        get() = synchronized(lock) { wantOn }
        set(value) {
            try {
                synchronized(lock) {
                    if (released || value == wantOn) return
                    wantOn = value
                    if (value) {
                        requestFocus()
                        if (worker == null) startWorker()
                    } else {
                        abandonFocus()
                    }
                }
                DashRuntime.diag.add("engine sound: ${if (value) "on" else "off"}")
            } catch (e: Exception) {
                log("set enabled=$value failed", e)
            }
        }

    /** Latest smoothed rpm and throttle (%). Cheap; call every frame. */
    fun update(rpm: Double?, throttle: Double?) {
        input = Input(rpm, throttle, System.nanoTime())
    }

    /** Stops the audio thread (it releases the track) and abandons focus. The controller is dead after this. */
    fun release() {
        try {
            synchronized(lock) {
                if (released) return
                released = true
                stopNow = true
                abandonFocus()
                wantOn = false
            }
        } catch (e: Exception) {
            log("release failed", e)
        }
    }

    // --- focus (called under lock) ---

    private fun requestFocus() {
        val am = audioManager
        val req = focusRequest
        if (am == null || req == null) return
        try {
            val r = am.requestAudioFocus(req)
            focusHeld = true
            focusGain = if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) 1f else 0f
            if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) DashRuntime.diag.add("engine sound: focus denied ($r), muted")
        } catch (e: Exception) {
            log("requestAudioFocus failed", e)
        }
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        val am = audioManager
        val req = focusRequest
        if (am == null || req == null) return
        try {
            am.abandonAudioFocusRequest(req)
        } catch (e: Exception) {
            log("abandonAudioFocusRequest failed", e)
        }
    }

    private fun startWorker() {
        val t = Thread({ runAudio() }, "DashEngineSound")
        worker = t
        try {
            t.start()
        } catch (e: Exception) {
            worker = null
            log("audio thread start failed", e)
        }
    }

    // --- audio thread ---

    /** The audio thread is dying unexpectedly: switch off, drop focus, tell the UI. */
    private fun fail(why: String) {
        val wasOn: Boolean
        synchronized(lock) {
            worker = null
            wasOn = wantOn
            wantOn = false
            abandonFocus()
        }
        try {
            DashRuntime.diag.add("engine sound: $why; sound switched off")
        } catch (e: Exception) {
        }
        if (wasOn) {
            val cb = onStateChanged
            if (cb != null) main?.post { cb() }
        }
    }

    /** Drops focus after [IDLE_FOCUS_MS] of silence (rpm null) and takes it again when rpm returns. */
    private fun manageIdleFocus(on: Boolean, rpmKnown: Boolean, silentForMs: Long) {
        if (!on) return
        synchronized(lock) {
            if (!wantOn || released) return
            if (rpmKnown && !focusHeld) {
                requestFocus()
                DashRuntime.diag.add("engine sound: rpm back, focus requested")
            } else if (!rpmKnown && focusHeld && silentForMs >= IDLE_FOCUS_MS) {
                abandonFocus()
                focusGain = 0f
                DashRuntime.diag.add("engine sound: no rpm for ${IDLE_FOCUS_MS}ms, focus abandoned")
            }
        }
    }

    /** Whether the thread should keep going; clears [worker] atomically with the decision to exit. */
    private fun keepRunning(gain: Float): Boolean = synchronized(lock) {
        val go = !stopNow && (wantOn || gain > 0f)
        if (!go) worker = null
        go
    }

    private fun runAudio() {
        var track: AudioTrack? = null
        try {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (e: Exception) {
                // Not fatal: just a less latency-safe thread.
            }
            track = buildTrack()
            if (track == null) {
                fail("no AudioTrack")
                return
            }
            track.play()
            val synth = EngineSynth(SAMPLE_RATE)
            val block = FloatArray(BLOCK)
            val fadeStep = 1f / (FADE_MS * SAMPLE_RATE / 1000f)
            var gain = 0f
            var rpm = 0.0
            var throttle = 0.0
            var silentSinceNs = -1L
            while (keepRunning(gain)) {
                val inp = input
                val fresh = System.nanoTime() - inp.atNs < STALE_MS * 1_000_000L
                val liveRpm = if (fresh) inp.rpm else null
                if (liveRpm != null) {
                    rpm = liveRpm
                    throttle = inp.throttle ?: NEUTRAL_THROTTLE
                }
                val on = synchronized(lock) { wantOn }
                val now = System.nanoTime()
                if (liveRpm == null) {
                    if (silentSinceNs < 0) silentSinceNs = now
                } else {
                    silentSinceNs = -1L
                }
                val silentMs = if (silentSinceNs < 0) 0L else (now - silentSinceNs) / 1_000_000L
                manageIdleFocus(on, liveRpm != null, silentMs)
                val target = if (on && liveRpm != null) focusGain else 0f
                synth.render(block, rpm, throttle, volume())
                for (i in block.indices) {
                    gain = when {
                        gain < target -> minOf(target, gain + fadeStep)
                        gain > target -> maxOf(target, gain - fadeStep)
                        else -> gain
                    }
                    block[i] *= gain
                }
                val wrote = track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
                if (wrote < 0) {
                    fail("AudioTrack.write error $wrote")
                    break
                }
            }
        } catch (e: Exception) {
            fail("audio thread failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                track?.let {
                    if (it.state == AudioTrack.STATE_INITIALIZED) it.stop()
                    it.release()
                }
            } catch (e: Exception) {
                log("AudioTrack stop/release failed", e)
            }
        }
    }

    private fun buildTrack(): AudioTrack? = try {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val bytes = if (min > 0) min * 2 else BLOCK * 4 * 8
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bytes)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            DashRuntime.diag.add("engine sound: AudioTrack not initialized")
            t.release()
            null
        } else {
            t
        }
    } catch (e: Exception) {
        log("AudioTrack build failed", e)
        null
    }

    private fun volume(): Float = try {
        DashRuntime.settings.soundVolume.coerceIn(0f, 1f)
    } catch (e: Exception) {
        DEFAULT_VOLUME
    }

    private fun log(what: String, e: Exception) {
        try {
            DashRuntime.diag.add("engine sound: $what: ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: Exception) {
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44100
        const val BLOCK = 441 // 10 ms
        const val FADE_MS = 300
        const val STALE_MS = 500L
        /** Silence (no rpm) after which the focus is handed back, so music stops ducking. */
        const val IDLE_FOCUS_MS = 1000L
        const val DUCKED_GAIN = 0.3f
        const val DEFAULT_VOLUME = 0.7f
        /** Used when rpm is known but throttle is not: mid-load, and clear of the overrun crackle. */
        const val NEUTRAL_THROTTLE = 10.0
    }
}
