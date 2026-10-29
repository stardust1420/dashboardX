package com.karan.dashboardx.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.karan.dashboardx.alerts.Warning
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Style A, "Racing game": a port of drawA() in .superpowers/mockups/styles.html. */
class RacingRenderer(private val fonts: Fonts) : GaugeRenderer {

    private companion object {
        const val CX = 400f
        const val CY = 292f
        const val R = 172f
        const val A0 = PI * 0.75
        const val SWEEP = PI * 1.5
        const val A0_DEG = 135f
        const val SWEEP_DEG = 270f
        const val LIGHTS = 15
        const val SHIFT_BAND_RPM = 2200.0 // lights start this far below the redline (mockup: 4000..6200)
        const val BAR_W = 150f

        const val C_LABEL = 0xFF7F8A9C.toInt()
        const val C_WHITE = 0xFFFFFFFF.toInt()
        const val C_SOFT = 0xFFC9D1DC.toInt()
        const val C_TRACK = 0xFF1A2029.toInt()
        const val C_RED = 0xFFFF2D3D.toInt()
        const val C_GREEN = 0xFF2BFF6B.toInt()
        const val C_YELLOW = 0xFFFFD21F.toInt()
        const val C_BLUE_FLASH = 0xFF3D8BFF.toInt()
        const val C_LIGHT_OFF = 0xFF1D222B.toInt()
        const val C_BAR_COOL = 0xFF00B8FF.toInt()
        const val C_CYAN = 0xFF00E5FF.toInt()
        const val C_TICK = 0xFF8A94A6.toInt()
        const val C_NEEDLE = 0xFFFF3B30.toInt()
        const val C_REDZONE = 0xFF5A0F16.toInt()

        // Mockup warn thresholds for the tiles that have no configurable warning.
        const val OIL_WARN = 125.0
        const val IAT_WARN = 65.0
    }

