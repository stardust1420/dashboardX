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
    fun value_fastPidsStaleAfter3s() {
        for (p in listOf(Pid.RPM, Pid.SPEED, Pid.THROTTLE, Pid.MAF, Pid.MAP, Pid.LOAD)) {
            val s = CarState(readings = mapOf(p to Reading(1.0, 0L)))
            assertEquals("$p", 1.0, s.value(p, nowMs = 3000)!!, 0.0)
            assertNull("$p", s.value(p, nowMs = 3001))
        }
    }

    @Test
    fun value_slowPidsStaleAfter10s() {
        for (p in listOf(Pid.COOLANT, Pid.IAT, Pid.OIL)) {
            val s = CarState(readings = mapOf(p to Reading(90.0, 0L)))
            assertEquals("$p", 90.0, s.value(p, nowMs = 3001)!!, 0.0)
            assertEquals("$p", 90.0, s.value(p, nowMs = 10_000)!!, 0.0)
            assertNull("$p", s.value(p, nowMs = 10_001))
        }
    }

    @Test
    fun staleTable() {
        assertEquals(10_000L, Pid.COOLANT.staleMs)
        assertEquals(3_000L, Pid.RPM.staleMs)
    }

    @Test
    fun driveMode_nextCycles() {
        assertEquals(DriveMode.SPORT, DriveMode.COMFORT.next())
        assertEquals(DriveMode.SPORT_PLUS, DriveMode.SPORT.next())
        assertEquals(DriveMode.COMFORT, DriveMode.SPORT_PLUS.next())
    }

    @Test
    fun style_nextCycles() {
        assertEquals(Style.HUD, Style.RACING.next())
        assertEquals(Style.ARCADE, Style.HUD.next())
        assertEquals(Style.LUXURY, Style.ARCADE.next())
        assertEquals(Style.CLUSTER, Style.LUXURY.next())
        assertEquals(Style.MOST_WANTED, Style.CLUSTER.next())
        assertEquals(Style.COCKPIT, Style.MOST_WANTED.next())
        assertEquals(Style.RACING, Style.COCKPIT.next())
    }
}
