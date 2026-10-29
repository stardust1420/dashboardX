package com.karan.dashboardx.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Procedural inline-six engine voice ("sporty racing game", not a recording). Pure Kotlin so it
 * unit-tests on the JVM.
 *
 * Built from firing events rather than sine partials, which is what makes it sound like an engine:
 * - six cylinders fire evenly, three per crank revolution; each firing is a sharp-rise, slower-decay
 *   exhaust pulse. Each cylinder has a fixed level offset (a real engine's small imbalance, heard as
 *   the straight-six "burble") plus random cycle-to-cycle variation that is largest at idle;
 * - the pulse train goes through fixed exhaust resonances (a sub-bass rumble, a low body, a mid
 *   "honk" that opens with throttle and a high rasp band that grows with throttle and rpm), then an
 *   asymmetric soft clip that adds growl under load;
 * - band-passed intake noise, pulsed by the firings, under throttle;
 * - crackle: pops, short bursts of pops and the odd louder bang, each with a low thump. It plays on
 *   overrun (throttle < 2 %, rpm > 1500) and, more strongly, while decelerating: a sharp throttle
 *   lift or a fast rpm drop (a gear shift), measured against slow followers of throttle and rpm.
 *   The deceleration trigger is what lets it fire on cars whose throttle never reads near zero;
 * - master gain (kept moderate so the pre-limiter sum stays well under 1) and `tanh` limiting.
 *
 * State (phase, pulse envelopes, filters, RNG, last rpm/throttle) carries across [render] calls, so
 * consecutive blocks join seamlessly; rpm and throttle ramp linearly from the previous block's
 * values to the new ones across each block. Not thread-safe: one rendering thread.
 */
class EngineSynth(private val sampleRate: Int = 44100, seed: Long = 1) {
    private val rng = Random(seed)
    private val sr = sampleRate.toDouble()

    /** Crank phase in revolutions over one four-stroke cycle (0 until 2). */
    private var phase = 0.0
    private var nextFiring = 0 // index 0..5 of the next cylinder to fire, at phase nextFiring / 3
    private var lastRpm = Double.NaN
    private var lastThrottle = Double.NaN

    // Exhaust pulse: difference of two decaying exponentials (fast attack, slower decay).
    private var pulseSlow = 0.0
    private var pulseFast = 0.0
    private val fastDecay = exp(-1.0 / (0.00035 * sampleRate))
    private var dcState = 0.0

    private val sub = Resonator(55.0, 0.8)
    private val body = Resonator(100.0, 0.9)
    private val honk = Resonator(380.0, 1.4)
    private val rasp = Resonator(1500.0, 2.2)
    private val intake = Resonator(2600.0, 0.9)
    private val thump = Resonator(85.0, 3.0)

    private var popEnv = 0.0
    private var popTone = 0.0 // LP state for the pop's crack
    private var popThump = 1.0 // thump gain of the current pop (bigger for a bang)
    private val popDecay = exp(-1.0 / (0.006 * sampleRate)) // ~6 ms pops
    private var burstLeft = 0 // pops still to come in the current burst
    private var burstGap = 0 // samples until the next pop of the burst may fire

    // Slow followers (~0.35 s) of throttle (0..1) and rpm: a sharp drop below them is a deceleration.
    private var thrSlow = Double.NaN
    private var rpmSlow = Double.NaN
    private var crackle = 0.0 // 0..1, smoothed once per block

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
        val dcA = 1 - exp(-2 * PI * 25.0 / sr)

        // The rasp band climbs a little with revs; retuned once per block (rpm moves slowly).
        val rpmMidN = ((rpmStart + rpmEnd) / 2 / 7000.0).coerceIn(0.0, 1.0)
        rasp.tune(1300.0 + 900.0 * rpmMidN, 2.2)

        // Crackle level for this block: overrun, or a lift / rpm drop against the slow followers.
        if (thrSlow.isNaN()) thrSlow = thrEnd / 100.0
        if (rpmSlow.isNaN()) rpmSlow = rpmEnd
        val lift = (thrSlow - thrEnd / 100.0) * 3.0
        val drop = (rpmSlow - rpmEnd) / 1500.0
        val overrun = if (thrEnd < 2.0 && rpmEnd > 1500.0) 0.6 else 0.0
        val target = if (rpmEnd > 1100.0) maxOf(overrun, lift, drop).coerceIn(0.0, 1.0) else 0.0
        val follow = 1 - exp(-n / (0.35 * sr))
        thrSlow += follow * (thrEnd / 100.0 - thrSlow)
        rpmSlow += follow * (rpmEnd - rpmSlow)
        crackle += (if (target > crackle) 0.5 else 1 - exp(-n / (0.4 * sr))) * (target - crackle)

