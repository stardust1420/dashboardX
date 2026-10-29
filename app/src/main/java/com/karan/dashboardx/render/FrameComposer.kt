package com.karan.dashboardx.render

import com.karan.dashboardx.alerts.Warning
import com.karan.dashboardx.alerts.Warnings
import com.karan.dashboardx.data.CarState
import com.karan.dashboardx.data.ConnectionStatus
import com.karan.dashboardx.data.Pid

/**
 * Turns the latest [CarState] into a [FrameModel]. Pure (no android.*) so it is unit-tested.
 * Not thread-safe: DashRuntime serialises calls.
 */
class FrameComposer(private val warnings: Warnings = Warnings()) {
    private val rpmS = Smoother()
    private val speedS = Smoother()
    private val thrS = Smoother()
    private var hasLast = false
    private var lastMs = 0L
    private var t0Ms = 0L
    private var noRpmSinceMs = -1L // first frame of the current CONNECTED-without-rpm run; -1 = none

    /** Warnings that should chime as a result of the most recent [compose] call. */
    var chime: Set<Warning> = emptySet()
        private set

    fun compose(st: CarState, status: ConnectionStatus, redline: Int, coolantWarn: Int, nowMs: Long): FrameModel {
        val dt = if (hasLast) (nowMs - lastMs).coerceIn(0L, MAX_DT_MS) else 0L
        if (!hasLast) t0Ms = nowMs
        hasLast = true
        lastMs = nowMs

        val rawRpm = st.value(Pid.RPM, nowMs)
        val coolant = st.value(Pid.COOLANT, nowMs)
        rpmS.target(rawRpm)
        speedS.target(st.value(Pid.SPEED, nowMs))
        thrS.target(st.value(Pid.THROTTLE, nowMs))

        val noCarData = if (status != ConnectionStatus.CONNECTED || rawRpm != null) {
            noRpmSinceMs = -1L
            false
        } else {
            if (noRpmSinceMs < 0) noRpmSinceMs = nowMs
            nowMs - noRpmSinceMs > NO_DATA_HINT_MS
        }

        val w = warnings.evaluate(coolant, rawRpm, redline, coolantWarn, nowMs)
        chime = w.chime
        return FrameModel(
            rpm = rpmS.advance(dt),
            speed = speedS.advance(dt),
            throttle = thrS.advance(dt),
            coolant = coolant,
            oil = st.value(Pid.OIL, nowMs),
            iat = st.value(Pid.IAT, nowMs),
            instantKml = st.instantKml,
            tripKml = st.tripKml,
            oilUnsupported = st.isUnsupported(Pid.OIL),
            status = status,
            warnings = w.active,
            redline = redline,
            tSec = (nowMs - t0Ms) / 1000.0,
            noCarData = noCarData,
        )
    }

    companion object {
        const val MAX_DT_MS = 100L
        /** How long CONNECTED may go without a fresh rpm before [FrameModel.noCarData] is set. */
        const val NO_DATA_HINT_MS = 3000L
    }
}
