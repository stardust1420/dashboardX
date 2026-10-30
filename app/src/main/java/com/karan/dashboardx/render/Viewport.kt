package com.karan.dashboardx.render

import kotlin.math.min

/**
 * Fit of the 800x480 logical canvas into a physical area, centered. On an area wider than 5:3 the
 * height fills and [ext] logical px show beyond each side of the 800-wide core: the renderers
 * paint their backdrop over that and slide their side columns out into it, so there are no bars.
 */
data class Viewport(val scale: Float, val dx: Float, val dy: Float, val ext: Float = 0f) {
    /** Physical x (view or surface pixels) to the logical 800×480 space (negative or past 800 in [ext]). */
    fun toLogicalX(x: Float): Float = (x - dx) / scale

    /** Physical y to the logical 800×480 space. */
    fun toLogicalY(y: Float): Float = (y - dy) / scale

    companion object {
        const val LOGICAL_W = 800f
        const val LOGICAL_H = 480f

        fun fit(left: Int, top: Int, width: Int, height: Int): Viewport {
            val scale = min(width / LOGICAL_W, height / LOGICAL_H)
            val dx = left + (width - LOGICAL_W * scale) / 2f
            val dy = top + (height - LOGICAL_H * scale) / 2f
            val ext = ((width / scale - LOGICAL_W) / 2f).coerceAtLeast(0f)
            return Viewport(scale, dx, dy, ext)
        }

        /**
         * Fits into the whole surface (the car dash is full screen; the host's buttons float on top).
         * The size is clamped to at least 1 px so the scale stays positive.
         */
        fun fitSurface(surfaceW: Int, surfaceH: Int): Viewport =
            fit(0, 0, surfaceW.coerceAtLeast(1), surfaceH.coerceAtLeast(1))
    }
}
