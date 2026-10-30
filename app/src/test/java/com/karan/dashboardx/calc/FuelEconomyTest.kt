package com.karan.dashboardx.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelEconomyTest {
    @Test fun lphFromMaf_10gs() {
        assertEquals(3.3094, FuelEconomy.lphFromMaf(10.0), 0.001)
        assertEquals(3.6404, FuelEconomy.lphFromMaf(10.0, 1.1), 0.001)
    }

    @Test fun speedDensity_example() {
        assertEquals(12.95, FuelEconomy.mafFromSpeedDensity(50.0, 2000.0, 30.0, 1.591), 0.05)
    }

    @Test fun instant_cruise() = assertEquals(18.13, FuelEconomy.instantKml(60.0, 20.0, 3.3094)!!, 0.01)
    @Test fun instant_stopped() = assertNull(FuelEconomy.instantKml(0.0, 0.0, 0.8))
    @Test fun instant_fuelCut() = assertEquals(Double.POSITIVE_INFINITY, FuelEconomy.instantKml(50.0, 0.0, 0.5)!!, 0.0)
    @Test fun instant_capped() = assertEquals(50.0, FuelEconomy.instantKml(100.0, 10.0, 0.1)!!, 0.0)

    @Test fun trip_average() {
        val t = TripAverage()
        assertNull(t.kml())
        t.add(60.0, 4.0, 3_600_000)
        assertEquals(15.0, t.kml()!!, 0.001)
    }
}