    // ---- paints (allocated once) ----
    private val bgPaint = Paint().apply {
        shader = RadialGradient(
            400f, 290f, 480f,
            intArrayOf(0xFF141A24.toInt(), 0xFF141A24.toInt(), 0xFF05070A.toInt()),
            floatArrayOf(0f, 40f / 480f, 1f), Shader.TileMode.CLAMP,
        )
    }
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 18f; strokeCap = Paint.Cap.BUTT
    }
    private val valueArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 18f; strokeCap = Paint.Cap.BUTT
        shader = LinearGradient(
            CX - R, 0f, CX + R, 0f,
            intArrayOf(C_CYAN, 0xFFFFB000.toInt(), C_RED), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        setShadowLayer(glowRadius(14f), 0f, 0f, C_CYAN)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = C_NEEDLE
        setShadowLayer(glowRadius(10f), 0f, 0f, C_NEEDLE)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun text(weight: Int, size: Float, align: Paint.Align, color: Int) =
        fonts.orbitron(Paint(Paint.ANTI_ALIAS_FLAG), weight).apply {
            textSize = size; textAlign = align; this.color = color
        }

    private val tickLabel = text(600, 18f, Paint.Align.CENTER, C_SOFT)
    private val rpmText = text(800, 54f, Paint.Align.CENTER, C_WHITE)
    private val rpmUnit = text(500, 14f, Paint.Align.CENTER, C_LABEL)
    private val speedText = text(500, 26f, Paint.Align.CENTER, C_SOFT)
    private val label = text(500, 13f, Paint.Align.LEFT, C_LABEL)
    private val value = text(800, 34f, Paint.Align.LEFT, C_WHITE)
    private val small = text(500, 14f, Paint.Align.LEFT, C_LABEL)

    // Canvas textBaseline='middle' emulation: per-paint offset, computed once.
    private val midTick = middleOffset(tickLabel)
    private val midRpm = middleOffset(rpmText)
    private val midRpmUnit = middleOffset(rpmUnit)
    private val midSpeed = middleOffset(speedText)
    private val midLabel = middleOffset(label)
    private val midValue = middleOffset(value)
    private val midSmall = middleOffset(small)

    private val oval = RectF(CX - R, CY - R, CX + R, CY + R)
    private val barRect = RectF()

    // Static tick geometry: 0..7000 step 500.
    private val tickCount = (TACH_MAX_RPM / 500).toInt() + 1
    private val tickRpm = DoubleArray(tickCount) { it * 500.0 }
    private val tickCos = FloatArray(tickCount) { cos(angle(tickRpm[it])).toFloat() }
    private val tickSin = FloatArray(tickCount) { sin(angle(tickRpm[it])).toFloat() }
    private val tickText = Array(tickCount) { (tickRpm[it] / 1000).toInt().toString() }

    // ---- per-value text caches ----
    private val tRpm = TextCache()
    private val tSpeed = TextCache()
    private val tClt = TextCache()
    private val tOil = TextCache()
    private val tIat = TextCache()
    private val tThr = TextCache()
    private val tKml = TextCache()
    private val tTrip = TextCache()

    private fun angle(rpm: Double): Double = A0 + Fmt.clamp01(rpm, 0.0, TACH_MAX_RPM) * SWEEP

    override fun draw(c: Canvas, f: FrameModel) {
        c.drawRect(0f, 0f, Viewport.LOGICAL_W, Viewport.LOGICAL_H, bgPaint)
        drawShiftLights(c, f)
        drawTach(c, f)
        drawBars(c, f)
        drawStatusOverlay(c, f, fonts)
    }

    private fun drawShiftLights(c: Canvas, f: FrameModel) {
        val rpm = f.rpm
        val lit = if (rpm == null) 0
        else Math.round(Fmt.clamp01(rpm, f.redline - SHIFT_BAND_RPM, f.redline.toDouble()) * LIGHTS).toInt()
        val flash = Warning.REDLINE in f.warnings && floor(f.tSec * 8.0).toLong() % 2L == 1L
        for (i in 0 until LIGHTS) {
            val on = i < lit
            val col = if (flash) C_BLUE_FLASH else if (i < 5) C_GREEN else if (i < 10) C_YELLOW else C_RED
            if (on || flash) {
                lightPaint.color = col
                lightPaint.setShadowLayer(glowRadius(16f), 0f, 0f, col)
            } else {
                lightPaint.color = C_LIGHT_OFF
                lightPaint.clearShadowLayer()
            }
            c.drawCircle(CX - 7 * 26 + i * 26, 30f, 9f, lightPaint)
        }
    }

    private fun drawTach(c: Canvas, f: FrameModel) {
        val redline = f.redline.toDouble()
        arcPaint.color = C_TRACK
        c.drawArc(oval, A0_DEG, SWEEP_DEG, false, arcPaint)
        val redFrac = Fmt.clamp01(redline, 0.0, TACH_MAX_RPM).toFloat()
        arcPaint.color = C_REDZONE
        c.drawArc(oval, A0_DEG + redFrac * SWEEP_DEG, (1f - redFrac) * SWEEP_DEG, false, arcPaint)

        val rpm = f.rpm
        if (rpm != null) {
            val sweep = Fmt.clamp01(rpm, 0.0, TACH_MAX_RPM).toFloat() * SWEEP_DEG
            if (sweep > 0f) c.drawArc(oval, A0_DEG, sweep, false, valueArcPaint)
        }

        for (i in 0 until tickCount) {
            val v = tickRpm[i]
            val major = i % 2 == 0
            val red = v >= redline
            val r1 = R - 16
            val r2 = R - if (major) 34 else 24
            tickPaint.color = if (red) C_RED else C_TICK
            tickPaint.strokeWidth = if (major) 3f else 1.5f
            c.drawLine(CX + tickCos[i] * r1, CY + tickSin[i] * r1, CX + tickCos[i] * r2, CY + tickSin[i] * r2, tickPaint)
            if (major) {
                tickLabel.color = if (red) C_RED else C_SOFT
                c.drawText(tickText[i], CX + tickCos[i] * (R - 54), CY + tickSin[i] * (R - 54) + midTick, tickLabel)
            }
        }

        if (rpm != null) {
            val na = angle(rpm)
            val ca = cos(na).toFloat()
            val sa = sin(na).toFloat()
            c.drawLine(CX + ca * 70, CY + sa * 70, CX + ca * (R - 8), CY + sa * (R - 8), needlePaint)
        }

        val rpmStr = tRpm.get(textKey(rpm, 0.1)) { Fmt.int(rpm?.let { Math.round(it / 10) * 10.0 }) }
        c.drawText(rpmStr, CX, CY + 4 + midRpm, rpmText)
        c.drawText("RPM × 1000", CX, CY + 44 + midRpmUnit, rpmUnit)
        val speed = f.speed
        val speedStr = tSpeed.get(textKey(speed)) { Fmt.int(speed) + " km/h" }
        c.drawText(speedStr, CX, CY + 128 + midSpeed, speedText)
    }

    private fun drawBars(c: Canvas, f: FrameModel) {
        val clt = f.coolant
        bar(c, 36f, 110f, "COOLANT", tClt.get(textKey(clt)) { Fmt.temp(clt, false) },
            clt, 40.0, 120.0, Warning.OVERHEAT in f.warnings)
        val oil = f.oil
        bar(c, 36f, 215f, "OIL", tOil.get(textKey(oil, unsupported = f.oilUnsupported)) { Fmt.temp(oil, f.oilUnsupported) },
            oil, 40.0, 140.0, oil != null && oil >= OIL_WARN)
        val iat = f.iat
        bar(c, 36f, 320f, "INTAKE AIR", tIat.get(textKey(iat)) { Fmt.temp(iat, false) },
            iat, 0.0, 80.0, iat != null && iat >= IAT_WARN)
        val thr = f.throttle
        bar(c, 614f, 110f, "THROTTLE", tThr.get(textKey(thr)) { if (thr == null) Fmt.int(null) else Fmt.int(thr) + "%" },
            thr, 0.0, 100.0, false)

        c.drawText("ECONOMY", 614f, 215f + midLabel, label)
        value.color = C_WHITE
        val kml = f.instantKml
        c.drawText(tKml.get(textKey(kml, 10.0)) { Fmt.kml(kml) }, 614f, 247f + midValue, value)
        c.drawText("km/l", 614f, 280f + midSmall, small)
        val trip = f.tripKml
        c.drawText(tTrip.get(textKey(trip, 10.0)) { "TRIP AVG  " + Fmt.kml(trip) }, 614f, 340f + midSmall, small)
    }

    private fun bar(c: Canvas, x: Float, y: Float, name: String, text: String, v: Double?, lo: Double, hi: Double, warn: Boolean) {
        c.drawText(name, x, y + midLabel, label)
        value.color = if (warn) C_RED else C_WHITE
        c.drawText(text, x, y + 32 + midValue, value)
        barRect.set(x, y + 58, x + BAR_W, y + 68)
        barPaint.color = C_TRACK
        c.drawRoundRect(barRect, 5f, 5f, barPaint)
        if (v == null) return
        val frac = Fmt.clamp01(v, lo, hi)
        if (frac <= 0.0) return
        barRect.right = x + BAR_W * frac.toFloat()
        barPaint.color = if (warn) C_RED else if (frac > 0.55) C_GREEN else C_BAR_COOL
        c.drawRoundRect(barRect, 5f, 5f, barPaint)
    }
}
