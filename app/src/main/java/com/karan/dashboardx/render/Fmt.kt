package com.karan.dashboardx.render

import java.util.Locale
import kotlin.math.roundToInt

object Fmt {
    fun temp(v: Double?, unsupported: Boolean): String = when {
        v != null -> "${v.roundToInt()}°"
        unsupported -> "N/A"
        else -> "--"
    }

    fun int(v: Double?): String = if (v == null) "--" else v.roundToInt().toString()

    fun kml(v: Double?): String = when {
        v == null -> "--"
        v == Double.POSITIVE_INFINITY -> "∞"
        else -> String.format(Locale.US, "%.1f", v)
    }

    fun clamp01(v: Double, lo: Double, hi: Double): Double =
        ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)
}
