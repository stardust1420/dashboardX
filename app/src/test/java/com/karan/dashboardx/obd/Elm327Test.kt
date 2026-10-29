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
}
