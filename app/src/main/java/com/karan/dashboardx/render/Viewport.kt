package com.karan.dashboardx.render

import kotlin.math.min

/** Fit of the 800x480 logical canvas into a physical area, centered. */
data class Viewport(val scale: Float, val dx: Float, val dy: Float) {
    companion object {
        const val LOGICAL_W = 800f
        const val LOGICAL_H = 480f

        fun fit(left: Int, top: Int, width: Int, height: Int): Viewport {
            val scale = min(width / LOGICAL_W, height / LOGICAL_H)
            val dx = left + (width - LOGICAL_W * scale) / 2f
            val dy = top + (height - LOGICAL_H * scale) / 2f
            return Viewport(scale, dx, dy)
        }
    }
}
