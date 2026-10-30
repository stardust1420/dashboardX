package com.karan.dashboardx.render

import kotlin.math.exp

/** Exponential smoother. First non-null target snaps; null target reports null but keeps the last value. */
class Smoother(private val tauMs: Double = 80.0) {
    private var targetValue: Double? = null
    private var current: Double? = null

    fun target(v: Double?) {
        targetValue = v
        if (v != null && current == null) current = v
    }

    fun advance(dtMs: Long): Double? {
        val t = targetValue ?: return null
        val c = current ?: t
        val next = c + (t - c) * (1.0 - exp(-dtMs.toDouble() / tauMs))
        current = next
        return next
    }
}
