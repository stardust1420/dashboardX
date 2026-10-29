package com.karan.dashboardx.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.karan.dashboardx.alerts.Warning
import kotlin.math.floor
import kotlin.math.pow

/** Style C, "Retro arcade": a port of drawC() in .superpowers/mockups/styles.html. */
class ArcadeRenderer(private val fonts: Fonts) : GaugeRenderer {

    private companion object {
        const val C_NIGHT = 0xFF0D0221.toInt()
        const val C_PURPLE = 0xFF2B0A4A.toInt()
        const val C_OFF = 0xFF251040.toInt()
        const val C_CYAN = 0xFF00F0FF.toInt()
        const val C_PINK = 0xFFFF2975.toInt()
        const val C_RED = 0xFFFF2A2A.toInt()
        const val C_YELLOW = 0xFFFFD319.toInt()
        const val C_VIOLET = 0xFFB967FF.toInt()
        const val C_WHITE = 0xFFFFFFFF.toInt()

        const val HORIZON = 300f
        const val SEGS = 28
        const val PINK_FROM_RPM = 4500.0
        const val V_LINES = 12 // radial lines each side of centre
        const val H_LINES = 10

        /** Grid scroll in cycles per second per km/h (the mockup's per-frame step aliases at speed; this is smooth). */
        const val GRID_CYCLES_PER_KMH_S = 0.015
    }

