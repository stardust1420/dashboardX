package com.karan.dashboardx.calc

import kotlin.math.exp

enum class DriveMode { ECO, COMFORT, SPORT }

/**
 * Infers a drive mode from how the car is being driven (the Verna has no mode switch to read).
 * Pure (no android.*) so it is unit-tested.
 *
 * Each frame scores sportiness 0..1 as the larger of throttle (full at [FULL_THROTTLE_PCT]) and rpm
 * (0 at [CALM_RPM], full at [FULL_RPM]). The score is averaged quickly upwards ([RISE_S]) and
 * slowly downwards ([FALL_S]), so a pull shows SPORT within a second or two and it lingers after.
 * Each mode is entered past one threshold and left past a lower one (hysteresis), so it doesn't
 * flicker. Missing rpm or throttle holds the current mode.
 *
 * Not thread-safe: FrameComposer serialises calls.
 */
class DriveModeEstimator {
    var mode = DriveMode.COMFORT
        private set

    private var score = COMFORT_START
    private var lastMs = Long.MIN_VALUE

    fun update(rpm: Double?, throttle: Double?, nowMs: Long): DriveMode {
        val dt = if (lastMs == Long.MIN_VALUE) 0.0 else ((nowMs - lastMs).coerceIn(0L, MAX_DT_MS)) / 1000.0
        lastMs = nowMs
        if (rpm == null || throttle == null) return mode
        val s = maxOf(throttle / FULL_THROTTLE_PCT, (rpm - CALM_RPM) / (FULL_RPM - CALM_RPM)).coerceIn(0.0, 1.0)
        val tau = if (s > score) RISE_S else FALL_S
        score += (1 - exp(-dt / tau)) * (s - score)
        mode = when (mode) {
            DriveMode.SPORT -> if (score < SPORT_EXIT) comfortOrEco() else DriveMode.SPORT
            DriveMode.ECO -> if (score > ECO_EXIT) comfortOrSport() else DriveMode.ECO
            DriveMode.COMFORT -> if (score > SPORT_ENTER) DriveMode.SPORT else if (score < ECO_ENTER) DriveMode.ECO else DriveMode.COMFORT
        }
        return mode
    }

    private fun comfortOrEco() = if (score < ECO_ENTER) DriveMode.ECO else DriveMode.COMFORT
    private fun comfortOrSport() = if (score > SPORT_ENTER) DriveMode.SPORT else DriveMode.COMFORT

    private companion object {
        const val FULL_THROTTLE_PCT = 70.0
        const val CALM_RPM = 1500.0
        const val FULL_RPM = 5000.0
        const val RISE_S = 0.8
        const val FALL_S = 4.0
        const val SPORT_ENTER = 0.6
        const val SPORT_EXIT = 0.5
        const val ECO_ENTER = 0.25
        const val ECO_EXIT = 0.32
        /** Middle of the COMFORT band, so a fresh estimator starts in COMFORT. */
        const val COMFORT_START = 0.4
        const val MAX_DT_MS = 1000L
    }
}
