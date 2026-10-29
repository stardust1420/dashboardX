package com.karan.dashboardx.render

import com.karan.dashboardx.alerts.Warning
import com.karan.dashboardx.data.ConnectionStatus

/**
 * Everything a renderer needs for one frame. RPM, speed and throttle are smoothed; the other
 * values are raw and already stale-checked (null = missing or older than the PID's
 * [com.karan.dashboardx.data.Pid.staleMs]: 3 s, or 10 s for coolant, IAT and oil).
 */
data class FrameModel(
    val rpm: Double?,
    val speed: Double?,
    val throttle: Double?,
    val coolant: Double?,
    val oil: Double?,
    val iat: Double?,
    val instantKml: Double?,
    val tripKml: Double?,
    val oilUnsupported: Boolean,
    val status: ConnectionStatus,
    val warnings: Set<Warning>,
    val redline: Int,
    val tSec: Double,
    /** CONNECTED but no fresh rpm for more than 3 s: the overlay hints that the engine may be off. */
    val noCarData: Boolean = false,
)