    private val bg = Paint().apply { color = C_NIGHT }
    private val skyPaint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, 300f, C_NIGHT, C_PURPLE, Shader.TileMode.CLAMP)
    }
    private val sunPaint = Paint().apply {
        shader = LinearGradient(0f, 190f, 0f, 300f, C_YELLOW, C_PINK, Shader.TileMode.CLAMP)
    }
    private val stripePaint = Paint().apply { color = C_PURPLE }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = C_PINK
        setShadowLayer(glowRadius(6f), 0f, 0f, C_PINK)
    }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint()

    private fun text(size: Float, align: Paint.Align, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.pressStart2p
        textSize = size; textAlign = align; this.color = color
    }

    private val rpmText = text(40f, Paint.Align.CENTER, C_WHITE).apply {
        setShadowLayer(glowRadius(14f), 0f, 0f, C_CYAN)
    }
    private val rpmLabel = text(12f, Paint.Align.CENTER, C_CYAN)
    private val shiftText = text(18f, Paint.Align.CENTER, C_YELLOW)
    private val statText = text(11f, Paint.Align.LEFT, C_WHITE)
    private val scoreLabel = text(11f, Paint.Align.RIGHT, C_YELLOW)
    private val scoreValue = text(26f, Paint.Align.RIGHT, C_WHITE)
    private val speedText = text(11f, Paint.Align.RIGHT, C_CYAN)

    // Canvas textBaseline='top' emulation (em-box top ~ ascent line).
    private fun topOffset(p: Paint): Float = -p.fontMetrics.ascent
    private val topRpm = topOffset(rpmText)
    private val topRpmLabel = topOffset(rpmLabel)
    private val topShift = topOffset(shiftText)
    private val topStat = topOffset(statText)
    private val topScoreLabel = topOffset(scoreLabel)
    private val topScoreValue = topOffset(scoreValue)
    private val topSpeed = topOffset(speedText)

    // Static geometry.
    private val sunPath = Path().apply {
        moveTo(290f, HORIZON)
        arcTo(RectF(290f, HORIZON - 110f, 510f, HORIZON + 110f), 180f, 180f)
        close()
    }
    private val radial = FloatArray((V_LINES * 2 + 1) * 4).also {
        var n = 0
        for (i in -V_LINES..V_LINES) {
            it[n++] = 400f + i * 14f; it[n++] = HORIZON
            it[n++] = 400f + i * 110f; it[n++] = 480f
        }
    }
    // y = 300 + z^2.2 * 180 is evaluated per frame (10 pow calls, no allocation) because z scrolls.

    private var gridOff = 0.0
    private var lastT = Double.NaN

    private val tRpm = TextCache()
    private val tH2o = TextCache()
    private val tOil = TextCache()
    private val tAir = TextCache()
    private val tThr = TextCache()
    private val tKml = TextCache()
    private val tSpeed = TextCache()

    override fun draw(c: Canvas, f: FrameModel) {
        advanceGrid(f)
        drawScene(c)
        drawGrid(c)
        drawPowerBar(c, f)
        drawRpm(c, f)
        drawStats(c, f)
        drawStatusOverlay(c, f, fonts)
    }

    private fun advanceGrid(f: FrameModel) {
        val dt = f.tSec - lastT
        lastT = f.tSec
        if (dt.isNaN() || dt < 0.0 || dt > 1.0) {
            gridOff = 0.0
            return
        }
        val speed = f.speed ?: 0.0
        gridOff = (gridOff + speed * GRID_CYCLES_PER_KMH_S * dt) % 1.0
    }

    private fun drawScene(c: Canvas) {
        c.drawRect(0f, 0f, 800f, 300f, skyPaint)
        c.drawRect(0f, 300f, 800f, 480f, bg)
        val save = c.save()
        c.clipPath(sunPath)
        c.drawRect(290f, 190f, 510f, 300f, sunPaint)
        for (i in 0 until 6) c.drawRect(290f, 250f + i * 9f, 510f, 250f + i * 9f + 2f + i, stripePaint)
        c.restoreToCount(save)
    }

    private fun drawGrid(c: Canvas) {
        c.drawLines(radial, gridPaint)
        for (k in 0 until H_LINES) {
            val z = (k + gridOff) / H_LINES
            val y = HORIZON + (z.pow(2.2) * 180.0).toFloat()
            c.drawLine(0f, y, 800f, y, gridPaint)
        }
    }

    private fun drawPowerBar(c: Canvas, f: FrameModel) {
        val rpm = f.rpm
        val on = if (rpm == null) 0 else Math.round(Fmt.clamp01(rpm, 0.0, TACH_MAX_RPM) * SEGS).toInt()
        for (i in 0 until SEGS) {
            val v = i.toDouble() / SEGS * TACH_MAX_RPM
            val col = if (v >= f.redline) C_RED else if (v >= PINK_FROM_RPM) C_PINK else C_CYAN
            if (i < on) {
                segPaint.color = col
                segPaint.setShadowLayer(glowRadius(10f), 0f, 0f, col)
            } else {
                segPaint.color = C_OFF
                segPaint.clearShadowLayer()
            }
            c.drawRect(64f + i * 24f, 22f, 64f + i * 24f + 18f, 56f, segPaint)
        }
    }

    private fun drawRpm(c: Canvas, f: FrameModel) {
        val rpm = f.rpm
        val s = tRpm.get(textKey(rpm, 0.1)) { Fmt.int(rpm?.let { Math.round(it / 10) * 10.0 }).padStart(4, ' ') }
        c.drawText(s, 400f, 76f + topRpm, rpmText)
        c.drawText("RPM", 400f, 128f + topRpmLabel, rpmLabel)
        if (Warning.REDLINE in f.warnings && floor(f.tSec * 6.0).toLong() % 2L == 1L) {
            c.drawText("SHIFT UP!", 400f, 156f + topShift, shiftText)
        }
    }

    private fun drawStats(c: Canvas, f: FrameModel) {
        val clt = f.coolant
        stat(c, 30f, 180f, tH2o.get(textKey(clt)) { "H2O " + Fmt.temp(clt, false) }, clt, 40.0, 120.0, C_CYAN)
        val oil = f.oil
        stat(c, 30f, 232f, tOil.get(textKey(oil, unsupported = f.oilUnsupported)) { "OIL " + Fmt.temp(oil, f.oilUnsupported) },
            oil, 40.0, 140.0, C_YELLOW)
        val iat = f.iat
        stat(c, 30f, 284f, tAir.get(textKey(iat)) { "AIR " + Fmt.temp(iat, false) }, iat, 0.0, 80.0, C_VIOLET)
        val thr = f.throttle
        stat(c, 612f, 180f, tThr.get(textKey(thr)) { "THR " + if (thr == null) Fmt.int(null) else Fmt.int(thr) + "%" },
            thr, 0.0, 100.0, C_PINK)

        c.drawText("SCORE km/l", 770f, 232f + topScoreLabel, scoreLabel)
        val kml = f.instantKml
        c.drawText(tKml.get(textKey(kml, 10.0)) { Fmt.kml(kml) }, 770f, 252f + topScoreValue, scoreValue)
        val speed = f.speed
        c.drawText(tSpeed.get(textKey(speed)) { Fmt.int(speed) + " KM/H" }, 770f, 298f + topSpeed, speedText)
    }

    private fun stat(c: Canvas, x: Float, y: Float, label: String, v: Double?, lo: Double, hi: Double, col: Int) {
        c.drawText(label, x, y + topStat, statText)
        val lit = if (v == null) 0 else Math.round(Fmt.clamp01(v, lo, hi) * 10).toInt()
        for (i in 0 until 10) {
            fillPaint.color = if (i < lit) col else C_OFF
            c.drawRect(x + i * 15f, y + 18f, x + i * 15f + 12f, y + 30f, fillPaint)
        }
    }
}
