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

    @Test fun render_rampsAcrossBlockWithoutJumps() {
        val s = EngineSynth()
        s.render(FloatArray(441), 2000.0, 20.0, 1f)
        val out = FloatArray(441)
        s.render(out, 6000.0, 90.0, 1f) // big step: must be ramped, not jumped
        var maxStep = 0f
        for (i in 1 until out.size) maxStep = maxOf(maxStep, abs(out[i] - out[i - 1]))
        assertTrue("max sample-to-sample step $maxStep", maxStep < 0.5f)
    }

    @Test fun render_deterministicForSeed() {
        assertArrayEquals(render(6500.0, 0.0, 1f), render(6500.0, 0.0, 1f), 0f)
    }
}
