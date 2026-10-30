package com.karan.dashboardx.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.karan.dashboardx.data.DriveMode

/**
 * The drive-mode selector: Comfort / Sport / Sport+ side by side in one outlined box, the picked
 * one a solid glowing block in its [accents] colour with [litText] on it. Each renderer makes one
 * in its own type and colours and reports [hit] as its [GaugeRenderer.modeHit]. Allocation-free
 * per frame.
 *
 * [label] must be centre-aligned; its colour is set per segment.
 */
class ModeSelector(
    left: Float, top: Float, right: Float, bottom: Float,
    private val label: Paint,
    /** Lit colour per [DriveMode] ordinal. */
    private val accents: IntArray,
    private val dim: Int,
    track: Int,
    private val radius: Float,
    /** Label colour on the lit segment (dark, to read on the solid accent). */
    private val litText: Int = 0xFF0A0A0C.toInt(),
    private val labels: Array<String> = arrayOf("COMFORT", "SPORT", "SPORT+"),
) {
    val rect = RectF(left, top, right, bottom)

    /** The box grown on every side, so it is easy to hit at arm's length while driving. */
    val hit = RectF(rect).apply { inset(-HIT_PAD, -HIT_PAD) }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = track }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = dim
    }
    private val litFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seg = RectF()
    private val mid = middleOffset(label)

    fun draw(c: Canvas, mode: DriveMode) {
        c.drawRoundRect(rect, radius, radius, bg)
        c.drawRoundRect(rect, radius, radius, edge)
        val w = rect.width() / labels.size
        for (i in labels.indices) {
            val x = rect.left + i * w
            val on = i == mode.ordinal
            if (on) {
                val col = accents[i]
                seg.set(x + 3f, rect.top + 3f, x + w - 3f, rect.bottom - 3f)
                val r = (radius - 3f).coerceAtLeast(0f)
                litFill.color = col
                litFill.setShadowLayer(glowRadius(16f), 0f, 0f, col)
                c.drawRoundRect(seg, r, r, litFill)
            }
            label.color = if (on) litText else dim
            c.drawText(labels[i], x + w / 2f, rect.centerY() + mid, label)
        }
    }

    private companion object {
        const val HIT_PAD = 14f
    }
}
