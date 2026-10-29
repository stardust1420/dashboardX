package com.karan.dashboardx.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Fake car: 9 s pull through 3 gears, cruise, idle (16 s cycle). Ported from the mockup's step(). */
class DemoSource(private val clock: () -> Long = System::currentTimeMillis) : DataSource {
    private val _state = MutableStateFlow(CarState())
    private val _status = MutableStateFlow(ConnectionStatus.IDLE_NEEDS_SETUP)
    override val state: StateFlow<CarState> = _state
    override val status: StateFlow<ConnectionStatus> = _status

    private var rpm = 850.0
    private var speed = 0.0
    private var thr = 0.0
    private var lastT: Double? = null
    private var job: Job? = null

    /** Smoothing factor [k] defined per 60 fps frame, rescaled to the elapsed time. */
    private fun alpha(k: Double, dt: Double) = 1.0 - (1.0 - k).pow(dt * 60.0)

    private fun reset() {
        rpm = 850.0; speed = 0.0; thr = 0.0; lastT = null
    }

    /**
     * Advances the simulation to [tSec] and returns the state. Stateful (smoothing needs history):
     * deterministic for a given sequence of calls; t must increase, and a t lower than the
     * previous call resets the simulation state.
     */
    fun stepAt(tSec: Double): CarState {
        val t = tSec
        if (lastT?.let { t < it } == true) reset()
        val dt = lastT?.let { max(0.0, t - it) } ?: (1.0 / 60.0)
        lastT = t
        val ph = t % 16.0
        val tr: Double
        val thrT: Double
        val spdT: Double
        if (ph < 9) {
            val g = ph % 3
            tr = 2600 + (g / 3) * 3900; thrT = 92.0; spdT = 20 + ph * 9
        } else if (ph < 13) {
            tr = 2300 + sin(t * 2) * 60; thrT = 24.0; spdT = 95.0
        } else {
            tr = 850.0; thrT = 0.0; spdT = max(0.0, 95 - (ph - 13) * 40)
        }
        rpm += (tr - rpm) * alpha(0.12, dt)
        thr += (thrT - thr) * alpha(0.15, dt)
        speed += (spdT - speed) * alpha(0.04, dt)
        val clt = 90 + 3 * sin(t / 9)
        val oil = 97 + 4 * sin(t / 11)
        val iat = 36 + sin(t / 7)
        val fuel = 0.25 + thr * 0.09 + rpm / 2500
        // Same conventions as FuelEconomy.instantKml: coasting = infinity, near standstill = unknown.
        val kml = if (thr < 2 && speed > 10) Double.POSITIVE_INFINITY else if (speed < 3) null else min(40.0, speed / (fuel * 3))
        val now = clock()
        fun r(v: Double) = Reading(v, now)
        return CarState(
            readings = mapOf(
                Pid.RPM to r(rpm),
                Pid.SPEED to r(speed),
                Pid.COOLANT to r(clt),
                Pid.IAT to r(iat),
                Pid.THROTTLE to r(thr),
                Pid.OIL to r(oil),
                Pid.MAF to r(2.0 + thr * 1.5 + rpm / 400),
                Pid.MAP to r(30 + thr * 0.7),
                Pid.LOAD to r(20 + thr * 0.7),
            ),
            instantKml = kml,
        )
    }

    override fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        reset()
        _status.value = ConnectionStatus.DEMO
        val t0 = clock()
        job = scope.launch {
            while (true) {
                _state.value = stepAt((clock() - t0) / 1000.0)
                delay(50)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _status.value = ConnectionStatus.IDLE_NEEDS_SETUP
    }
}
