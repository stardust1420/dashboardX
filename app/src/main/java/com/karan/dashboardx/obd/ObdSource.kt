package com.karan.dashboardx.obd

import com.karan.dashboardx.calc.FuelEconomy
import com.karan.dashboardx.calc.TripAverage
import com.karan.dashboardx.data.CarState
import com.karan.dashboardx.data.ConnectionStatus
import com.karan.dashboardx.data.DataSource
import com.karan.dashboardx.data.Pid
import com.karan.dashboardx.data.Reading
import com.karan.dashboardx.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

class ObdSource(
    private val transportFactory: () -> ObdTransport,
    private val settings: Settings,
    private val log: DiagLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoffMs: (attempt: Int) -> Long = { minOf(10_000L, 1000L shl it) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val probeRetryMs: Long = PROBE_RETRY_MS,
) : DataSource {
    private val _state = MutableStateFlow(CarState())
    private val _status = MutableStateFlow(ConnectionStatus.IDLE_NEEDS_SETUP)
    override val state: StateFlow<CarState> = _state
    override val status: StateFlow<ConnectionStatus> = _status

    private var job: Job? = null
    private val statusLock = Any()
    private var trip = TripAverage()

    override fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        synchronized(statusLock) { _status.value = ConnectionStatus.CONNECTING }
        trip = TripAverage()
        job = scope.launch(ioDispatcher) { run() }
    }

    /** Writes status only while this run is still active, so stop()'s IDLE always wins. */
    private fun CoroutineScope.setStatus(s: ConnectionStatus) {
        synchronized(statusLock) { if (isActive) _status.value = s }
    }

    private suspend fun CoroutineScope.run() {
        var attempt = 0
        var cycle = 0
        val trip = this@ObdSource.trip
        while (isActive) {
            var t: ObdTransport? = null
            try {
                t = transportFactory()
                t.open()
                val elm = Elm327(t, log, clock)
                val supported = initWithRetry(elm)
                _state.value = _state.value.copy(unsupported = Pid.entries.toSet() - supported)
                setStatus(ConnectionStatus.CONNECTED)
                attempt = 0
                var lastCycleMs: Long? = null
                while (isActive) {
                    for (pid in PollSchedule.pidsFor(cycle, supported)) {
                        val v = elm.read(pid) ?: continue
                        val now = clock()
                        _state.value = _state.value.let { it.copy(readings = it.readings + (pid to Reading(v, now))) }
                    }
                    cycle++
                    elm.endCycle()
                    lastCycleMs = updateFuel(trip, lastCycleMs)
                    delay(1) // yield between cycles; real latency comes from the adapter
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                log.add("link error: ${e.message}")
            } catch (e: RetryableObdException) {
                log.add("init failed: ${e.message}")
            } catch (e: Exception) {
                log.add("unexpected ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                t?.close()
                _state.value = _state.value.copy(instantKml = null)
            }
            setStatus(ConnectionStatus.RECONNECTING)
            val wait = backoffMs(attempt)
            attempt = minOf(attempt + 1, 20)
            log.add("reconnect in ${wait}ms")
            delay(wait)
        }
    }

    /**
     * Runs [Elm327.init]. If the car does not answer (ignition off, UNABLE TO CONNECT), re-asks
     * 0100 on the same link every [probeRetryMs], up to [PROBE_RETRIES] times, before giving up
     * and letting the caller drop the Bluetooth link. IOExceptions propagate unchanged.
     */
    private suspend fun initWithRetry(elm: Elm327): Set<Pid> {
        var last = try {
            return elm.init()
        } catch (e: RetryableObdException) {
            e
        }
        for (i in 1..PROBE_RETRIES) {
            log.add("init: ${last.message}; retrying 0100 on this link in ${probeRetryMs}ms ($i/$PROBE_RETRIES)")
            delay(probeRetryMs)
            last = try {
                return elm.probe()
            } catch (e: RetryableObdException) {
                e
            }
        }
        throw last
    }

    /** Computes instant/trip economy from fresh readings; returns this cycle's timestamp. */
    private fun updateFuel(trip: TripAverage, lastMs: Long?): Long {
        val now = clock()
        val st = _state.value
        val cal = settings.fuelCalibration
        val maf = st.value(Pid.MAF, now)
        val mafGs = maf ?: run {
            val map = st.value(Pid.MAP, now)
            val rpm = st.value(Pid.RPM, now)
            if (map != null && rpm != null) {
                FuelEconomy.mafFromSpeedDensity(map, rpm, st.value(Pid.IAT, now) ?: 25.0, settings.displacementL)
            } else null
        }
        val lph = mafGs?.let { FuelEconomy.lphFromMaf(it, cal) }
        val speed = st.value(Pid.SPEED, now)
        val instant = FuelEconomy.instantKml(speed, st.value(Pid.THROTTLE, now), lph)
        if (lph != null && speed != null && lastMs != null) {
            val dt = now - lastMs
            if (dt in 1..2000) trip.add(speed, lph, dt)
        }
        _state.value = _state.value.copy(instantKml = instant, tripKml = trip.kml())
        return now
    }

    override fun stop() {
        job?.cancel() // the run's finally block closes its transport
        job = null
        _state.value = _state.value.copy(instantKml = null)
        synchronized(statusLock) { _status.value = ConnectionStatus.IDLE_NEEDS_SETUP }
    }

    private companion object {
        const val PROBE_RETRY_MS = 3000L
        const val PROBE_RETRIES = 5
    }
}
