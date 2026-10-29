package com.karan.dashboardx.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DriveModeEstimatorTest {
    /** Feeds [rpm] and [throttle] every 100 ms for [ms] from [startMs]; returns the last mode. */
    private fun DriveModeEstimator.drive(rpm: Double?, throttle: Double?, ms: Long, startMs: Long): DriveMode {
        var m = mode
        var t = startMs
        while (t < startMs + ms) {
            m = update(rpm, throttle, t)
            t += 100
        }
        return m
    }

    @Test fun startsInComfort() {
        assertEquals(DriveMode.COMFORT, DriveModeEstimator().mode)
    }

    @Test fun gentleCruiseIsEco() {
        assertEquals(DriveMode.ECO, DriveModeEstimator().drive(1800.0, 12.0, 10_000, 0))
    }

    @Test fun moderateDrivingIsComfort() {
        assertEquals(DriveMode.COMFORT, DriveModeEstimator().drive(3000.0, 30.0, 10_000, 0))
    }

    @Test fun hardPullIsSport() {
        assertEquals(DriveMode.SPORT, DriveModeEstimator().drive(5500.0, 90.0, 3000, 0))
    }

    @Test fun aBriefBlipDoesNotMeanSport() {
        val e = DriveModeEstimator()
        e.drive(1800.0, 12.0, 10_000, 0)
        assertNotEquals(DriveMode.SPORT, e.drive(4500.0, 80.0, 300, 10_000))
    }

    /** Sport lingers after a pull (slow decay plus hysteresis), then settles back. */
    @Test fun sportLingersThenSettles() {
        val e = DriveModeEstimator()
        e.drive(5500.0, 90.0, 5000, 0)
        assertEquals(DriveMode.SPORT, e.drive(2000.0, 15.0, 1000, 5000))
        assertEquals(DriveMode.ECO, e.drive(2000.0, 15.0, 20_000, 6000))
    }

    @Test fun missingDataHoldsTheMode() {
        val e = DriveModeEstimator()
        e.drive(5500.0, 90.0, 5000, 0)
        assertEquals(DriveMode.SPORT, e.drive(null, null, 10_000, 5000))
    }
}
