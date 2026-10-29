package com.karan.dashboardx.obd

import com.karan.dashboardx.data.Pid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327Test {
    @Test
    fun session_initSendsSequence() = runBlocking {
        val t = FakeTransport(mapOf("0100" to "4100BE3EB813"))
        Elm327(t, DiagLog()).init()
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100"), t.sent.take(7))
    }

    @Test
    fun session_initReturnsSupported() = runBlocking {
        val t = FakeTransport(mapOf("0100" to "4100BE3EB813", "0120" to "NO DATA", "0140" to "NO DATA"))
        val s = Elm327(t, DiagLog()).init()
        for (p in listOf(Pid.RPM, Pid.SPEED, Pid.COOLANT, Pid.IAT, Pid.THROTTLE, Pid.MAP, Pid.LOAD)) assertTrue("$p", p in s)
        assertFalse(Pid.MAF in s)
        assertFalse(Pid.OIL in s)
    }

    @Test
    fun session_oilFrom0140() = runBlocking {
        // 0x5C = base 0x40 + 28 -> index 27 -> mask bit 4 -> last byte 0x10
        val t = FakeTransport(mapOf("0100" to "4100BE3EB813", "0140" to "414000000010"))
        val s = Elm327(t, DiagLog()).init()
        assertTrue(Pid.OIL in s)
    }

    @Test(expected = RetryableObdException::class)
    fun session_unableToConnect_throwsRetryable() {
        runBlocking { Elm327(FakeTransport(mapOf("0100" to "UNABLE TO CONNECT")), DiagLog()).init() }
    }

    @Test
    fun session_readDecodes() = runBlocking {
        val t = FakeTransport(mapOf("010C" to "410C1AF8"))
        assertEquals(1726.0, Elm327(t, DiagLog()).read(Pid.RPM)!!, 0.001)
        assertEquals(null, Elm327(t, DiagLog()).read(Pid.SPEED))
    }

    @Test
    fun session_commandTimeouts() = runBlocking {
        val t = FakeTransport(mapOf("0100" to "4100BE3EB813", "010C" to "410C1AF8"))
        val elm = Elm327(t, DiagLog())
        elm.init()
        elm.read(Pid.RPM)
        val byCmd = t.sent.zip(t.timeouts).toMap()
        assertEquals(3000L, byCmd["ATZ"])
        assertEquals(10_000L, byCmd["0100"])
        assertEquals(10_000L, byCmd["0120"])
        assertEquals(10_000L, byCmd["0140"])
        assertEquals(2000L, byCmd["ATE0"])
        assertEquals(2000L, byCmd["ATSP0"])
        assertEquals(2000L, byCmd["010C"])
    }

    @Test
    fun session_probeRerunsOnlyThe01xxQueries() = runBlocking {
        val t = FakeTransport(mapOf("0100" to "4100BE3EB813"))
        val s = Elm327(t, DiagLog()).probe()
        assertEquals(listOf("0100", "0120", "0140"), t.sent)
        assertTrue(Pid.RPM in s)
    }

    /** Each range's last bit says the next range exists; the chain is followed to 01A0 for PID A4. */
    @Test
    fun probe_logsGearPidWhenAdvertised() = runBlocking {
        val t = FakeTransport(mapOf(
            "0100" to "4100BE3EB813",
            "0140" to "414000000001",
            "0160" to "416000000001",
            "0180" to "418000000001",
            "01A0" to "41A010000000", // 0xA4 = base 0xA0 + 4 -> index 3 -> first byte 0x10
        ))
        val log = DiagLog()
        Elm327(t, log).probe()
        assertEquals(listOf("0100", "0120", "0140", "0160", "0180", "01A0"), t.sent)
        assertTrue(log.lines().any { it.endsWith("gear PID A4: supported") })
    }

    @Test
    fun probe_stopsTheChainWhereItEnds() = runBlocking {
        val t = FakeTransport(mapOf("0100" to "4100BE3EB813", "0140" to "414000000001", "0160" to "416000000000"))
        val log = DiagLog()
        Elm327(t, log).probe()
        assertEquals(listOf("0100", "0120", "0140", "0160"), t.sent)
        assertTrue(log.lines().any { it.endsWith("gear PID A4: not supported") })
    }

    @Test
    fun log_pollTrafficOnlyForFirstThreeCyclesThenSummary() = runBlocking {
        var now = 0L
        val log = DiagLog()
        val t = FakeTransport(mapOf("0100" to "4100BE3EB813", "010C" to "410C1AF8"))
        val elm = Elm327(t, log) { now }
        elm.init()
        assertTrue(log.lines().any { it.endsWith("> ATZ") })
        assertTrue(log.lines().any { it.endsWith("> 0100") })
        repeat(10) {
            elm.read(Pid.RPM)
            elm.endCycle()
            now += 100
        }
        assertEquals(3, log.lines().count { it.endsWith("> 010C") })
        elm.read(Pid.SPEED) // NO DATA: logged in full even after cycle 3
        assertTrue(log.lines().any { it.endsWith("> 010D") })
        assertTrue(log.lines().any { it.endsWith("< NO DATA") })
        now = 30_000
        elm.read(Pid.RPM)
        elm.endCycle()
        val summary = log.lines().filter { "polled" in it }
        assertEquals(1, summary.size)
        assertTrue(summary[0], summary[0].contains("polled 12 cmds, 1 no-data, last rpm 1726"))
        elm.endCycle() // no second summary inside the next 30 s
        assertEquals(1, log.lines().count { "polled" in it })
    }
}
