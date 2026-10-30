package com.karan.dashboardx.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The engine-sound toggle drawn into the dash: a speaker in an outlined box, like [ModeSelector].
 * On: [accent] waves and edge, glowing. Off: a [dim] speaker with a cross. Each renderer makes one
 * in its own colours and corner [radius] (half the height for a circle) and reports [hit] as its
 * [GaugeRenderer.soundHit]. Allocation-free per frame.
 *
 * [draw] takes the side column's shift ([shifted]); [hit] follows it.
 */
class SoundButton(
    left: Float, top: Float, right: Float, bottom: Float,
    private val accent: Int,
    private val dim: Int,
    track: Int,
    private val radius: Float,
) {
    val rect = RectF(left, top, right, bottom)

    /** The box as last drawn, grown on every side, so it is easy to hit at arm's length while driving. */
    val hit = RectF(rect).apply { inset(-ModeSelector.HIT_PAD, -ModeSelector.HIT_PAD) }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = track }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    // The CarIcons speaker, in its 96-unit box, scaled to fit the button.
    private val unit = minOf(rect.width(), rect.height()) * 0.66f / GLYPH
    private val speaker = Path().apply {
        moveTo(12f, 36f); lineTo(28f, 36f); lineTo(48f, 18f); lineTo(48f, 78f); lineTo(28f, 60f); lineTo(12f, 60f)
        close()
    }
    private val waveIn = RectF(36f, 32f, 68f, 64f)
    private val waveOut = RectF(30f, 18f, 86f, 78f)

    fun draw(c: Canvas, on: Boolean, dx: Float = 0f) {
        hit.set(rect)
        hit.inset(-ModeSelector.HIT_PAD, -ModeSelector.HIT_PAD)
        hit.offset(dx, 0f)
        c.shifted(dx) { drawBox(c, on) }
    }

    private fun drawBox(c: Canvas, on: Boolean) {
        val col = if (on) accent else dim
        c.drawRoundRect(rect, radius, radius, bg)
        edge.color = col
        if (on) edge.setShadowLayer(glowRadius(10f), 0f, 0f, col) else edge.clearShadowLayer()
        c.drawRoundRect(rect, radius, radius, edge)

        body.color = col
        line.color = col
        if (on) {
            // The glyph is drawn scaled by [unit], so its glow is sized in glyph units.
            body.setShadowLayer(glowRadius(10f) / unit, 0f, 0f, col)
            line.setShadowLayer(glowRadius(10f) / unit, 0f, 0f, col)
        } else {
            body.clearShadowLayer()
            line.clearShadowLayer()
        }
        val save = c.save()
        c.translate(rect.centerX() - GLYPH / 2f * unit, rect.centerY() - GLYPH / 2f * unit)
        c.scale(unit, unit)
        c.drawPath(speaker, body)
        if (on) {
            line.strokeWidth = 7f
            c.drawArc(waveIn, -50f, 100f, false, line)
            c.drawArc(waveOut, -50f, 100f, false, line)
        } else {
            line.strokeWidth = 8f
            c.drawLine(60f, 34f, 84f, 62f, line)
            c.drawLine(84f, 34f, 60f, 62f, line)
        }
        c.restoreToCount(save)
    }

    private companion object {
        const val GLYPH = 96f
    }
}
