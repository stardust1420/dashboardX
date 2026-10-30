package com.karan.dashboardx.calc

object FuelEconomy {
    private const val AFR = 14.7
    private const val GASOLINE_G_PER_L = 740.0

    fun lphFromMaf(mafGs: Double, calibration: Double = 1.0): Double =
        mafGs * 3600.0 / (AFR * GASOLINE_G_PER_L) * calibration

    /** Estimated air mass flow in g/s from MAP, RPM and intake temperature (4-stroke speed-density). */
    fun mafFromSpeedDensity(
        mapKpa: Double,
        rpm: Double,
        iatC: Double,
        displacementL: Double,
        ve: Double = 0.85,
    ): Double =
        mapKpa * 1000.0 * (displacementL / 1000.0) * ve * (rpm / 120.0) /
            (287.05 * (iatC + 273.15)) * 1000.0

    fun instantKml(speedKmh: Double?, throttlePct: Double?, lph: Double?): Double? {
        if (speedKmh == null || speedKmh < 3.0 || lph == null) return null
        if (throttlePct != null && throttlePct < 2.0 && speedKmh > 10.0) return Double.POSITIVE_INFINITY
        return minOf(50.0, speedKmh / lph)
    }
}

class TripAverage {
    private var km = 0.0
    private var litres = 0.0

    fun add(speedKmh: Double, lph: Double, dtMs: Long) {
        km += speedKmh * dtMs / 3_600_000.0
        litres += lph * dtMs / 3_600_000.0
    }

    fun kml(): Double? = if (litres >= 0.01) km / litres else null
}
