package com.karan.dashboardx.obd

import com.karan.dashboardx.data.CarState
import com.karan.dashboardx.data.ConnectionStatus
import com.karan.dashboardx.data.DataSource
import com.karan.dashboardx.data.Pid
import com.karan.dashboardx.data.Reading
import com.karan.dashboardx.settings.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
    @Volatile private var transport: ObdTransport? = null

    override fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        _status.value = ConnectionStatus.CONNECTING
        job = scope.launch(ioDispatcher) { run() }
    }

    private suspend fun run() {
        var attempt = 0
        var cycle = 0
        while (currentCoroutineContext().isActive) {
            try {
                val t = transportFactory().also { transport = it }
                t.open()
                val elm = Elm327(t, log)
                val supported = elm.init()
                _state.value = _state.value.copy(unsupported = Pid.entries.toSet() - supported)
                _status.value = ConnectionStatus.CONNECTED
                attempt = 0
                while (currentCoroutineContext().isActive) {
                    for (pid in PollSchedule.pidsFor(cycle, supported)) {
                        val v = elm.read(pid) ?: continue
                        val now = clock()
                        _state.value = _state.value.let { it.copy(readings = it.readings + (pid to Reading(v, now))) }
                    }
                    cycle++
                    delay(1) // yield between cycles; real latency comes from the adapter
                }
            } catch (e: IOException) {
                log.add("link error: ${e.message}")
            } catch (e: RetryableObdException) {
                log.add("init failed: ${e.message}")
            }
            transport?.close()
            transport = null
            _status.value = ConnectionStatus.RECONNECTING
            val wait = backoffMs(attempt++)
            log.add("reconnect in ${wait}ms")
            delay(wait)
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        transport?.close()
        transport = null
        _status.value = ConnectionStatus.IDLE_NEEDS_SETUP
    }
}
