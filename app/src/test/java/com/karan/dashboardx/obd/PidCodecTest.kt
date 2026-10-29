package com.karan.dashboardx.obd

import com.karan.dashboardx.data.Pid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PidCodecTest {
    @Test fun decode_rpm() = assertEquals(1726.0, PidCodec.decode(Pid.RPM, "41 0C 1A F8\r\r>")!!, 1e-9)
    @Test fun decode_rpmNoSpaces() = assertEquals(1726.0, PidCodec.decode(Pid.RPM, "410C1AF8")!!, 1e-9)
    @Test fun decode_speed() = assertEquals(60.0, PidCodec.decode(Pid.SPEED, "410D3C")!!, 1e-9)

    @Test fun decode_coolant() {
        assertEquals(83.0, PidCodec.decode(Pid.COOLANT, "41057B")!!, 1e-9)
        assertEquals(-40.0, PidCodec.decode(Pid.COOLANT, "410500")!!, 1e-9)
    }

    @Test fun decode_throttle() = assertEquals(100.0, PidCodec.decode(Pid.THROTTLE, "4111FF")!!, 1e-9)
    @Test fun decode_maf() = assertEquals(10.0, PidCodec.decode(Pid.MAF, "411003E8")!!, 1e-9)
    @Test fun decode_map() = assertEquals(50.0, PidCodec.decode(Pid.MAP, "410B32")!!, 1e-9)
    @Test fun decode_oil() = assertEquals(90.0, PidCodec.decode(Pid.OIL, "415C82")!!, 1e-9)

    @Test fun decode_ignoresErrorReplies() {
        assertNull(PidCodec.decode(Pid.RPM, "NO DATA"))
        assertNull(PidCodec.decode(Pid.RPM, "?"))
        assertNull(PidCodec.decode(Pid.RPM, "STOPPED"))
        assertNull(PidCodec.decode(Pid.RPM, "SEARCHING...\rNO DATA"))
        assertNull(PidCodec.decode(Pid.RPM, "410C1A"))
        assertNull(PidCodec.decode(Pid.RPM, "7F0112"))
        assertNull(PidCodec.decode(Pid.RPM, ""))
    }

    @Test fun decode_searchingThenData() =
        assertEquals(1726.0, PidCodec.decode(Pid.RPM, "SEARCHING...\r410C1AF8")!!, 1e-9)

    @Test fun parseSupported_bitmask() {
        val s = PidCodec.parseSupported("4100BE3EB813", 0)
        for (p in listOf(0x01, 0x0C, 0x0D, 0x0F, 0x11, 0x20)) assertTrue("missing $p", p in s)
        assertFalse(0x02 in s)
        assertFalse(0x10 in s)
    }

    @Test fun decode_truncatedLineThenGood() =
        assertEquals(1726.0, PidCodec.decode(Pid.RPM, "410C1A\r410C1AF8")!!, 1e-9)

    @Test fun decode_multiEcuFirstMatchingLine() =
        assertEquals(1726.0, PidCodec.decode(Pid.RPM, "410C1AF8\r410C0FA0")!!, 1e-9)

    @Test fun decode_skipsOtherPidLines() =
        assertEquals(60.0, PidCodec.decode(Pid.SPEED, "410C1AF8\r\n410D3C\r\r>")!!, 1e-9)

    @Test fun decode_errorLineThenGood() =
        assertEquals(1726.0, PidCodec.decode(Pid.RPM, "NO DATA\r410C1AF8")!!, 1e-9)

    @Test fun parseSupported_unionsEcus() {
        val s = PidCodec.parseSupported("4100BE1FA813\r410080000000", 0)
        assertTrue(0x01 in s) // in both
        assertTrue(0x0C in s) // first line only
        assertTrue(0x20 in s)
        val second = PidCodec.parseSupported("410080000000", 0)
        assertEquals(setOf(0x01), second)
        // A bit only the second line sets is kept in the union.
        val u = PidCodec.parseSupported("410000000001\r410080000000", 0)
        assertEquals(setOf(0x01, 0x20), u)
    }

    @Test fun parseSupported_ignoresShortAndErrorLines() =
        assertEquals(setOf(0x01), PidCodec.parseSupported("SEARCHING...\r4100800000\rNO DATA\r410080000000", 0))
}
