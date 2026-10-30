package com.karan.dashboardx.render

import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {
    @Test fun fmt_temp() {
        assertEquals("92°", Fmt.temp(92.4, false))
        assertEquals("--", Fmt.temp(null, false))
        assertEquals("N/A", Fmt.temp(null, true))
        assertEquals("-40°", Fmt.temp(-40.0, false))
    }

    @Test fun fmt_int() {
        assertEquals("--", Fmt.int(null))
        assertEquals("3001", Fmt.int(3000.6))
    }

    @Test fun fmt_kml() {
        assertEquals("--", Fmt.kml(null))
        assertEquals("∞", Fmt.kml(Double.POSITIVE_INFINITY))
        assertEquals("14.2", Fmt.kml(14.23))
    }

    @Test fun clamp01() {
        assertEquals(1.0, Fmt.clamp01(9000.0, 0.0, 7000.0), 0.0)
        assertEquals(0.0, Fmt.clamp01(-40.0, 40.0, 120.0), 0.0)
        assertEquals(0.5, Fmt.clamp01(80.0, 40.0, 120.0), 1e-9)
    }
}
