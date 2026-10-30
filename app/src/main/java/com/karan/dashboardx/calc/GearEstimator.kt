package com.karan.dashboardx.calc

import kotlin.math.abs
import kotlin.math.ln

/**
 * Estimates the automatic gearbox's current gear from rpm ÷ speed, which is fixed per gear while
 * the torque converter is locked. Pure (no android.*) so it is unit-tested.
 *
 * - The nearest gear (in log ratio) counts as a match within [TOLERANCE]. Below [MIN_SPEED_KMH]
 *   the converter slips too much to tell, so the result is null.
 * - A new gear is shown only after matching for [SETTLE_MS]; while nothing matches (mid-shift or
 *   converter slip) the last gear is held for [HOLD_MS], then the result is null.
 * - [scale] learns a uniform offset (final drive or tyre size differing from the defaults) from
 *   matches in 3rd to 6th, where the converter is usually locked; 1st and 2nd slip and would skew it.
 *
 * Not thread-safe: FrameComposer serialises calls.
 */
class GearEstimator {
    var scale = 1.0
        private set

    private var shown: Int? = null
    private var candidate: Int? = null
    private var candidateSinceMs = 0L
    private var lastMatchMs = 0L

    fun update(rpm: Double?, speed: Double?, nowMs: Long): Int? {
        if (rpm == null || speed == null || speed < MIN_SPEED_KMH) {
            shown = null
            candidate = null
            return null
        }
        val ratio = ln(rpm / speed / scale)
        var best = 0
        for (g in 1 until RPM_PER_KMH.size) {
            if (abs(ratio - LOG_RATIOS[g]) < abs(ratio - LOG_RATIOS[best])) best = g
        }
        val err = ratio - LOG_RATIOS[best]
        if (abs(err) > TOLERANCE) {
            candidate = null
            if (shown != null && nowMs - lastMatchMs > HOLD_MS) shown = null
            return shown
        }
        val gear = best + 1
        lastMatchMs = nowMs
        if (gear != candidate) {
            candidate = gear
            candidateSinceMs = nowMs
        }
        if (shown == null || nowMs - candidateSinceMs >= SETTLE_MS) shown = gear
        if (gear >= 3 && gear == shown) scale = (scale * (1 + LEARN_RATE * err)).coerceIn(MIN_SCALE, MAX_SCALE)
        return shown
    }

    companion object {
        /**
         * rpm per km/h in 1st..6th for the 2019 Verna 1.6 automatic: gearbox ratios 4.400, 2.726,
         * 1.834, 1.392, 1.000, 0.774 × final drive 3.648, on a 1.95 m tyre (195/55 R16 and
         * 185/65 R15 are within 0.1 %). [scale] corrects a uniform error in these.
         */
        val RPM_PER_KMH = doubleArrayOf(137.1, 84.9, 57.1, 43.4, 31.2, 24.1)
        private val LOG_RATIOS = DoubleArray(RPM_PER_KMH.size) { ln(RPM_PER_KMH[it]) }

        const val MIN_SPEED_KMH = 8.0
        /** Just under half the smallest gap between neighbouring gears (6th/5th, ln 1.29 ≈ 0.26). */
        const val TOLERANCE = 0.12
        const val SETTLE_MS = 400L
        const val HOLD_MS = 1500L
        private const val LEARN_RATE = 0.01
        private const val MIN_SCALE = 0.85
        private const val MAX_SCALE = 1.15
    }
}
