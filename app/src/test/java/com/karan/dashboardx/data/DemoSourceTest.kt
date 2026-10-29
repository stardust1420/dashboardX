package com.karan.dashboardx.data

import org.junit.Assert.assertTrue
import org.junit.Test

class DemoSourceTest {
    private fun at(s: CarState, p: Pid) = s.readings.getValue(p).value

    @Test
    fun demo_rpmStaysInRange() {
        val d = DemoSource { 0L }
        var i = 0
        while (i <= 1200) {
            val s = d.stepAt(i * 0.05)
            assertTrue(at(s, Pid.RPM) in 700.0..7000.0)
            assertTrue(at(s, Pid.THROTTLE) in 0.0..100.0)
            assertTrue(at(s, Pid.COOLANT) in 85.0..95.0)
            i++
        }
    }

    @Test
    fun demo_reachesRedlineDuringPull() {
        val d = DemoSource { 0L }
        var max = 0.0
        var i = 0
        while (i <= 180) {
            max = maxOf(max, at(d.stepAt(i * 0.05), Pid.RPM))
            i++
        }
        assertTrue("max rpm $max", max > 6200.0)
    }
}
