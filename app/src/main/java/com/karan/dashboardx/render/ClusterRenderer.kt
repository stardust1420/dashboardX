package com.karan.dashboardx.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.karan.dashboardx.alerts.Warning
import com.karan.dashboardx.calc.DriveMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Style E, "Cluster": a modern sports digital instrument cluster. Two hatched chevron gauges
 * (speed on the left, rpm on the right, mirrored) whose bands fill from the bottom with a glowing
 * leading edge; the rpm gauge holds a decaying peak marker. Thin strips inside the gauges show
 * coolant (left) and throttle (right). Centre: trip pill, big speed, a P R N D row with the estimated
 * gear. Bottom: economy, clock, drive mode, intake air and oil, with small icons.
 *
 * Chevron geometry: a gauge runs from its bottom point up to the apex and on to its top point, and
 * y is linear in the fraction (0 at the bottom, 1 at the top), so a fill level is a clip at one y.
 */
class ClusterRenderer(private val fonts: Fonts) : GaugeRenderer {

    private companion object {
        const val C_BG = 0xFF04060C.toInt()
        const val C_TEXT = 0xFFF2F5F8.toInt()
        const val C_SOFT = 0xFFA9B2BE.toInt()
        const val C_DIM = 0xFF4A5260.toInt()
        const val C_BLUE = 0xFF3A5BFF.toInt()
        const val C_RED = 0xFFFF2D3D.toInt()
        const val C_ORANGE = 0xFFFF7A1A.toInt()
        const val C_ECO = 0xFF35D0A0.toInt()

        const val SPEED_MAX = 200.0

        // Left gauge: outer edge bottom (BX, BOT) -> apex (AX, MIDY) -> top (BX, TOP); band WIDTH wide.
        const val TOP = 132f
        const val BOT = 372f
        const val BX = 262f
        const val AX = 172f
        const val WIDTH = 64f
        // Inner strip (coolant / throttle): offset from the outer edge, STRIP wide.
        const val STRIP_DX = WIDTH + 12f
        const val STRIP = 7f

        const val LATTICE = 34f
        const val HATCH = 7f

        const val PEAK_HOLD_S = 1.2
        const val PEAK_FALL_PER_S = 0.25 // of full scale
    }

    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun text(face: Typeface, size: Float, color: Int, align: Paint.Align) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size; this.color = color; textAlign = align }

    /** y of fraction [f] (0 bottom, 1 top) and x of the left gauge's outer edge there. */
    private fun yAt(f: Float) = BOT - (BOT - TOP) * f
    private fun outerX(f: Float) = BX - (BX - AX) * (1f - abs(1f - 2f * f))

    private fun mx(mirror: Boolean, v: Float) = if (mirror) Viewport.LOGICAL_W - v else v

    /** A chevron band between offsets [d0] and [d1] from the outer edge. */
    private fun bandPath(mirror: Boolean, d0: Float, d1: Float): Path {
        val mid = (TOP + BOT) / 2f
        return Path().apply {
            moveTo(mx(mirror, BX + d0), BOT); lineTo(mx(mirror, AX + d0), mid); lineTo(mx(mirror, BX + d0), TOP)
            lineTo(mx(mirror, BX + d1), TOP); lineTo(mx(mirror, AX + d1), mid); lineTo(mx(mirror, BX + d1), BOT); close()
        }
    }
    private fun edgePath(mirror: Boolean, dx: Float): Path {
        return Path().apply {
            moveTo(mx(mirror, BX + dx), BOT); lineTo(mx(mirror, AX + dx), (TOP + BOT) / 2f); lineTo(mx(mirror, BX + dx), TOP)
        }
    }

    private val leftBand = bandPath(false, 0f, WIDTH)
    private val rightBand = bandPath(true, 0f, WIDTH)
    private val leftStrip = bandPath(false, STRIP_DX, STRIP_DX + STRIP)
    private val rightStrip = bandPath(true, STRIP_DX, STRIP_DX + STRIP)
    private val leftOuter = edgePath(false, 0f)
    private val rightOuter = edgePath(true, 0f)
    private val leftInner = edgePath(false, WIDTH)
    private val rightInner = edgePath(true, WIDTH)

    // ---- backdrop ----
    private val bgPaint = Paint().apply { color = C_BG }
    // Fully opaque before the lattice's clip edges (nearest 210 px from the centre), so they never show.
    private val vignette = Paint().apply {
        shader = RadialGradient(400f, 250f, 200f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, C_BG),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
    }
    private val latticePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 70, 100, 255); strokeWidth = 1f }
    private val lattice: FloatArray = run {
        val pts = ArrayList<Float>()
        var k = -480f
        while (k < 800f) {
            pts += listOf(k, 0f, k + 480f, 480f) // "\" family
            pts += listOf(k + 480f, 0f, k, 480f) // "/" family
            k += LATTICE
        }
        pts.toFloatArray()
    }
    /** Fine one-direction hatching for the gauge bands (a machined-carbon texture). */
    private val hatch: FloatArray = run {
        val pts = ArrayList<Float>()
        var k = -480f
        while (k < 800f) { pts += listOf(k, 480f, k + 480f, 0f); k += HATCH }
        pts.toFloatArray()
    }
    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(28, 150, 170, 255); strokeWidth = 1f }
    private fun glow(x: Float) = Paint().apply {
        shader = RadialGradient(x, 360f, 150f, Color.argb(120, 255, 40, 60), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    private val glowL = glow(300f)
    private val glowR = glow(500f)

    // ---- gauges ----
    private val bandBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 28, 34, 70) }
    private val bandFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, BOT, 0f, TOP,
            intArrayOf(C_RED, Color.argb(230, 200, 40, 120), Color.argb(220, 60, 80, 255)), null, Shader.TileMode.CLAMP)
    }
    /** The fill's bright leading edge: a white-to-clear wash 22 px deep, translated to the level. */
    private val headMatrix = Matrix()
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, 22f, Color.argb(150, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    private val redZone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 255, 45, 61) }
    private val outerEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = C_TEXT; strokeJoin = Paint.Join.ROUND
        setShadowLayer(glowRadius(10f), 0f, 0f, Color.argb(160, 255, 255, 255))
    }
    private val innerEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.argb(160, 200, 210, 230)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f; color = C_SOFT }
    private val minorTick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f; color = C_SOFT }
    private val level = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f; color = C_TEXT; setShadowLayer(glowRadius(8f), 0f, 0f, C_TEXT)
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f; color = C_ORANGE; setShadowLayer(glowRadius(8f), 0f, 0f, C_ORANGE)
    }
    private val stripBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 60, 70, 100) }
    private val throttleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, BOT, 0f, TOP, C_ORANGE, C_RED, Shader.TileMode.CLAMP)
    }
    private val coolantFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, BOT, 0f, TOP, intArrayOf(C_BLUE, 0xFF00C8FF.toInt(), C_RED),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }

    // ---- bottom strip ----
    private val baseline = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f }
    private val baseLeft = LinearGradient(40f, 0f, 330f, 0f, Color.TRANSPARENT, C_TEXT, Shader.TileMode.CLAMP)
    private val baseRight = LinearGradient(470f, 0f, 760f, 0f, C_TEXT, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; color = C_ORANGE; setShadowLayer(glowRadius(8f), 0f, 0f, C_ORANGE) }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f; color = C_SOFT; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dropPath = Path()
    private val thermoRect = RectF()

    // ---- centre ----
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF10141E.toInt() }
    private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0xFF262D3C.toInt() }
    private val pillRect = RectF(330f, 64f, 470f, 94f)

    // ---- type ----
    private val scaleLeft = text(sans, 16f, C_SOFT, Paint.Align.RIGHT)
    private val scaleRight = text(sans, 16f, C_SOFT, Paint.Align.LEFT)
    private val unitLeft = text(sansMedium, 13f, C_SOFT, Paint.Align.LEFT)
    private val unitRight = text(sansMedium, 13f, C_SOFT, Paint.Align.RIGHT)
    private val stripLabel = text(sansMedium, 11f, C_SOFT, Paint.Align.CENTER)
    private val pillText = text(sans, 14f, C_SOFT, Paint.Align.CENTER)
    private val speedBig = text(sansLight, 104f, C_TEXT, Paint.Align.CENTER).apply {
        setShadowLayer(glowRadius(18f), 0f, 0f, Color.argb(110, 160, 180, 255))
    }
    private val speedUnit = text(sans, 17f, C_SOFT, Paint.Align.CENTER)
    private val brand = text(sansMedium, 15f, C_TEXT, Paint.Align.CENTER).apply { letterSpacing = 0.4f }
    private val prndText = text(sansMedium, 24f, C_DIM, Paint.Align.CENTER)
    private val gearSub = text(sansMedium, 17f, C_TEXT, Paint.Align.LEFT)
    private val modeText = text(sansMedium, 15f, C_RED, Paint.Align.CENTER).apply { letterSpacing = 0.08f }
    private val bottomLeft = text(sans, 17f, C_TEXT, Paint.Align.LEFT)
    private val bottomRight = text(sans, 17f, C_TEXT, Paint.Align.RIGHT)
    private val midScale = middleOffset(scaleLeft)
    private val midBottom = middleOffset(bottomLeft)
    private val midPill = middleOffset(pillText)
    private val midPrnd = middleOffset(prndText)

    private val speedLabels = Array(11) { (it * 20).toString() }
    private val rpmLabels = Array(8) { it.toString() }
    private val prnd = arrayOf("P", "R", "N", "D")

    private val clock = SimpleDateFormat("HH:mm", Locale.US)
    private val date = Date()
    private val tSpeed = TextCache()
    private val tGear = TextCache()
    private val tClock = TextCache()
    private val tKml = TextCache()
    private val tIat = TextCache()
    private val tOil = TextCache()
    private val tTrip = TextCache()

    // rpm peak hold (render thread only)
    private var peak = 0f
    private var peakAtS = 0.0
    private var lastS = Double.NaN

    override fun draw(c: Canvas, f: FrameModel) {
        c.drawRect(0f, 0f, Viewport.LOGICAL_W, Viewport.LOGICAL_H, bgPaint)
        val save = c.save()
        c.clipRect(150f, 40f, 650f, 460f)
        c.drawLines(lattice, latticePaint)
        c.restoreToCount(save)
        c.drawRect(0f, 0f, Viewport.LOGICAL_W, Viewport.LOGICAL_H, vignette)
        c.drawCircle(300f, 360f, 150f, glowL)
        c.drawCircle(500f, 360f, 150f, glowR)

        val speed = f.speed
        gauge(c, false, speed?.let { Fmt.clamp01(it, 0.0, SPEED_MAX).toFloat() }, null, null)
        val rpmFrac = f.rpm?.let { Fmt.clamp01(it, 0.0, TACH_MAX_RPM).toFloat() }
        gauge(c, true, rpmFrac, Fmt.clamp01(f.redline.toDouble(), 0.0, TACH_MAX_RPM).toFloat(), updatePeak(rpmFrac, f.tSec))
        strip(c, false, f.coolant?.let { Fmt.clamp01(it, 40.0, 120.0).toFloat() }, coolantFill, "CLT",
            Warning.OVERHEAT in f.warnings)
        strip(c, true, f.throttle?.let { Fmt.clamp01(it, 0.0, 100.0).toFloat() }, throttleFill, "THR", false)
        scale(c)
        drawCentre(c, f)
        drawBottom(c, f)
        drawStatusOverlay(c, f, fonts)
    }

    /**
     * Peak rpm fraction: jumps up with rpm, holds [PEAK_HOLD_S], then falls at [PEAK_FALL_PER_S].
     * Null (not drawn) while it sits on the current level.
     */
    private fun updatePeak(frac: Float?, tSec: Double): Float? {
        val dt = if (lastS.isNaN() || tSec < lastS) 0.0 else tSec - lastS
        lastS = tSec
        val v = frac ?: 0f
        if (v >= peak) {
            peak = v
            peakAtS = tSec
        } else if (tSec - peakAtS > PEAK_HOLD_S) {
            peak = maxOf(v, peak - (PEAK_FALL_PER_S * dt).toFloat())
        }
        return if (frac == null || peak - v < 0.02f) null else peak
    }

    /** One chevron gauge: hatched band, fill up to [frac] with a bright head, red zone, edges, level and peak. */
    private fun gauge(c: Canvas, mirror: Boolean, frac: Float?, redFrom: Float?, peakFrac: Float?) {
        val band = if (mirror) rightBand else leftBand
        c.drawPath(band, bandBg)
        var s = c.save()
        c.clipPath(band)
        c.drawLines(hatch, hatchPaint)
        c.restoreToCount(s)
        if (redFrom != null) {
            s = c.save()
            c.clipRect(0f, TOP, Viewport.LOGICAL_W, yAt(redFrom))
            c.drawPath(band, redZone)
            c.restoreToCount(s)
        }
        if (frac != null && frac > 0f) {
            val y = yAt(frac)
            s = c.save()
            c.clipRect(0f, y, Viewport.LOGICAL_W, BOT)
            c.drawPath(band, bandFill)
            c.clipRect(0f, y, Viewport.LOGICAL_W, y + 22f)
            headMatrix.setTranslate(0f, y)
            headPaint.shader.setLocalMatrix(headMatrix)
            c.drawPath(band, headPaint)
            c.restoreToCount(s)
            levelLine(c, mirror, frac, level)
        }
        if (peakFrac != null) levelLine(c, mirror, peakFrac, peakPaint)
        c.drawPath(if (mirror) rightOuter else leftOuter, outerEdge)
        c.drawPath(if (mirror) rightInner else leftInner, innerEdge)
    }

    private fun levelLine(c: Canvas, mirror: Boolean, frac: Float, p: Paint) {
        val y = yAt(frac)
        val x1 = outerX(frac)
        c.drawLine(mx(mirror, x1), y, mx(mirror, x1 + WIDTH), y, p)
    }

    /** A thin strip inside a gauge, filled from the bottom; labelled under its foot (red on [warn]). */
    private fun strip(c: Canvas, mirror: Boolean, frac: Float?, fill: Paint, label: String, warn: Boolean) {
        val path = if (mirror) rightStrip else leftStrip
        c.drawPath(path, stripBg)
        if (frac != null && frac > 0f) {
            val s = c.save()
            c.clipRect(0f, yAt(frac), Viewport.LOGICAL_W, BOT)
            c.drawPath(path, fill)
            c.restoreToCount(s)
        }
        stripLabel.color = if (warn) C_RED else C_SOFT
        c.drawText(label, mx(mirror, BX + STRIP_DX + STRIP / 2f), BOT + 18f, stripLabel)
    }

    private fun scale(c: Canvas) {
        // Minor ticks: every 10 km/h and every 500 rpm, between the labelled ones.
        for (i in 1 until 20 step 2) {
            val fr = i / 20f
            val x = outerX(fr)
            c.drawLine(x - 8f, yAt(fr), x - 4f, yAt(fr), minorTick)
        }
        for (i in 1 until 14 step 2) {
            val fr = i / 14f
            val x = Viewport.LOGICAL_W - outerX(fr)
            c.drawLine(x + 4f, yAt(fr), x + 8f, yAt(fr), minorTick)
        }
        for (i in speedLabels.indices) {
            val fr = i / (speedLabels.size - 1f)
            val x = outerX(fr)
            val y = yAt(fr)
            c.drawLine(x - 12f, y, x - 4f, y, tick)
            c.drawText(speedLabels[i], x - 18f, y + midScale, scaleLeft)
        }
        c.drawText("km/h", BX + 6f, TOP - 14f, unitLeft)
        for (i in rpmLabels.indices) {
            val fr = i / (rpmLabels.size - 1f)
            val x = Viewport.LOGICAL_W - outerX(fr)
            val y = yAt(fr)
            c.drawLine(x + 4f, y, x + 12f, y, tick)
            c.drawText(rpmLabels[i], x + 18f, y + midScale, scaleRight)
        }
        c.drawText("rpm ×1000", Viewport.LOGICAL_W - BX - 6f, TOP - 14f, unitRight)
    }

    private fun modeColor(m: DriveMode) = when (m) {
        DriveMode.SPORT -> C_RED
        DriveMode.COMFORT -> C_TEXT
        DriveMode.ECO -> C_ECO
    }

    private fun drawCentre(c: Canvas, f: FrameModel) {
        c.drawRoundRect(pillRect, 15f, 15f, pill)
        c.drawRoundRect(pillRect, 15f, 15f, pillEdge)
        val trip = f.tripKml
        c.drawText(tTrip.get(textKey(trip, 10.0)) { if (trip == null) "Trip avg --" else "Trip avg " + Fmt.kml(trip) + " km/l" }, 400f, pillRect.centerY() + midPill, pillText)

        c.drawText("VERNA", 400f, 128f, brand)
        // Three-colour underline (blue, white, red); the paint is restored to the bottom-strip orange.
        accent.color = C_BLUE
        c.drawLine(372f, 138f, 388f, 138f, accent)
        accent.color = C_TEXT
        c.drawLine(392f, 138f, 408f, 138f, accent)
        accent.color = C_RED
        c.drawLine(412f, 138f, 428f, 138f, accent)
        accent.color = C_ORANGE
        val speed = f.speed
        c.drawText(tSpeed.get(textKey(speed)) { Fmt.int(speed) }, 400f, 262f, speedBig)
        c.drawText("km/h", 400f, 292f, speedUnit)

        // P R N D: only D can be inferred (with its gear); nothing is lit when the gear is unknown.
        val gear = f.gear
        val y = 352f + midPrnd
        for (i in prnd.indices) {
            prndText.color = if (i == 3 && gear != null) C_TEXT else C_DIM
            c.drawText(prnd[i], 360f + i * 24f, y, prndText)
        }
        if (gear != null) c.drawText(tGear.get(gear.toLong()) { gear.toString() }, 441f, y - 6f, gearSub)
    }

    private fun drawBottom(c: Canvas, f: FrameModel) {
        val y = 400f
        baseline.shader = baseLeft
        c.drawLine(40f, y, 330f, y, baseline)
        baseline.shader = baseRight
        c.drawLine(470f, y, 760f, y, baseline)
        c.drawLine(300f, y, 340f, y, accent)
        c.drawLine(460f, y, 500f, y, accent)

        val cy = 440f
        val by = cy + midBottom
        drop(c, 52f, cy)
        val kml = f.instantKml
        c.drawText(tKml.get(textKey(kml, 10.0)) { Fmt.kml(kml) + " km/l" }, 66f, by, bottomLeft)
        c.drawText(tClock.get(f.wallMs / 60_000L) { date.time = f.wallMs; clock.format(date) }, 210f, by, bottomLeft)
        modeText.color = modeColor(f.driveMode)
        c.drawText(f.driveMode.name, 400f, by, modeText)
        val iat = f.iat
        val iatStr = tIat.get(textKey(iat)) { "Air " + Fmt.temp(iat, false) }
        c.drawText(iatStr, 612f, by, bottomRight)
        thermometer(c, 612f - bottomRight.measureText(iatStr) - 12f, cy)
        val oil = f.oil
        val oilStr = tOil.get(textKey(oil, unsupported = f.oilUnsupported)) { "Oil " + Fmt.temp(oil, f.oilUnsupported) }
        c.drawText(oilStr, 752f, by, bottomRight)
        thermometer(c, 752f - bottomRight.measureText(oilStr) - 12f, cy)
    }

    /** Fuel drop icon centred on (x, y). */
    private fun drop(c: Canvas, x: Float, y: Float) {
        dropPath.rewind()
        dropPath.moveTo(x, y - 8f)
        dropPath.cubicTo(x + 6f, y - 1f, x + 6f, y + 7f, x, y + 7f)
        dropPath.cubicTo(x - 6f, y + 7f, x - 6f, y - 1f, x, y - 8f)
        c.drawPath(dropPath, icon)
    }

    /** Thermometer icon centred on (x, y). */
    private fun thermometer(c: Canvas, x: Float, y: Float) {
        thermoRect.set(x - 2.5f, y - 9f, x + 2.5f, y + 3f)
        c.drawRoundRect(thermoRect, 2.5f, 2.5f, icon)
        c.drawCircle(x, y + 5.5f, 3.5f, icon)
    }
}
