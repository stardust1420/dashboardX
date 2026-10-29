package com.karan.dashboardx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarStateTest {
    @Test
    fun value_returnsNullWhenStale() {
        val s = CarState(readings = mapOf(Pid.RPM to Reading(1234.0, 0L)))
        assertNull(s.value(Pid.RPM, nowMs = 3001))
        assertEquals(1234.0, s.value(Pid.RPM, nowMs = 2999)!!, 0.0)
    }

    @Test
    fun style_nextCycles() {
        assertEquals(Style.HUD, Style.RACING.next())
        assertEquals(Style.ARCADE, Style.HUD.next())
        assertEquals(Style.RACING, Style.ARCADE.next())
    }
}