        for (i in 0 until n) {
            val k = (i + 1).toDouble() / n
            val r = rpmStart + (rpmEnd - rpmStart) * k
            val thr = (thrStart + (thrEnd - thrStart) * k) / 100.0
            val rpmN = (r / 7000.0).coerceIn(0.0, 1.0)
            val load = 0.3 + 0.7 * thr

            // Fire the next cylinder when the crank reaches its slot (every 1/3 revolution).
            phase += r / 60.0 / sr
            val slot = nextFiring / 3.0
            if (phase >= slot && (nextFiring != 0 || phase < 1.0)) {
                // Cycle-to-cycle roughness: large at a lumpy idle, smooth at high revs.
                val jitter = (0.22 - 0.17 * rpmN) * rng.nextGaussian()
                val amp = CYLINDER_LEVEL[nextFiring] * (1 + jitter).coerceAtLeast(0.2)
                pulseSlow += amp
                pulseFast += amp
                nextFiring = (nextFiring + 1) % 6
            }
            if (phase >= 2.0) phase -= 2.0

            // Pulse length follows the firing period, so slow pulses separate and fast ones merge.
            val period = 1.0 / firingHz(r.coerceAtLeast(300.0))
            val tau = (0.32 * period).coerceIn(0.0012, 0.012)
            pulseSlow *= exp(-1.0 / (tau * sr))
            pulseFast *= fastDecay
            val raw = pulseSlow - pulseFast
            dcState += dcA * (raw - dcState)
            val src = raw - dcState

            // Exhaust: low body, mid honk opening with throttle, high rasp with throttle and revs.
            // Load scales it per sample, so throttle changes ramp smoothly between firings.
            val exhaust = load * 0.55 * src +
                load * 1.4 * sub.next(src) +
                load * 2.0 * body.next(src) +
                load * (0.5 + 0.9 * thr) * honk.next(src) +
                load * thr * (0.25 + 0.95 * rpmN) * rasp.next(src)

            // Growl: asymmetric soft clip (even harmonics), driven harder under load at high revs.
            val drive = 1.0 + 2.2 * thr * (0.3 + 0.7 * rpmN)
            val growl = (tanh(drive * (exhaust + GROWL_BIAS)) - tanh(drive * GROWL_BIAS)) / drive

            // Intake: band-passed noise breathing with each firing, mostly under throttle.
            val white = rng.nextDouble() * 2 - 1
            val breath = intake.next(white) * (0.15 + 0.85 * thr) * (0.3 + 0.7 * rpmN) * (0.4 + 2.0 * raw)

            // Crackle: single pops or quick bursts of 2-4, now and then a louder bang.
            if (burstGap > 0) burstGap--
            if (crackle > 0.05 && popEnv < 0.01 && burstGap == 0) {
                if (burstLeft > 0) {
                    burstLeft--
                    firePop()
                } else if (rng.nextDouble() < crackle * (6.0 + 14.0 * rpmN) / sr) {
                    burstLeft = if (rng.nextDouble() < 0.4) 1 + rng.nextInt(3) else 0
                    firePop()
                }
            }
            var pop = 0.0
            if (popEnv > 1e-4) {
                popTone += 0.3 * (white - popTone)
                pop = popEnv * popTone
                popEnv *= popDecay
            } else {
                popEnv = 0.0
            }
            val bang = thump.next(pop) * 2.5 * popThump

            val sum = (growl * 0.55 + breath * 0.5 + pop * 0.8 + bang) * MASTER * vol
            out[i] = tanh(sum).toFloat()
        }
        lastRpm = rpmEnd
        lastThrottle = thrEnd
    }

    private fun firePop() {
        val big = rng.nextDouble() < 0.15
        popEnv = if (big) 1.0 else 0.4 + 0.5 * rng.nextDouble()
        popThump = if (big) 1.8 else 1.0
        burstGap = ((0.025 + 0.05 * rng.nextDouble()) * sr).toInt() // 25-75 ms between burst pops
    }

    /** Two-pole band-pass (RBJ, 0 dB peak); state carries across blocks. */
    private inner class Resonator(hz: Double, q: Double) {
        private var b0 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            tune(hz, q)
        }

        fun tune(hz: Double, q: Double) {
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
        /** Small fixed per-cylinder differences, in firing order. */
        val CYLINDER_LEVEL = doubleArrayOf(1.00, 0.91, 1.06, 0.95, 1.03, 0.88)
        const val GROWL_BIAS = 0.2
        const val MASTER = 1.35
    }
}
