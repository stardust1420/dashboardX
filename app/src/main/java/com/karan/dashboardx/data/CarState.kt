package com.karan.dashboardx.data

data class Reading(val value: Double, val atMs: Long)

/**
 * Mode 01 PIDs. [staleMs] is how long a reading stays valid: 3 s for the PIDs polled every one
 * or two cycles, 10 s for COOLANT, IAT and OIL, which are polled only every 10th cycle.
 */
enum class Pid(val staleMs: Long) {
    RPM(FAST_STALE_MS),
    SPEED(FAST_STALE_MS),
    COOLANT(SLOW_STALE_MS),
    IAT(SLOW_STALE_MS),
    THROTTLE(FAST_STALE_MS),
    MAF(FAST_STALE_MS),
    MAP(FAST_STALE_MS),
    LOAD(FAST_STALE_MS),
    OIL(SLOW_STALE_MS),
}

const val FAST_STALE_MS = 3_000L
const val SLOW_STALE_MS = 10_000L

enum class ConnectionStatus { IDLE_NEEDS_SETUP, DEMO, CONNECTING, CONNECTED, RECONNECTING }

data class CarState(
    val readings: Map<Pid, Reading> = emptyMap(),
    val unsupported: Set<Pid> = emptySet(),
    val instantKml: Double? = null,
    val tripKml: Double? = null,
) {
    /** Reading value, or null if missing or older than [staleMs] (default: the PID's own window). */
    fun value(pid: Pid, nowMs: Long, staleMs: Long = pid.staleMs): Double? {
        val r = readings[pid] ?: return null
        return if (nowMs - r.atMs > staleMs) null else r.value
    }

    fun isUnsupported(pid: Pid): Boolean = pid in unsupported
}

/** The drive mode the driver picked by tapping the on-screen selector (display only; saved). */
enum class DriveMode {
    COMFORT, SPORT, SPORT_PLUS;

    fun next(): DriveMode = entries[(ordinal + 1) % entries.size]
}

enum class Style {
    RACING, HUD, ARCADE, LUXURY, CLUSTER, MOST_WANTED, COCKPIT;

    fun next(): Style = entries[(ordinal + 1) % entries.size]
}
