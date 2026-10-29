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
    private fun render(rpm: Double, throttle: Double, volume: Float, n: Int = 4410, warmup: Int = 0): FloatArray {
        val s = EngineSynth()
        if (warmup > 0) s.render(FloatArray(warmup), rpm, throttle, volume)
        val out = FloatArray(n)
        s.render(out, rpm, throttle, volume)
        return out
    }

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

    @Test fun firingHz_inlineSix() {
        val s = EngineSynth()
        assertEquals(150.0, s.firingHz(3000.0), 1e-9)
        assertEquals(40.0, s.firingHz(800.0), 1e-9)
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

    @Test fun render_noClipping() {
        val out = render(7000.0, 100.0, 1f, n = 44100)
        for (v in out) assertTrue("sample $v", v.isFinite() && v >= -1f && v <= 1f)
    }

    /** Pre-limiter level stays moderate: tanh(1.1) ~ 0.8, so peaks under 0.8 mean no hard drive. */
    @Test fun render_headroomAtFullScale() {
        val peakOn = render(7000.0, 100.0, 1f, n = 44100).maxOf { abs(it) }
        val peakOverrun = render(6500.0, 0.0, 1f, n = 44100).maxOf { abs(it) }
        assertTrue("on-throttle peak $peakOn", peakOn < 0.8f)
        assertTrue("overrun peak $peakOverrun", peakOverrun < 0.8f)
    }

    @Test fun render_pitchTracksRpm() {
        val zLo = zeroCrossings(lowPass(render(3000.0, 50.0, 1f, n = 44100), 400.0))
        val zHi = zeroCrossings(lowPass(render(6000.0, 50.0, 1f, n = 44100), 400.0))
        assertTrue("zLo=$zLo zHi=$zHi", zHi > 1.6 * zLo)
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

    private fun rms(x: FloatArray, from: Int, to: Int): Double =
        sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    /** Warm up at [warmThrottle], then render one 441-sample block at throttle 100 (rpm 3000 throughout). */
    private fun stepBlock(warmThrottle: Double, seed: Long): FloatArray {
        val s = EngineSynth(seed = seed)
        s.render(FloatArray(4410), 3000.0, warmThrottle, 1f)
        val out = FloatArray(441)
        s.render(out, 3000.0, 100.0, 1f)
        return out
    }

    /**
     * A 0 -> 100 % throttle step must ramp across the block: the start of the block is still near
     * the old (quiet) level and the end is at the new one. Unramped, the first 50 samples are already
     * at full load (louder than the last 50 here). Measured margins over seeds 1..4: ramped
     * first/last <= 0.45, unramped >= 1.04; ramped first / steady-at-100 first <= 0.25,
     * unramped >= 0.86.
     */
    @Test fun render_rampsThrottleAcrossBlock() {
        for (seed in 1L..4L) {
            val ramped = stepBlock(0.0, seed)
            val steady = stepBlock(100.0, seed)
            val first = rms(ramped, 0, 50)
            val last = rms(ramped, 391, 441)
            val steadyFirst = rms(steady, 0, 50)
            assertTrue("seed $seed: first=$first last=$last", first < 0.6 * last)
            assertTrue("seed $seed: ramped first=$first steady first=$steadyFirst", first < 0.5 * steadyFirst)
        }
    }

    @Test fun render_deterministicForSeed() {
        assertArrayEquals(render(6500.0, 0.0, 1f), render(6500.0, 0.0, 1f), 0f)
    }
}
