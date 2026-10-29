package com.karan.dashboardx.obd

import com.karan.dashboardx.data.Pid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollScheduleTest {
    private val all = Pid.entries.toSet()

    @Test
    fun schedule_everyCycleHasRpmAndThrottle() {
        for (c in 0..19) {
            val l = PollSchedule.pidsFor(c, all)
            assertTrue(Pid.RPM in l && Pid.THROTTLE in l)
        }
    }

    @Test
    fun schedule_evenCyclesAddSpeedAndAirflow() {
        val l = PollSchedule.pidsFor(2, all)
        assertTrue(Pid.SPEED in l && Pid.MAF in l)
        val m = PollSchedule.pidsFor(2, all - Pid.MAF)
        assertTrue(Pid.SPEED in m && Pid.MAP in m)
    }

    @Test
    fun schedule_tempsEveryTenth() {
        val l = PollSchedule.pidsFor(10, all)
        assertTrue(Pid.COOLANT in l && Pid.IAT in l && Pid.OIL in l)
        val n = PollSchedule.pidsFor(11, all)
        assertFalse(Pid.COOLANT in n || Pid.IAT in n || Pid.OIL in n)
    }

    @Test
    fun schedule_filtersUnsupported() {
        val s = all - Pid.OIL
        for (c in 0..29) assertFalse(Pid.OIL in PollSchedule.pidsFor(c, s))
    }
}
