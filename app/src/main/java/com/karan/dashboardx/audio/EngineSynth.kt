package com.karan.dashboardx.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Procedural inline-six engine voice ("pleasant racing game", not a recording). Pure Kotlin so it
 * unit-tests on the JVM.
 *
 * Layers, per sample:
 * - engine orders 1.5, 3, 4.5, 6, 9 and 12 of the crank rate (rpm/60); order 3 (the six-cylinder
 *   firing rate) dominates, and the high orders brighten with throttle;
 * - a soft-clipped "rasp" copy of that tone that grows with throttle and rpm;
 * - band-passed noise for intake/exhaust breath, pulsed by the firing rate;
 * - on overrun (throttle < 2 %, rpm > 1500), sparse random crackle pops;
 * - master gain (kept moderate so the pre-limiter sum stays well under 1) and `tanh` limiting.
 *
 * State (phase, filters, RNG, last rpm/throttle) carries across [render] calls, so consecutive
 * blocks join seamlessly; rpm and throttle ramp linearly from the previous block's values to the
 * new ones across each block. Not thread-safe: one rendering thread.
 */
class EngineSynth(private val sampleRate: Int = 44100, seed: Long = 1) {
    private val rng = Random(seed)

    /** Crank phase in revolutions, wrapped at 2 so the half-order (1.5, 4.5) partials stay continuous. */
    private var phase = 0.0
    private var lastRpm = Double.NaN
    private var lastThrottle = Double.NaN

    private var noiseLo = 0.0 // one-pole LP state: removes rumble (subtracted)
    private var noiseHi = 0.0 // one-pole LP state: sets the breath brightness
    private var popEnv = 0.0
    private var popTone = 0.0 // LP state for the pop's body
    private val popDecay = exp(-1.0 / (0.004 * sampleRate)) // ~4 ms pops

    /** Firing frequency of a four-stroke inline six: three power strokes per revolution. */
    fun firingHz(rpm: Double): Double = rpm / 60.0 * 3.0

    fun render(out: FloatArray, rpm: Double, throttlePct: Double, volume: Float) {
        val n = out.size
        if (n == 0) return
        val rpmEnd = rpm.coerceIn(0.0, 9000.0)
        val thrEnd = throttlePct.coerceIn(0.0, 100.0)
        val rpmStart = if (lastRpm.isNaN()) rpmEnd else lastRpm
        val thrStart = if (lastThrottle.isNaN()) thrEnd else lastThrottle
        val vol = volume.coerceIn(0f, 1f).toDouble()
        val sr = sampleRate.toDouble()
        val noiseLoA = 1 - exp(-2 * PI * 180.0 / sr)

        for (i in 0 until n) {
            val k = (i + 1).toDouble() / n
            val r = rpmStart + (rpmEnd - rpmStart) * k
            val thr = (thrStart + (thrEnd - thrStart) * k) / 100.0
            val rpmN = (r / 7000.0).coerceIn(0.0, 1.0)

            phase += r / 60.0 / sr
            if (phase >= 2.0) phase -= 2.0
            val w = 2 * PI * phase

            // Engine orders, normalised to a peak of about 1. High orders open up with throttle.
            val bright = 0.35 + 0.65 * thr
            val tone = (
                0.30 * sin(1.5 * w + 0.4) +
                    1.00 * sin(3.0 * w) +
                    0.35 * sin(4.5 * w + 1.1) +
                    0.45 * bright * sin(6.0 * w + 0.7) +
                    0.20 * bright * sin(9.0 * w + 2.0) +
                    0.12 * bright * sin(12.0 * w + 0.3)
                ) / 2.42

            // Rasp: a driven, soft-clipped copy of the tone that thickens under load and revs.
            val raspMix = 0.45 * thr * (0.3 + 0.7 * rpmN)
            val drive = 2.0 + 4.0 * rpmN
            val voiced = tone * (1 - raspMix) + tanh(drive * tone) * raspMix * 0.8

            // Breath: band-passed noise, pulsed at the firing rate.
            val white = rng.nextDouble() * 2 - 1
            val cutHz = 700.0 + 2200.0 * rpmN * (0.4 + 0.6 * thr)
            noiseHi += (1 - exp(-2 * PI * cutHz / sr)) * (white - noiseHi)
            noiseLo += noiseLoA * (noiseHi - noiseLo)
            val pulse = 0.55 + 0.45 * sin(3.0 * w)
            val breath = (noiseHi - noiseLo) * pulse * (0.25 + 0.75 * thr) * (0.4 + 0.6 * rpmN)

            // Overrun crackle: rare, short, decaying noise bursts.
            if (thr < 0.02 && r > 1500.0 && popEnv < 0.01) {
                val perSecond = 3.0 + 7.0 * rpmN
                if (rng.nextDouble() < perSecond / sr) popEnv = 0.5 + 0.5 * rng.nextDouble()
            }
            var pop = 0.0
            if (popEnv > 1e-4) {
                popTone += 0.35 * (white - popTone)
                pop = popEnv * popTone
                popEnv *= popDecay
            } else {
                popEnv = 0.0
            }

            val load = 0.35 + 0.65 * thr
            val sum = (voiced * load * 0.75 + breath * 0.9 + pop * 0.9) * 0.6 * vol
            out[i] = tanh(sum).toFloat()
        }
        lastRpm = rpmEnd
        lastThrottle = thrEnd
    }
}
