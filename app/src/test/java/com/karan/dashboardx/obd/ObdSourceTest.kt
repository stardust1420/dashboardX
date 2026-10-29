package com.karan.dashboardx.obd

import com.karan.dashboardx.data.ConnectionStatus
import com.karan.dashboardx.data.Pid
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun source(disp: kotlinx.coroutines.CoroutineDispatcher, now: () -> Long, factory: () -> ObdTransport) =
        ObdSource(factory, com.karan.dashboardx.settings.Settings(FakePrefs()), DiagLog(), now, { 1000L }, disp)

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
}
