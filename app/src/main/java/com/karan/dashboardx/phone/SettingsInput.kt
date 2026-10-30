package com.karan.dashboardx.phone

import java.util.Locale

/** Result of validating one settings text field. */
sealed interface Parsed<out T> {
    data class Ok<T>(val value: T) : Parsed<T>
    data class Err(val message: String) : Parsed<Nothing>
}

/** Pure parsing and range checks for the settings number fields (no android.* here). */
object SettingsInput {
    const val CAL_MIN = 0.5
    const val CAL_MAX = 1.5
    const val REDLINE_MIN = 3000
    const val REDLINE_MAX = 8000
    const val COOLANT_MIN = 90
    const val COOLANT_MAX = 130

    /** The two engines the app knows: 1.4 (1.396 L) and 1.6 (1.591 L). */
    val DISPLACEMENTS = listOf(1.396, 1.591)

    fun calibration(text: String): Parsed<Double> {
        val t = text.trim().replace(',', '.')
        val v = t.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: return Parsed.Err("Enter a number from $CAL_MIN to $CAL_MAX")
        return if (v < CAL_MIN || v > CAL_MAX) Parsed.Err("Must be from $CAL_MIN to $CAL_MAX") else Parsed.Ok(v)
    }

    fun redline(text: String): Parsed<Int> = intIn(text, REDLINE_MIN, REDLINE_MAX)

    fun coolant(text: String): Parsed<Int> = intIn(text, COOLANT_MIN, COOLANT_MAX)

    /** Index into [DISPLACEMENTS] of the value closest to [litres]. */
    fun displacementIndex(litres: Double): Int =
        DISPLACEMENTS.indices.minByOrNull { kotlin.math.abs(DISPLACEMENTS[it] - litres) } ?: 0

    /** How a calibration factor is shown in its field: up to 3 decimals, no trailing zeros. */
    fun formatCalibration(v: Double): String =
        String.format(Locale.US, "%.3f", v).trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }

    private fun intIn(text: String, min: Int, max: Int): Parsed<Int> {
        val v = text.trim().toIntOrNull() ?: return Parsed.Err("Enter a whole number from $min to $max")
        return if (v < min || v > max) Parsed.Err("Must be from $min to $max") else Parsed.Ok(v)
    }
}
