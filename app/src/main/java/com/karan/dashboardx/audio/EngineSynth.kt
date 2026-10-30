package com.karan.dashboardx.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Procedural V8 engine voice in the smooth, linear style of a Lexus V8 (not a recording). Pure
 * Kotlin so it unit-tests on the JVM.
 *
 * The note is built from clean engine-order partials, with no noise or random variation, so it
 * reads as a steady musical tone rather than hiss:
 * - a V8 fires four times per crank revolution, so order 4 is the fundamental; orders 8 and 12 give
 *   it body, and orders 2 and 6 add the low cross-plane hum. A faint order 1 and 3 give a soft
 *   burble at idle that fades away as revs rise;
 * - the upper orders (16 to 32, the "induction" sheen) open up smoothly with rpm and throttle, so
 *   the note gets brighter in a straight line as it revs instead of turning raspy;
 * - loudness rises gently with throttle and rpm, then a mild `tanh` warms it and keeps the peaks low;
 * - rpm and throttle glide through slow followers (~120 ms and ~200 ms), so the steppy OBD readings
 *   become a smooth sweep, and the new block's targets ramp linearly across the block on top of that;
 * - exhaust pops, kept warm rather than crackly: each is a low thump (a damped ~90 Hz resonance)
 *   with a short, muted crack (band-passed noise, ~5 ms). Single pops, quick bursts of 2-3 and now
 *   and then a fuller bang. They play on overrun (throttle < 2 %, rpm > 1500) and when
 *   decelerating: a sharp throttle lift or a fast rpm drop (a gear shift), measured against slow
 *   followers of throttle and rpm, which lets them fire on cars whose throttle never reads near
 *   zero. Steady cruising stays clean.
 *
 * State (crank phase, followers, pop filters, RNG) carries across [render] calls, so consecutive blocks join
 * seamlessly. Not thread-safe: one rendering thread.
 */
class EngineSynth(private val sampleRate: Int = 44100, seed: Long = 1) {
    private val sr = sampleRate.toDouble()
    private val rng = Random(seed)

    /** Crank phase in revolutions (0 until 1); every order is a whole number, so it wraps at 1. */
    private var phase = 0.0
    private var lastRpm = Double.NaN
    private var lastThrottle = Double.NaN
    private var rpmGlide = Double.NaN
    private var thrGlide = Double.NaN
    private val rpmA = 1 - exp(-1.0 / (RPM_GLIDE_S * sampleRate))
    private val thrA = 1 - exp(-1.0 / (THR_GLIDE_S * sampleRate))

    private var popEnv = 0.0
    private var popGain = 1.0 // thump gain of the current pop (bigger for a bang)
    private val popDecay = exp(-1.0 / (0.005 * sampleRate)) // ~5 ms pops
    private val crackBand = Resonator(1600.0, 0.8)
    private var crackLp = 0.0 // one-pole low-pass that takes the edge off the crack
    private val crackLpA = 1 - exp(-2 * PI * 3500.0 / sampleRate)
    private val thump = Resonator(90.0, 4.0)
    private var burstLeft = 0 // pops still to come in the current burst
    private var burstGap = 0 // samples until the next pop of the burst may fire

    // Slow followers (~0.35 s) of throttle (0..1) and rpm: a sharp drop below them is a deceleration.
    private var thrSlow = Double.NaN
    private var rpmSlow = Double.NaN
    private var crackle = 0.0 // 0..1, smoothed once per block

    /** Firing frequency of a four-stroke V8: four power strokes per revolution. */
    fun firingHz(rpm: Double): Double = rpm / 60.0 * 4.0

