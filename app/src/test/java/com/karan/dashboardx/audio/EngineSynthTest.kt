package com.karan.dashboardx.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class EngineSynthTest {
    private fun render(
        rpm: Double, throttle: Double, volume: Float, n: Int = 4410, warmup: Int = 0,
        tune: EngineSynth.Tune = EngineSynth.Tune.COMFORT,
    ): FloatArray {
        val s = EngineSynth()
        if (warmup > 0) s.render(FloatArray(warmup), rpm, throttle, volume, tune)
        val out = FloatArray(n)
        s.render(out, rpm, throttle, volume, tune)
        return out
    }

    private fun maxStep(x: FloatArray): Float = (1 until x.size).maxOf { abs(x[it] - x[it - 1]) }

    private fun rms(x: FloatArray): Double = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    /** Two cascaded one-pole low-passes at [cutHz]; strips noise and high orders. */
    private fun lowPass(x: FloatArray, cutHz: Double, sr: Int = 44100): DoubleArray {
        val a = 1 - exp(-2 * PI * cutHz / sr)
        var y1 = 0.0
        var y2 = 0.0
        return DoubleArray(x.size) { i ->
            y1 += a * (x[i] - y1)
            y2 += a * (y1 - y2)
            y2
        }
    }

    private fun zeroCrossings(x: DoubleArray): Int {
        val mean = x.average()
        var n = 0
        for (i in 1 until x.size) if ((x[i - 1] - mean) < 0 != (x[i] - mean) < 0) n++
        return n
    }

    @Test fun firingHz_v8() {
        val s = EngineSynth()
        assertEquals(200.0, s.firingHz(3000.0), 1e-9)
        assertEquals(800.0 / 15, s.firingHz(800.0), 1e-9)
    }

    @Test fun render_silentAtZeroVolume() {
        val out = render(4000.0, 60.0, 0f)
        for (v in out) assertEquals(0f, v, 0f)
    }

    @Test fun render_louderWithThrottle() {
        val hi = rms(render(3000.0, 90.0, 1f))
        val lo = rms(render(3000.0, 5.0, 1f))
        assertTrue("hi=$hi lo=$lo", hi > 1.5 * lo)
    }

    /** Kept gentle: even flat out at full volume the peaks stay well under full scale. */
    @Test fun render_gentlePeaks() {
        val peak = render(7000.0, 100.0, 1f, n = 44100).maxOf { abs(it) }
        assertTrue("peak $peak", peak.isFinite() && peak < 0.5f)
    }

    @Test fun render_pitchTracksRpm() {
        val zLo = zeroCrossings(lowPass(render(3000.0, 50.0, 1f, n = 44100), 400.0))
        val zHi = zeroCrossings(lowPass(render(6000.0, 50.0, 1f, n = 44100), 400.0))
        assertTrue("zLo=$zLo zHi=$zHi", zHi > 1.6 * zLo)
    }

    /** A steady note has no noise: one crank revolution later the waveform repeats. */
    @Test fun render_periodicAtSteadyRpm() {
        val out = render(3000.0, 40.0, 1f, n = 8820, warmup = 44100)
        val rev = 882 // 3000 rpm = 50 rev/s = 882 samples
        for (i in 0 until 4000) assertEquals("sample $i", out[i], out[i + rev], 2e-3f)
    }

    @Test fun render_phaseContinuousAcrossBlocks() {
        val whole = EngineSynth(seed = 7)
        val split = EngineSynth(seed = 7)
        val a = FloatArray(882)
        whole.render(a, 3500.0, 40.0, 1f)
        val b1 = FloatArray(441)
        val b2 = FloatArray(441)
        split.render(b1, 3500.0, 40.0, 1f)
        split.render(b2, 3500.0, 40.0, 1f)
        assertArrayEquals(a, b1 + b2, 1e-6f)
    }

    /** A sudden OBD jump (idle to 6000 rpm, 10 to 100 % throttle) glides: no clicks between samples. */
    @Test fun render_glidesOverInputSteps() {
        val s = EngineSynth()
        s.render(FloatArray(4410), 800.0, 10.0, 1f)
        val out = FloatArray(4410)
        s.render(out, 6000.0, 100.0, 1f)
        var maxStep = 0f
        for (i in 1 until out.size) maxStep = maxOf(maxStep, abs(out[i] - out[i - 1]))
        assertTrue("max step $maxStep", maxStep < 0.1f)
    }

    /** A throttle stab is heard at once: 150 ms later the note is already close to its new loudness. */
    @Test fun render_respondsQuicklyToThrottle() {
        val s = EngineSynth()
        s.render(FloatArray(44100), 3000.0, 10.0, 1f)
        repeat(15) { s.render(FloatArray(441), 3000.0, 90.0, 1f) } // 10 ms blocks, as the audio thread renders
        val after = FloatArray(441)
        s.render(after, 3000.0, 90.0, 1f)
        val steady = rms(render(3000.0, 90.0, 1f, n = 441, warmup = 44100))
        assertTrue("150 ms after the stab: ${rms(after)} vs steady $steady", rms(after) > 0.93 * steady)
    }

    /** Lifting off at speed (a shift) pops: peaks rise clearly above the steady note at the same rpm. */
    @Test fun render_popsOnLift() {
        for (seed in 1L..4L) {
            val s = EngineSynth(seed = seed)
            s.render(FloatArray(44100), 5000.0, 70.0, 1f)
            val lifted = FloatArray(44100)
            s.render(lifted, 3200.0, 10.0, 1f)
            val steady = render(3200.0, 10.0, 1f, n = 44100, warmup = 44100)
            val pk = lifted.maxOf { abs(it) }
            val base = steady.maxOf { abs(it) }
            assertTrue("seed $seed: lifted peak $pk steady peak $base", pk > 1.3 * base)
            assertTrue("seed $seed: pops stay gentle, peak $pk", pk < 0.6f)
        }
    }

    /** Sport is louder than Comfort; Sport+ is no louder than Sport (it only adds the turbo). */
    @Test fun tune_loudnessSteps() {
        val comfort = rms(render(3000.0, 50.0, 1f, n = 44100, warmup = 44100))
        val sport = rms(render(3000.0, 50.0, 1f, n = 44100, warmup = 44100, tune = EngineSynth.Tune.SPORT))
        val plus = rms(render(3000.0, 50.0, 1f, n = 44100, warmup = 44100, tune = EngineSynth.Tune.SPORT_PLUS))
        assertTrue("comfort=$comfort sport=$sport", sport > 1.1 * comfort)
        assertTrue("sport=$sport plus=$plus", plus < 1.05 * sport)
        val peak = render(7000.0, 100.0, 1f, n = 44100, tune = EngineSynth.Tune.SPORT_PLUS).maxOf { abs(it) }
        assertTrue("peak $peak", peak < 0.55f)
    }

    /** Sport is still a clean tone at steady revs: no noise, the waveform repeats every revolution. */
    @Test fun tune_sportStaysPeriodic() {
        val out = render(3000.0, 40.0, 1f, n = 8820, warmup = 44100, tune = EngineSynth.Tune.SPORT)
        for (i in 0 until 4000) assertEquals("sample $i", out[i], out[i + 882], 2e-3f)
    }

    /** Coasting at 1400 rpm: Comfort stays clean, Sport (overrun pops from 1300 rpm) pops. */
    @Test fun tune_sportPopsWhereComfortDoesNot() {
        val comfort = render(1400.0, 0.0, 1f, n = 88200, warmup = 44100)
        val plus = render(1400.0, 0.0, 1f, n = 88200, warmup = 44100, tune = EngineSynth.Tune.SPORT)
        val cPeak = comfort.maxOf { abs(it) }
        val pPeak = plus.maxOf { abs(it) }
        assertTrue("comfort coasting has no pops: peak $cPeak", cPeak < 0.2f)
        assertTrue("sport pops: peak $pPeak vs comfort $cPeak", pPeak > 1.5f * cPeak)
    }

    /** Sport+ minus Sport, rendered side by side through the same inputs: the turbo on its own. */
    private fun turboOnly(steps: List<Pair<Double, Double>>, blocks: Int): List<Double> {
        val sport = EngineSynth()
        val plus = EngineSynth()
        val a = FloatArray(441)
        val b = FloatArray(441)
        val out = ArrayList<Double>()
        for ((rpm, thr) in steps) repeat(blocks) {
            sport.render(a, rpm, thr, 1f, EngineSynth.Tune.SPORT)
            plus.render(b, rpm, thr, 1f, EngineSynth.Tune.SPORT_PLUS)
            out.add(sqrt(a.indices.sumOf { i -> (b[i] - a[i]).toDouble().let { it * it } } / a.size))
        }
        return out // rms of the turbo per 10 ms block
    }

    /** The whistle lags the throttle (spool) and builds to a clear level; there is none off boost. */
    @Test fun turbo_whistleSpoolsUp() {
        val t = turboOnly(listOf(4000.0 to 5.0, 4000.0 to 100.0), blocks = 200)
        val offBoost = t[199]
        val justStabbed = t[205]
        val spooled = t[399]
        assertTrue("off boost $offBoost", offBoost < 1e-3)
        assertTrue("spool lag: 50 ms $justStabbed vs 2 s $spooled", justStabbed < 0.3 * spooled)
        assertTrue("audible whistle $spooled", spooled > 0.01)
    }

    /** Lifting off boost gives a blow-off with a "stu-tu-tu" flutter that dies away; the turbo is then silent. */
    @Test fun turbo_flutterOnLift() {
        val t = turboOnly(listOf(4500.0 to 100.0, 3500.0 to 0.0), blocks = 200)
        val pssh = (200 until 215).maxOf { t[it] }
        val after = t[399]
        assertTrue("blow-off $pssh", pssh > 0.01)
        assertTrue("silent after: $after", after < 1e-3)
        // Separate "tu"s: the 10 ms block level swells and falls several times in the first 0.6 s.
        var swells = 0
        for (i in 202 until 260) if (t[i] > 1.4 * t[i - 2] && t[i] > 0.004) swells++
        assertTrue("flutter swells $swells", swells >= 4)
    }

    /** Switching mode mid-note glides: the first block after the switch has no bigger jumps than steady Sport+. */
    @Test fun tune_changeDoesNotClick() {
        val s = EngineSynth()
        s.render(FloatArray(44100), 3000.0, 50.0, 1f)
        val switched = FloatArray(441)
        s.render(switched, 3000.0, 50.0, 1f, EngineSynth.Tune.SPORT_PLUS)
        val steady = render(3000.0, 50.0, 1f, n = 441, warmup = 44100, tune = EngineSynth.Tune.SPORT_PLUS)
        assertTrue("switch ${maxStep(switched)} steady ${maxStep(steady)}", maxStep(switched) <= 1.05f * maxStep(steady))
    }

    @Test fun render_deterministic() {
        assertArrayEquals(render(6500.0, 0.0, 1f), render(6500.0, 0.0, 1f), 0f)
    }
}
