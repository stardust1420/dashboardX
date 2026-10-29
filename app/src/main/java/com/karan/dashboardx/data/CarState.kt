package com.karan.dashboardx.data

data class Reading(val value: Double, val atMs: Long)

enum class Pid { RPM, SPEED, COOLANT, IAT, THROTTLE, MAF, MAP, LOAD, OIL }

enum class ConnectionStatus { IDLE_NEEDS_SETUP, DEMO, CONNECTING, CONNECTED, RECONNECTING }

data class CarState(
    val readings: Map<Pid, Reading> = emptyMap(),
    val unsupported: Set<Pid> = emptySet(),
    val instantKml: Double? = null,
    val tripKml: Double? = null,
) {
    /** Reading value, or null if missing or older than [staleMs]. */
    fun value(pid: Pid, nowMs: Long, staleMs: Long = 3000): Double? {
        val r = readings[pid] ?: return null
        return if (nowMs - r.atMs > staleMs) null else r.value
    }

    fun isUnsupported(pid: Pid): Boolean = pid in unsupported
}

enum class Style {
    RACING, HUD, ARCADE;

    fun next(): Style = entries[(ordinal + 1) % entries.size]
}
