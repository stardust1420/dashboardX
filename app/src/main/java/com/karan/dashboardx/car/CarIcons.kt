package com.karan.dashboardx.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat

/**
 * Action-strip icons drawn in code (the build has no R class): white glyphs on a transparent
 * 96×96 bitmap. Built once on first use and shared; CarIcon is immutable.
 */
internal object CarIcons {
    private const val SIZE = 96

    val style: CarIcon by lazy { icon(::drawStyle) }
    val soundOn: CarIcon by lazy { icon { c, p -> drawSpeaker(c, p); drawWaves(c, p) } }
    val soundOff: CarIcon by lazy { icon { c, p -> drawSpeaker(c, p); drawCross(c, p) } }

    private fun icon(draw: (Canvas, Paint) -> Unit): CarIcon {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        draw(Canvas(bmp), paint)
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    /** A gauge: 270° arc, needle and hub. */
    private fun drawStyle(c: Canvas, p: Paint) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 9f
        c.drawArc(RectF(14f, 14f, 82f, 82f), 135f, 270f, false, p)
        p.strokeWidth = 8f
        c.drawLine(48f, 48f, 70f, 30f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(48f, 48f, 8f, p)
    }

    private fun drawSpeaker(c: Canvas, p: Paint) {
        p.style = Paint.Style.FILL
        val body = Path().apply {
            moveTo(12f, 36f)
            lineTo(28f, 36f)
            lineTo(48f, 18f)
            lineTo(48f, 78f)
            lineTo(28f, 60f)
            lineTo(12f, 60f)
            close()
        }
        c.drawPath(body, p)
    }

    private fun drawWaves(c: Canvas, p: Paint) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 7f
        c.drawArc(RectF(36f, 32f, 68f, 64f), -50f, 100f, false, p)
        c.drawArc(RectF(30f, 18f, 86f, 78f), -50f, 100f, false, p)
    }

    private fun drawCross(c: Canvas, p: Paint) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 8f
        c.drawLine(60f, 34f, 84f, 62f, p)
        c.drawLine(84f, 34f, 60f, 62f, p)
    }
}