    fun render(out: FloatArray, rpm: Double, throttlePct: Double, volume: Float) {
        val n = out.size
        if (n == 0) return
        val rpmEnd = rpm.coerceIn(0.0, 9000.0)
        val thrEnd = throttlePct.coerceIn(0.0, 100.0) / 100.0
        val rpmStart = if (lastRpm.isNaN()) rpmEnd else lastRpm
        val thrStart = if (lastThrottle.isNaN()) thrEnd else lastThrottle
        if (rpmGlide.isNaN()) rpmGlide = rpmStart
        if (thrGlide.isNaN()) thrGlide = thrStart
        val vol = volume.coerceIn(0f, 1f).toDouble()
        val nyquistSafe = 0.4 * sr

        // Pop level for this block: overrun, or a lift / rpm drop against the slow followers.
        if (thrSlow.isNaN()) thrSlow = thrEnd
        if (rpmSlow.isNaN()) rpmSlow = rpmEnd
        val lift = (thrSlow - thrEnd) * 3.0
        val drop = (rpmSlow - rpmEnd) / 1500.0
        val overrun = if (thrEnd < 0.02 && rpmEnd > 1500.0) 0.6 else 0.0
        val target = if (rpmEnd > 1100.0) maxOf(overrun, lift, drop).coerceIn(0.0, 1.0) else 0.0
        val follow = 1 - exp(-n / (0.35 * sr))
        thrSlow += follow * (thrEnd - thrSlow)
        rpmSlow += follow * (rpmEnd - rpmSlow)
        crackle += (if (target > crackle) 0.5 else 1 - exp(-n / (0.4 * sr))) * (target - crackle)

        for (i in 0 until n) {
            val k = (i + 1).toDouble() / n
            rpmGlide += rpmA * (rpmStart + (rpmEnd - rpmStart) * k - rpmGlide)
            thrGlide += thrA * (thrStart + (thrEnd - thrStart) * k - thrGlide)
            val r = rpmGlide
            val thr = thrGlide
            val rpmN = (r / 7000.0).coerceIn(0.0, 1.0)
            // Brightness grows linearly with revs and throttle: 0.2 at idle, 1 at full song.
            val bright = 0.2 + 0.8 * (0.6 * rpmN + 0.4 * thr)
            val burble = (1 - 1.6 * rpmN).coerceAtLeast(0.0)

            phase += r / 60.0 / sr
            if (phase >= 1.0) phase -= 1.0
            val revHz = r / 60.0

            var v = 0.0
            for (j in ORDERS.indices) {
                val order = ORDERS[j]
                val hz = order * revHz
                if (hz >= nyquistSafe) continue
                var w = WEIGHTS[j]
                if (order >= 16) w *= bright * bright
                if (order == 1.0 || order == 3.0) w *= burble
                // Fade partials out just below the alias limit instead of cutting them.
                val edge = ((nyquistSafe - hz) / (0.1 * sr)).coerceAtMost(1.0)
                v += w * edge * sin(2 * PI * order * phase)
            }

            val level = (0.45 + 0.55 * thr) * (0.55 + 0.45 * rpmN)
            val warm = tanh(WARMTH * v) / WARMTH

            // Pops: single ones or quick bursts of 2-3, now and then a fuller bang.
            if (burstGap > 0) burstGap--
            if (crackle > 0.05 && popEnv < 0.01 && burstGap == 0) {
                if (burstLeft > 0) {
                    burstLeft--
                    firePop()
                } else if (rng.nextDouble() < crackle * (3.0 + 7.0 * rpmN) / sr) {
                    burstLeft = if (rng.nextDouble() < 0.35) 1 + rng.nextInt(2) else 0
                    firePop()
                }
            }
            val pop = if (popEnv > 1e-4) {
                crackLp += crackLpA * (crackBand.next(rng.nextDouble() * 2 - 1) - crackLp)
                val p = popEnv * crackLp * CRACK + thump.next(popEnv * popEnv) * THUMP * popGain
                popEnv *= popDecay
                p
            } else {
                popEnv = 0.0
                thump.next(0.0) * THUMP * popGain // let the thump ring out
            }

            out[i] = (tanh((warm * level * MASTER + pop * POP_LEVEL) * vol * 1.2) / 1.2).toFloat()
        }
        lastRpm = rpmEnd
        lastThrottle = thrEnd
    }

    private fun firePop() {
        val big = rng.nextDouble() < 0.12
        popEnv = if (big) 1.0 else 0.45 + 0.4 * rng.nextDouble()
        popGain = if (big) 1.3 else 1.0
        burstGap = ((0.04 + 0.05 * rng.nextDouble()) * sr).toInt() // 40-90 ms between burst pops
    }

    /** Two-pole band-pass (RBJ, 0 dB peak); state carries across blocks. */
    private inner class Resonator(hz: Double, q: Double) {
        private val b0: Double
        private val a1: Double
        private val a2: Double
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            val w0 = 2 * PI * hz / sr
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            b0 = alpha / a0
            a1 = -2 * cos(w0) / a0
            a2 = (1 - alpha) / a0
        }

        fun next(x: Double): Double {
            val y = b0 * (x - x2) - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }
    }

    private companion object {
        /** Engine orders (multiples of crank rate) and their base weights. */
        val ORDERS = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0, 20.0, 24.0, 32.0)
        val WEIGHTS = doubleArrayOf(0.14, 0.45, 0.12, 1.0, 0.18, 0.55, 0.22, 0.22, 0.13, 0.09, 0.05)
        const val RPM_GLIDE_S = 0.12
        const val THR_GLIDE_S = 0.2
        const val WARMTH = 0.8
        /** Keeps full-scale peaks around 0.45: comfortable, with headroom over the music. */
        const val MASTER = 0.38
        const val CRACK = 0.8
        const val THUMP = 6.0
        /** Pops stand out clearly above the note on a lift or shift, but stay short and well under full scale. */
        const val POP_LEVEL = 0.4
    }
}
