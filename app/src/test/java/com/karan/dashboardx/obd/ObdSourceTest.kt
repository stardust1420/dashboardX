package com.karan.dashboardx.obd

import com.karan.dashboardx.data.ConnectionStatus
import com.karan.dashboardx.data.Pid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObdSourceTest {
    private val script = mapOf(
        "0100" to "4100BE3EB813", "0120" to "NO DATA", "0140" to "NO DATA",
        "010C" to "410C1AF8", "010D" to "410D3C", "0105" to "41055A",
        "010F" to "410F4B", "0111" to "41117F", "010B" to "410B22", "0104" to "410440",
    )

    private fun source(disp: kotlinx.coroutines.CoroutineDispatcher, now: () -> Long, log: DiagLog = DiagLog(), factory: () -> ObdTransport) =
        ObdSource(factory, com.karan.dashboardx.settings.Settings(FakePrefs()), log, now, { 1000L }, disp)

    @Test
    fun source_publishesValues() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val src = source(d, { testScheduler.currentTime }) { FakeTransport(script) }
        src.start(backgroundScope)
        advanceTimeBy(200)
        assertEquals(ConnectionStatus.CONNECTED, src.status.value)
        assertEquals(1726.0, src.state.value.value(Pid.RPM, testScheduler.currentTime)!!, 0.001)
        assertTrue(Pid.OIL in src.state.value.unsupported)
        src.stop()
        assertEquals(ConnectionStatus.IDLE_NEEDS_SETUP, src.status.value)
    }

    @Test
    fun source_reconnectsAfterIoError() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val made = mutableListOf<FakeTransport>()
        val src = source(d, { testScheduler.currentTime }) {
            FakeTransport(script, if (made.isEmpty()) 20 else Int.MAX_VALUE).also { made += it }
        }
        val seen = mutableListOf<ConnectionStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { src.status.collect { seen += it } }
        src.start(backgroundScope)
        advanceTimeBy(3000)
        assertTrue(seen.toString(), ConnectionStatus.RECONNECTING in seen)
        assertEquals(2, made.size)
        assertTrue(made[0].closed)
        assertEquals(ConnectionStatus.CONNECTED, src.status.value)
        assertEquals(ConnectionStatus.CONNECTING, seen[1])
        assertTrue(src.state.value.readings.containsKey(Pid.RPM))
        src.stop()
    }

    @Test
    fun source_stopSetsIdleEvenMidReconnect() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val src = source(d, { testScheduler.currentTime }) { FakeTransport(script, 0) }
        src.start(backgroundScope)
        advanceTimeBy(500) // failed, now in the 1000 ms backoff
        assertEquals(ConnectionStatus.RECONNECTING, src.status.value)
        src.stop()
        advanceTimeBy(5000)
        assertEquals(ConnectionStatus.IDLE_NEEDS_SETUP, src.status.value)
    }

    @Test
    fun source_closesTransportOnScopeCancel() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val t = FakeTransport(script)
        val src = source(d, { testScheduler.currentTime }) { t }
        val scope = kotlinx.coroutines.CoroutineScope(d + kotlinx.coroutines.SupervisorJob())
        src.start(scope)
        advanceTimeBy(100)
        scope.cancel()
        advanceTimeBy(100)
        assertTrue(t.closed)
    }

    @Test
    fun source_nonIoExceptionRetries() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        var n = 0
        val src = source(d, { testScheduler.currentTime }) {
            if (n++ == 0) object : ObdTransport {
                override suspend fun open() { throw SecurityException("no bt permission") }
                override suspend fun send(cmd: String, timeoutMs: Long): String = ""
                override fun close() {}
            } else FakeTransport(script)
        }
        src.start(backgroundScope)
        advanceTimeBy(3000)
        assertEquals(2, n)
        assertEquals(ConnectionStatus.CONNECTED, src.status.value)
        src.stop()
    }

    @Test
    fun source_computesInstantKml() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val maf = script + mapOf("0100" to "4100BE3FB813", "0110" to "411003E8", "010D" to "410D3C", "0111" to "411133")
        val src = source(d, { testScheduler.currentTime }) { FakeTransport(maf) }
        src.start(backgroundScope)
        advanceTimeBy(500)
        assertEquals(18.13, src.state.value.instantKml!!, 0.05)
        src.stop()
    }

    @Test
    fun source_clearsInstantKmlOnLinkLoss() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val maf = script + mapOf("0100" to "4100BE3FB813", "0110" to "411003E8", "010D" to "410D3C", "0111" to "411133")
        val src = source(d, { testScheduler.currentTime }) { FakeTransport(maf, 60) }
        src.start(backgroundScope)
        advanceTimeBy(500)
        assertEquals(ConnectionStatus.RECONNECTING, src.status.value)
        assertEquals(null, src.state.value.instantKml)
        src.stop()
    }

    @Test
    fun source_retriesProbeOnSameLinkWhenCarIsOff() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val made = mutableListOf<FakeTransport>()
        val log = DiagLog()
        val src = source(d, { testScheduler.currentTime }, log) {
            FakeTransport(script, sequences = mapOf("0100" to listOf("NO DATA", "UNABLE TO CONNECT", "NO DATA")))
                .also { made += it }
        }
        src.start(backgroundScope)
        advanceTimeBy(5_000) // init + first retry at 3 s, both fail
        assertEquals(1, made.size)
        assertTrue(src.status.value != ConnectionStatus.CONNECTED)
        advanceTimeBy(5_000) // third probe fails at 6 s, fourth (9 s) answers
        assertEquals(ConnectionStatus.CONNECTED, src.status.value)
        assertEquals(1, made.size)
        assertEquals(1, made[0].sent.count { it == "ATZ" })
        assertEquals(4, made[0].sent.count { it == "0100" })
        assertTrue(log.lines().joinToString("\n"), log.lines().any { "retrying 0100" in it })
        src.stop()
    }

    @Test
    fun source_dropsLinkAfterFiveFailedProbeRetries() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val made = mutableListOf<FakeTransport>()
        val src = source(d, { testScheduler.currentTime }) {
            FakeTransport(script + ("0100" to "NO DATA")).also { made += it }
        }
        src.start(backgroundScope)
        advanceTimeBy(14_000) // init at 0 s, retries at 3, 6, 9, 12 s
        assertEquals(1, made.size)
        assertTrue(!made[0].closed)
        advanceTimeBy(2_000) // fifth retry at 15 s fails; link closed, 1 s backoff
        assertTrue(made[0].closed)
        assertEquals(6, made[0].sent.count { it == "0100" })
        assertEquals(ConnectionStatus.RECONNECTING, src.status.value)
        advanceTimeBy(1_000)
        assertEquals(2, made.size)
        src.stop()
    }
}
