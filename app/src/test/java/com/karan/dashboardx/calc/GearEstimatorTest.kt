package com.karan.dashboardx.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GearEstimatorTest {
    private val r = GearEstimator.RPM_PER_KMH

    /** Feeds [speed] with the rpm of [gear] (times [offset]) every 100 ms for [ms]; returns the last result. */
    private fun GearEstimator.drive(gear: Int, speed: Double, ms: Long, startMs: Long, offset: Double = 1.0): Int? {
        var g: Int? = null
        var t = startMs
        while (t < startMs + ms) {
            g = update(r[gear - 1] * speed * offset, speed, t)
            t += 100
        }
        return g
    }

    @Test fun recognisesEveryGear() {
        for (gear in 1..6) {
            val e = GearEstimator()
            assertEquals("gear $gear", gear, e.drive(gear, 40.0, 1000, 0))
        }
    }

    @Test fun nullBelowMinimumSpeed() {
        val e = GearEstimator()
        e.drive(1, 20.0, 1000, 0)
        assertNull(e.update(r[0] * 5.0, 5.0, 1100))
    }

    @Test fun nullWithoutRpmOrSpeed() {
        val e = GearEstimator()
        assertNull(e.update(null, 50.0, 0))
        assertNull(e.update(2000.0, null, 100))
    }

    @Test fun newGearShowsOnlyAfterItHolds() {
        val e = GearEstimator()
        e.drive(3, 50.0, 1000, 0)
        // 4th for 200 ms: still 3rd; after 400 ms: 4th.
        assertEquals(3, e.drive(4, 50.0, 200, 1000))
        assertEquals(4, e.drive(4, 50.0, 400, 1200))
    }

    @Test fun holdsLastGearThroughAShiftThenGivesUp() {
        val e = GearEstimator()
        e.drive(2, 40.0, 1000, 0)
        // Ratio halfway (in log terms) between 2nd and 3rd: a shift or converter slip.
        val between = Math.sqrt(r[1] * r[2]) * 40.0
        assertEquals(2, e.update(between, 40.0, 1000))
        assertEquals(2, e.update(between, 40.0, 2400))
        assertNull(e.update(between, 40.0, 2600))
    }

    @Test fun learnsAUniformOffset() {
        val e = GearEstimator()
        // Final drive / tyres 10 % off the defaults: 5th still matches, and teaches the offset.
        assertEquals(5, e.drive(5, 80.0, 30_000, 0, offset = 1.10))
        // 19 % off nominal is outside the default tolerance, but 8 % off the learned offset.
        assertEquals(2, e.drive(2, 40.0, 1000, 30_000, offset = 1.10 * 1.08))
    }

    @Test fun lowGearsDoNotTeach() {
        val e = GearEstimator()
        // Converter slip in 1st reads high; it must not shift the learned scale.
        e.drive(1, 15.0, 30_000, 0, offset = 1.10)
        assertEquals(6, e.drive(6, 100.0, 1000, 30_000))
        assertEquals(1.0, e.scale, 1e-9)
    }
}
