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
 * - rpm and throttle glide through short followers (~50 ms), so the steppy OBD readings become a
 *   smooth sweep without adding audible lag (the dash's 80 ms frame smoother already sits in front),
 *   and the new block's targets ramp linearly across the block on top of that;
 * - exhaust pops, kept warm rather than crackly: each is a low thump (a damped ~90 Hz resonance)
 *   with a short, muted crack (band-passed noise, ~5 ms). Single pops, quick bursts of 2-3 and now
 *   and then a fuller bang. They play on overrun (throttle < 2 %, rpm > 1500) and when
 *   decelerating: a sharp throttle lift or a fast rpm drop (a gear shift), measured against slow
 *   followers of throttle and rpm, which lets them fire on cars whose throttle never reads near
 *   zero. Steady cruising stays clean.
 *
 * - a [Tune] per drive mode: [Tune.COMFORT] is the sound above; Sport steps up the level,
 *   brightness, low cross-plane rumble (orders 2 and 6) and pop rate, all still clean tones. Sport+
 *   is Sport plus a turbo: a clean whistle that spools up with throttle and revs (pitch and level
 *   follow a lagging boost estimate) and, when boost is dumped on a lift, a soft "pssh" under a
 *   Supra-style compressor flutter ("stu-tu-tu"): smooth, slightly uneven chirps (~380 Hz sliding
 *   to ~200 Hz, each bending down a little) over a deep body tone (~115 to ~60 Hz) and a breath of
 *   air, ~22 a second slowing to ~12
 *   as they die away over about 0.8 s; the note dips and the pops hold back meanwhile so it is heard. Level, brightness, rumble and turbo glide (~150 ms) to a new tune, so a mode change never
 *   clicks.
 *
 * State (crank phase, followers, pop filters, RNG) carries across [render] calls, so consecutive blocks join
 * seamlessly. Not thread-safe: one rendering thread.
 */
class EngineSynth(private val sampleRate: Int = 44100, seed: Long = 1) {
    /**
     * How the voice is tuned. [level] scales the note, [bright] is added to the brightness that opens
     * the upper orders, [growl] scales orders 2 and 6, [popRate] / [popLevel] scale how often and how
     * loud pops are, overrun pops start above [overrunRpm], and [turbo] (0..1) mixes in the turbo.
     */
    class Tune(
        val level: Double,
        val bright: Double,
        val growl: Double,
        val popRate: Double,
        val popLevel: Double,
        val overrunRpm: Double,
        val turbo: Double = 0.0,
    ) {
        companion object {
            /** The original smooth Lexus-style note. */
            val COMFORT = Tune(level = 1.0, bright = 0.0, growl = 1.0, popRate = 1.0, popLevel = 1.0, overrunRpm = 1500.0)
            val SPORT = Tune(level = 1.18, bright = 0.1, growl = 1.3, popRate = 1.8, popLevel = 1.15, overrunRpm = 1300.0)
            /** Sport's note, no louder, with the turbo. */
            val SPORT_PLUS = Tune(level = 1.18, bright = 0.1, growl = 1.3, popRate = 1.8, popLevel = 1.15, overrunRpm = 1300.0, turbo = 1.0)
        }
    }

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

    // Tune values gliding toward the requested [Tune] (NaN until the first block).
    private var tuneLevel = Double.NaN
    private var tuneBright = Double.NaN
    private var tuneGrowl = Double.NaN
    private val tuneA = 1 - exp(-1.0 / (TUNE_GLIDE_S * sampleRate))
    private var tuneTurbo = Double.NaN

    // Turbo: boost (0..1) spools up slowly and dumps fast; its own RNG keeps the pops unchanged.
    private val turboRng = Random(seed + 1)
    private var boost = 0.0
    private val spoolA = 1 - exp(-1.0 / (SPOOL_S * sampleRate))
    private val dumpA = 1 - exp(-1.0 / (DUMP_S * sampleRate))
    private var whistlePhase = 0.0
    private var bovEnv = 0.0
    private val bovDecay = exp(-1.0 / (BOV_DECAY_S * sampleRate))
    private val bovBand = Resonator(1800.0, 0.9)
    private var bovLp = 0.0
    private val bovLpA = 1 - exp(-2 * PI * 2500.0 / sampleRate) // muffles the hiss into a soft "pssh"
    // Flutter: a train of short air puffs whose rate slows as it fades.
    private var flutterEnv = 0.0
    private var flutterPhase = 0.0
    private var flutterHz = 0.0
    private val flutterDecay = exp(-1.0 / (FLUTTER_DECAY_S * sampleRate))
    private val flutterSlow = exp(-1.0 / (FLUTTER_SLOW_S * sampleRate))
    private var flutterPitch = 0.0 // Hz of the chirp, sliding down
    private var flutterTone = 0.0 // phase of the chirp
    private var flutterBody = 0.0 // phase of the low body tone under each chirp
    private var tuGain = 1.0 // each "tu" a little stronger or weaker than the last
    private val flutterAir = Resonator(600.0, 1.2) // a breath of air on each chirp

    /** Firing frequency of a four-stroke V8: four power strokes per revolution. */
    fun firingHz(rpm: Double): Double = rpm / 60.0 * 4.0

    fun render(out: FloatArray, rpm: Double, throttlePct: Double, volume: Float, tune: Tune = Tune.COMFORT) {
        val n = out.size
        if (n == 0) return
        val rpmEnd = rpm.coerceIn(0.0, 9000.0)
        val thrEnd = throttlePct.coerceIn(0.0, 100.0) / 100.0
        val rpmStart = if (lastRpm.isNaN()) rpmEnd else lastRpm
        val thrStart = if (lastThrottle.isNaN()) thrEnd else lastThrottle
        if (rpmGlide.isNaN()) rpmGlide = rpmStart
        if (thrGlide.isNaN()) thrGlide = thrStart
        if (tuneLevel.isNaN()) {
            tuneLevel = tune.level
            tuneBright = tune.bright
            tuneGrowl = tune.growl
            tuneTurbo = tune.turbo
        }
        val vol = volume.coerceIn(0f, 1f).toDouble()
        val nyquistSafe = 0.4 * sr

        // Pop level for this block: overrun, or a lift / rpm drop against the slow followers.
        if (thrSlow.isNaN()) thrSlow = thrEnd
        if (rpmSlow.isNaN()) rpmSlow = rpmEnd
        val lift = (thrSlow - thrEnd) * 3.0
        val drop = (rpmSlow - rpmEnd) / 1500.0
        val overrun = if (thrEnd < 0.02 && rpmEnd > tune.overrunRpm) 0.6 else 0.0
        val target = if (rpmEnd > 1100.0) maxOf(overrun, lift, drop).coerceIn(0.0, 1.0) else 0.0
        val follow = 1 - exp(-n / (0.35 * sr))
        thrSlow += follow * (thrEnd - thrSlow)
        rpmSlow += follow * (rpmEnd - rpmSlow)
        crackle += (if (target > crackle) 0.5 else 1 - exp(-n / (0.4 * sr))) * (target - crackle)

        for (i in 0 until n) {
            val k = (i + 1).toDouble() / n
            rpmGlide += rpmA * (rpmStart + (rpmEnd - rpmStart) * k - rpmGlide)
            thrGlide += thrA * (thrStart + (thrEnd - thrStart) * k - thrGlide)
            tuneLevel += tuneA * (tune.level - tuneLevel)
            tuneBright += tuneA * (tune.bright - tuneBright)
            tuneGrowl += tuneA * (tune.growl - tuneGrowl)
            tuneTurbo += tuneA * (tune.turbo - tuneTurbo)
            val r = rpmGlide
            val thr = thrGlide
            val rpmN = (r / 7000.0).coerceIn(0.0, 1.0)
            // Brightness grows linearly with revs and throttle: 0.2 at idle, 1 at full song.
            val bright = (0.2 + 0.8 * (0.6 * rpmN + 0.4 * thr) + tuneBright).coerceAtMost(MAX_BRIGHT)
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
                if (order == 2.0 || order == 6.0) w *= tuneGrowl
                // Fade partials out just below the alias limit instead of cutting them.
                val edge = ((nyquistSafe - hz) / (0.1 * sr)).coerceAtMost(1.0)
                v += w * edge * sin(2 * PI * order * phase)
            }

            val level = (0.45 + 0.55 * thr) * (0.55 + 0.45 * rpmN) * tuneLevel
            val warm = tanh(WARMTH * v) / WARMTH

            // Pops: single ones or quick bursts of 2-3, now and then a fuller bang.
            if (burstGap > 0) burstGap--
            if (crackle > 0.05 && popEnv < 0.01 && burstGap == 0) {
                if (burstLeft > 0) {
                    burstLeft--
                    firePop()
                } else if (rng.nextDouble() < crackle * (3.0 + 7.0 * rpmN) * tune.popRate / sr) {
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

            val turbo = turbo(r, thr)
            // While the turbo flutters, the note dips and the pops hold back so the "stu-tu-tu" is heard.
            val clear = flutterEnv * tuneTurbo
            val note = warm * level * MASTER * (1 - FLUTTER_DUCK * clear)
            val pops = pop * POP_LEVEL * tune.popLevel * (1 - FLUTTER_POP_DUCK * clear)
            out[i] = (tanh((note + pops + turbo) * vol * 1.2) / 1.2).toFloat()
        }
        lastRpm = rpmEnd
        lastThrottle = thrEnd
    }

    /** One sample of turbo (whistle + blow-off), already scaled by the gliding [Tune.turbo]. */
    private fun turbo(rpm: Double, thr: Double): Double {
        val boostTarget = thr * ((rpm - TURBO_FROM_RPM) / (TURBO_FULL_RPM - TURBO_FROM_RPM)).coerceIn(0.0, 1.0)
        // A sharp fall in demand with real boost built up dumps it through the blow-off valve.
        if (boost > 0.35 && boostTarget < boost - 0.3 && bovEnv < 0.05 && flutterEnv < 0.05) {
            bovEnv = boost
            flutterEnv = boost
            flutterPhase = 0.0
            flutterHz = FLUTTER_START_HZ
            flutterPitch = FLUTTER_PITCH_HI
        }
        boost += (if (boostTarget > boost) spoolA else dumpA) * (boostTarget - boost)
        if (tuneTurbo < 1e-4) {
            bovEnv = 0.0
            flutterEnv = 0.0
            return 0.0
        }
        whistlePhase += (WHISTLE_LO_HZ + (WHISTLE_HI_HZ - WHISTLE_LO_HZ) * boost) / sr
        if (whistlePhase >= 1.0) whistlePhase -= 1.0
        val whistle = (sin(2 * PI * whistlePhase) + 0.2 * sin(4 * PI * whistlePhase)) * boost * boost * WHISTLE
        val bov = if (bovEnv > 1e-4) {
            bovLp += bovLpA * (bovBand.next(turboRng.nextDouble() * 2 - 1) - bovLp)
            val v = bovLp * bovEnv * BOV
            bovEnv *= bovDecay
            v
        } else {
            bovEnv = 0.0
            0.0
        }
        val flutter = if (flutterEnv > 1e-3) {
            flutterPhase += flutterHz / sr
            if (flutterPhase >= 1.0) {
                flutterPhase -= 1.0
                tuGain = 0.7 + 0.3 * turboRng.nextDouble()
            }
            flutterHz = FLUTTER_END_HZ + (flutterHz - FLUTTER_END_HZ) * flutterSlow
            flutterPitch = FLUTTER_PITCH_LO + (flutterPitch - FLUTTER_PITCH_LO) * flutterSlow
            // Each "tu": a smooth swell and fade (no hard edge, so it chirps instead of knocking).
            val c = 0.5 - 0.5 * cos(2 * PI * flutterPhase)
            val shape = c * c
            // Each chirp bends down ~30 % across its cycle.
            flutterTone += flutterPitch * (1 + 0.3 * (1 - flutterPhase)) / sr
            if (flutterTone >= 1.0) flutterTone -= 1.0
            flutterBody += flutterPitch * BODY_RATIO / sr
            if (flutterBody >= 1.0) flutterBody -= 1.0
            val w = 2 * PI * flutterTone
            val chirp = sin(w) + 0.35 * sin(2 * w) + 0.15 * sin(3 * w)
            val body = sin(2 * PI * flutterBody)
            val air = flutterAir.next(turboRng.nextDouble() * 2 - 1)
            val v = (chirp * FLUTTER_CHIRP + body * FLUTTER_BODY + air * FLUTTER_AIR) * shape * tuGain * flutterEnv
            flutterEnv *= flutterDecay
            v
        } else {
            flutterEnv = 0.0
            0.0
        }
        return (whistle + bov + flutter) * tuneTurbo
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
        const val RPM_GLIDE_S = 0.05
        const val THR_GLIDE_S = 0.05
        const val TUNE_GLIDE_S = 0.15
        /** Cap on the brightness so the hottest tune still sounds clean, not raspy. */
        const val MAX_BRIGHT = 1.1
        const val WARMTH = 0.8
        /** Keeps full-scale peaks around 0.45: comfortable, with headroom over the music. */
        const val MASTER = 0.38
        const val CRACK = 0.8
        const val THUMP = 6.0
        /** Pops stand out clearly above the note on a lift or shift, but stay short and well under full scale. */
        const val POP_LEVEL = 0.4

        // Turbo: boost builds from TURBO_FROM_RPM and is full by TURBO_FULL_RPM at full throttle.
        const val TURBO_FROM_RPM = 1800.0
        const val TURBO_FULL_RPM = 4000.0
        const val SPOOL_S = 0.6 // lag before the whistle builds
        const val DUMP_S = 0.12
        const val WHISTLE_LO_HZ = 1400.0
        const val WHISTLE_HI_HZ = 3800.0
        /** Whistle sits well under the note: present, not piercing. */
        const val WHISTLE = 0.035
        const val BOV = 0.12
        const val FLUTTER_CHIRP = 0.22
        const val FLUTTER_AIR = 0.4
        const val FLUTTER_BODY = 0.45
        const val BODY_RATIO = 0.3 // body tone at under a third of the chirp pitch
        const val FLUTTER_PITCH_HI = 380.0
        const val FLUTTER_PITCH_LO = 200.0
        /** How far the note dips (0..1) and the pops hold back at the peak of a flutter. */
        const val FLUTTER_DUCK = 0.5
        const val FLUTTER_POP_DUCK = 0.8
        const val FLUTTER_START_HZ = 22.0
        const val FLUTTER_END_HZ = 12.0
        const val FLUTTER_SLOW_S = 0.45
        const val FLUTTER_DECAY_S = 0.38
        const val BOV_DECAY_S = 0.12
    }
}
