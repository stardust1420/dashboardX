package com.karan.dashboardx.obd

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
    @Suppress("unused") private val settings: Settings,
    private val log: DiagLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoffMs: (attempt: Int) -> Long = { minOf(10_000L, 1000L shl it) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DataSource {
    private val _state = MutableStateFlow(CarState())
    private val _status = MutableStateFlow(ConnectionStatus.IDLE_NEEDS_SETUP)
    override val state: StateFlow<CarState> = _state
    override val status: StateFlow<ConnectionStatus> = _status

    private var job: Job? = null
    private val statusLock = Any()

    override fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        synchronized(statusLock) { _status.value = ConnectionStatus.CONNECTING }
        job = scope.launch(ioDispatcher) { run() }
    }

    /** Writes status only while this run is still active, so stop()'s IDLE always wins. */
    private fun CoroutineScope.setStatus(s: ConnectionStatus) {
        synchronized(statusLock) { if (isActive) _status.value = s }
    }

    private suspend fun CoroutineScope.run() {
        var attempt = 0
        var cycle = 0
        while (isActive) {
            var t: ObdTransport? = null
            try {
                t = transportFactory()
                t.open()
                val elm = Elm327(t, log)
                val supported = elm.init()
                _state.value = _state.value.copy(unsupported = Pid.entries.toSet() - supported)
                setStatus(ConnectionStatus.CONNECTED)
                attempt = 0
                while (isActive) {
                    for (pid in PollSchedule.pidsFor(cycle, supported)) {
                        val v = elm.read(pid) ?: continue
                        val now = clock()
                        _state.value = _state.value.let { it.copy(readings = it.readings + (pid to Reading(v, now))) }
                    }
                    cycle++
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
            }
            setStatus(ConnectionStatus.RECONNECTING)
            val wait = backoffMs(attempt)
            attempt = minOf(attempt + 1, 20)
            log.add("reconnect in ${wait}ms")
            delay(wait)
        }
    }

    override fun stop() {
        job?.cancel() // the run's finally block closes its transport
        job = null
        synchronized(statusLock) { _status.value = ConnectionStatus.IDLE_NEEDS_SETUP }
    }
}
