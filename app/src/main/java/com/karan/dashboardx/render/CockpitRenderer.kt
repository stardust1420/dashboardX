package com.karan.dashboardx.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.karan.dashboardx.alerts.Warning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Style G, "Cockpit": a modern EV-style virtual cockpit in a rounded bezel. Two ringed gauges
 * (rpm left, speed right), each with an outer side arc (coolant left, throttle right). Between
 * them a perspective road scrolls with speed under our car seen from behind; its tail lights
 * brighten off throttle. Above the road: the drive-mode selector, a dot-matrix km/l history and a
 * gear ring. Top bar: oil, air, km/l; bottom bar: date, time, trip average. Sound toggle: top left.
 * On a wide screen the bezel stretches and the gauges slide out with it.
 */
class CockpitRenderer(private val fonts: Fonts) : GaugeRenderer {

    private companion object {
        const val C_BG_TOP = 0xFF111218.toInt()
        const val C_BG_BOTTOM = 0xFF040405.toInt()
        const val C_BEZEL = 0xFF0C0D12.toInt()
        const val C_EDGE = 0xFF22242C.toInt()
        const val C_TRACK = 0xFF262832.toInt()
        const val C_TEXT = 0xFFF1F2F6.toInt()
        const val C_SOFT = 0xFF9A9EAD.toInt()
        const val C_DIM = 0xFF5A5E6B.toInt()
        const val C_ACCENT = 0xFFFF2D6F.toInt() // magenta
        const val C_RED = 0xFFFF3030.toInt()
        const val C_LANE = 0xFF6F93FF.toInt()
        const val C_CAR = 0xFFF26A1B.toInt()
        const val C_CAR_DARK = 0xFF8A3208.toInt()

        const val LCX = 160f
        const val RCX = 640f
        const val GCY = 262f
        const val INNER_R = 74f
        const val OUTER_R = 120f
        const val SIDE_SWEEP = 120f // outer side arcs span ±60° around the horizontal

        const val HZ = 150f // road horizon
        const val ROAD_BOTTOM = 412f
        const val ROAD_TOP_HALF = 14f
        const val ROAD_BOTTOM_HALF = 118f
        const val DASHES = 7
        const val LANE_CYCLES_PER_KMH_S = 0.02

        const val SPEED_MAX = 200.0
        const val SPARK_COLS = 12
        const val SPARK_ROWS = 6
        const val SPARK_X = 278f
        const val SPARK_Y = 150f
        const val SPARK_STEP = 7f
        const val SPARK_EVERY_S = 2.0
        const val KML_FULL = 25.0

        const val GEAR_X = 476f
        const val GEAR_Y = 174f

        /** Floats in [ticks] per gauge (13 lines of 4), left gauge first. */
        const val TICKS_PER_SIDE = 13 * 4
    }

    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun text(face: Typeface, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size; this.color = color; textAlign = align }

    private fun stroke(width: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = width; this.color = color
    }

    // ---- backdrop ----
    private val bgPaint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, 480f, C_BG_TOP, C_BG_BOTTOM, Shader.TileMode.CLAMP)
    }
    private val bezelRect = RectF() // 14..786 plus ext, set per frame
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_BEZEL }
    private val bezelEdge = stroke(1.5f, C_EDGE)
    private val glowL = Paint().apply {
        shader = RadialGradient(LCX, GCY, 170f, 0x30FF2D6F, 0x00FF2D6F, Shader.TileMode.CLAMP)
    }
    private val glowR = Paint().apply {
        shader = RadialGradient(RCX, GCY, 170f, 0x30FF2D6F, 0x00FF2D6F, Shader.TileMode.CLAMP)
    }

    // ---- gauges ----
    private val lInner = RectF(LCX - INNER_R, GCY - INNER_R, LCX + INNER_R, GCY + INNER_R)
    private val rInner = RectF(RCX - INNER_R, GCY - INNER_R, RCX + INNER_R, GCY + INNER_R)
    private val lOuter = RectF(LCX - OUTER_R, GCY - OUTER_R, LCX + OUTER_R, GCY + OUTER_R)
    private val rOuter = RectF(RCX - OUTER_R, GCY - OUTER_R, RCX + OUTER_R, GCY + OUTER_R)
    private val ringTrack = stroke(3f, C_TRACK)
    private val ringFill = stroke(4f, C_ACCENT).apply {
        strokeCap = Paint.Cap.ROUND; setShadowLayer(glowRadius(12f), 0f, 0f, C_ACCENT)
    }
    private val sideTrack = stroke(7f, C_TRACK)
    private val sideFill = stroke(7f, C_ACCENT).apply { setShadowLayer(glowRadius(14f), 0f, 0f, C_ACCENT) }
    private val tickPaint = stroke(1.5f, C_DIM)
    private val ticks = FloatArray(2 * 13 * 4).also { t ->
        var n = 0
        for ((cx, base) in listOf(LCX to 180f - SIDE_SWEEP / 2f, RCX to -SIDE_SWEEP / 2f)) {
            for (i in 0..12) {
                val a = Math.toRadians((base + i * SIDE_SWEEP / 12f).toDouble())
                val r0 = OUTER_R + 9f
                val r1 = OUTER_R + if (i % 3 == 0) 17f else 13f
                t[n++] = cx + (cos(a) * r0).toFloat(); t[n++] = GCY + (sin(a) * r0).toFloat()
                t[n++] = cx + (cos(a) * r1).toFloat(); t[n++] = GCY + (sin(a) * r1).toFloat()
            }
        }
    }

    private val gaugeLabel = text(sansMedium, 13f, C_ACCENT)
    private val rpmValue = text(sansLight, 38f, C_TEXT)
    private val speedValue = text(sansLight, 52f, C_TEXT)
    private val unitText = text(sans, 14f, C_SOFT)
    private val sideLabel = text(sans, 13f, C_DIM)
    private val sideValueL = text(sansMedium, 16f, C_TEXT, Paint.Align.LEFT)
    private val sideNameL = text(sans, 13f, C_SOFT, Paint.Align.LEFT)
    private val sideValueR = text(sansMedium, 16f, C_TEXT, Paint.Align.RIGHT)
    private val sideNameR = text(sans, 13f, C_SOFT, Paint.Align.RIGHT)
    private val shiftText = text(sansMedium, 13f, C_RED)

    // ---- road and car ----
    private val roadPath = Path().apply {
        moveTo(400f - ROAD_TOP_HALF, HZ)
        lineTo(400f + ROAD_TOP_HALF, HZ)
        lineTo(400f + ROAD_BOTTOM_HALF, ROAD_BOTTOM)
        lineTo(400f - ROAD_BOTTOM_HALF, ROAD_BOTTOM)
        close()
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, HZ, 0f, ROAD_BOTTOM, 0x00191B24, 0xFF1C1F2A.toInt(), Shader.TileMode.CLAMP)
    }
    private val roadEdge = stroke(1.5f, C_SOFT).apply {
        shader = LinearGradient(0f, HZ, 0f, ROAD_BOTTOM, 0x00FFFFFF, 0xB0FFFFFF.toInt(), Shader.TileMode.CLAMP)
    }
    private val dashPaint = stroke(3f, C_TEXT)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandPath = Path()

    private val carShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(400f, 392f, 70f, 0xC0000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
    }
    private val cabinPath = Path().apply {
        moveTo(372f, 318f); lineTo(428f, 318f); lineTo(442f, 342f); lineTo(358f, 342f); close()
    }
    private val cabinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 318f, 0f, 342f, 0xFF3A3F4C.toInt(), 0xFF15171D.toInt(), Shader.TileMode.CLAMP)
    }
    private val bodyRect = RectF(350f, 338f, 450f, 382f)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 338f, 0f, 382f, C_CAR, C_CAR_DARK, Shader.TileMode.CLAMP)
    }
    private val tailRect = RectF(356f, 348f, 444f, 354f)
    private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plateRect = RectF(386f, 362f, 414f, 372f)
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9D9D9.toInt() }
    private val tyrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A0A0C.toInt() }
    private val tyreL = RectF(354f, 378f, 372f, 394f)
    private val tyreR = RectF(428f, 378f, 446f, 394f)

    private var laneOff = 0.0
    private var lastT = Double.NaN

    // ---- centre widgets ----
    private val modeSel = ModeSelector(256f, 96f, 544f, 138f, text(sansMedium, 15f, C_TEXT),
        intArrayOf(C_LANE, C_ACCENT, C_RED), C_SOFT, 0xFF16171E.toInt(), 21f)
    override val modeHit: RectF get() = modeSel.hit
    private val soundBtn = SoundButton(44f, 52f, 88f, 96f, C_ACCENT, C_SOFT, 0xFF16171E.toInt(), 22f)
    override val soundHit: RectF get() = soundBtn.hit
    private val spark = FloatArray(SPARK_COLS) { -1f } // km/l share 0..1 per column, -1 = no sample
    private var sparkHead = 0
    private var nextSparkT = 0.0
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkLabel = text(sans, 12f, C_DIM, Paint.Align.LEFT)
    private val sparkValue = text(sansMedium, 14f, C_TEXT, Paint.Align.LEFT)
    private val gearRing = stroke(2f, C_TRACK)
    private val gearNotch = stroke(3f, C_ACCENT).apply { strokeCap = Paint.Cap.ROUND }
    private val gearRect = RectF(GEAR_X - 20f, GEAR_Y - 20f, GEAR_X + 20f, GEAR_Y + 20f)
    private val gearValue = text(sansMedium, 20f, C_TEXT)
    private val gearLabel = text(sans, 12f, C_SOFT, Paint.Align.LEFT)

    // ---- bars ----
    private val barLabel = text(sans, 12f, C_DIM)
    private val barValue = text(sansMedium, 15f, C_TEXT)
    private val barRule = Paint().apply {
        shader = LinearGradient(120f, 0f, 680f, 0f,
            intArrayOf(0x00FFFFFF, 0x30FFFFFF, C_ACCENT, 0x30FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    }
    private val bottomText = text(sans, 14f, C_SOFT)
    private val timeText = text(sansMedium, 16f, C_TEXT)

    private val midGauge = middleOffset(gaugeLabel)
    private val midRpm = middleOffset(rpmValue)
    private val midSpeed = middleOffset(speedValue)
    private val midGear = middleOffset(gearValue)

    private val date = Date()
    private val clock = SimpleDateFormat("HH:mm", Locale.US)
    private val day = SimpleDateFormat("d MMM yyyy", Locale.US)

    private val tRpm = TextCache()
    private val tSpeed = TextCache()
    private val tClt = TextCache()
    private val tThr = TextCache()
    private val tOil = TextCache()
    private val tAir = TextCache()
    private val tKml = TextCache()
    private val tTrip = TextCache()
    private val tGear = TextCache()
    private val tClock = TextCache()
    private val tDay = TextCache()

    override fun draw(c: Canvas, f: FrameModel, ext: Float) {
        advance(f)
        c.drawRect(-ext, 0f, 800f + ext, 480f, bgPaint)
        bezelRect.set(14f - ext, 34f, 786f + ext, 462f)
        c.drawRoundRect(bezelRect, 44f, 44f, bezelPaint)
        c.drawRoundRect(bezelRect, 44f, 44f, bezelEdge)
        c.shifted(-ext) { c.drawCircle(LCX, GCY, 170f, glowL) }
        c.shifted(ext) { c.drawCircle(RCX, GCY, 170f, glowR) }
        drawRoad(c, f)
        drawCar(c, f)
        c.shifted(-ext) { drawRpmGauge(c, f) }
        c.shifted(ext) { drawSpeedGauge(c, f) }
        drawCentre(c, f)
        drawBars(c, f)
        soundBtn.draw(c, f.soundOn, -ext)
        drawStatusOverlay(c, f, fonts, ext)
    }

    private fun advance(f: FrameModel) {
        val dt = f.tSec - lastT
        lastT = f.tSec
        if (dt.isNaN() || dt < 0.0 || dt > 1.0) {
            laneOff = 0.0
            nextSparkT = f.tSec
        } else {
            laneOff = (laneOff + (f.speed ?: 0.0) * LANE_CYCLES_PER_KMH_S * dt) % 1.0
        }
        if (f.tSec >= nextSparkT) {
            nextSparkT = f.tSec + SPARK_EVERY_S
            val k = f.instantKml
            spark[sparkHead] = if (k == null) -1f else Fmt.clamp01(k, 0.0, KML_FULL).toFloat()
            sparkHead = (sparkHead + 1) % SPARK_COLS
        }
    }

    // ---- road ----

    private fun roadY(z: Double): Float = HZ + (z.pow(2.2) * (ROAD_BOTTOM - HZ)).toFloat()
    private fun roadHalf(y: Float): Float =
        ROAD_TOP_HALF + (y - HZ) / (ROAD_BOTTOM - HZ) * (ROAD_BOTTOM_HALF - ROAD_TOP_HALF)

    private fun drawRoad(c: Canvas, f: FrameModel) {
        c.drawPath(roadPath, roadPaint)
        c.drawLine(400f - ROAD_TOP_HALF, HZ, 400f - ROAD_BOTTOM_HALF, ROAD_BOTTOM, roadEdge)
        c.drawLine(400f + ROAD_TOP_HALF, HZ, 400f + ROAD_BOTTOM_HALF, ROAD_BOTTOM, roadEdge)

        // Lane dashes just inside each edge, scrolling toward us.
        for (k in 0 until DASHES) {
            val z0 = (k + laneOff) / DASHES
            val y0 = roadY(z0)
            val y1 = roadY((z0 + 0.06).coerceAtMost(1.0))
            dashPaint.alpha = (40 + 200 * z0).toInt().coerceAtMost(255)
            dashPaint.strokeWidth = 1f + 3f * z0.toFloat()
            val in0 = roadHalf(y0) * 0.86f
            val in1 = roadHalf(y1) * 0.86f
            c.drawLine(400f - in0, y0, 400f - in1, y1, dashPaint)
            c.drawLine(400f + in0, y0, 400f + in1, y1, dashPaint)
        }

        // Light bands ahead of the car (the cruise-control "gap" markers); more with throttle.
        val bands = 1 + Math.round(Fmt.clamp01(f.throttle ?: 0.0, 0.0, 100.0) * 3).toInt()
        for (i in 0 until bands) {
            val z = 0.73 - i * 0.07 // nearest band just ahead of the car's roof (z ≈ 0.82)
            val y = roadY(z)
            val y2 = roadY(z + 0.035)
            val h0 = roadHalf(y) * 0.8f
            val h1 = roadHalf(y2) * 0.8f
            bandPath.rewind()
            bandPath.moveTo(400f - h0, y); bandPath.lineTo(400f + h0, y)
            bandPath.lineTo(400f + h1, y2); bandPath.lineTo(400f - h1, y2); bandPath.close()
            bandPaint.color = C_LANE
            bandPaint.alpha = 190 - i * 40
            c.drawPath(bandPath, bandPaint)
        }
    }

    private fun drawCar(c: Canvas, f: FrameModel) {
        c.drawOval(320f, 372f, 480f, 412f, carShadow)
        c.drawRect(tyreL, tyrePaint)
        c.drawRect(tyreR, tyrePaint)
        c.drawPath(cabinPath, cabinPaint)
        c.drawRoundRect(bodyRect, 10f, 10f, bodyPaint)
        // Off throttle the tail lights glow like brake lights.
        val braking = (f.throttle ?: 0.0) < 3.0 && (f.speed ?: 0.0) > 3.0
        tailPaint.color = if (braking) C_RED else 0xFFB0141A.toInt()
        if (braking) tailPaint.setShadowLayer(glowRadius(16f), 0f, 0f, C_RED) else tailPaint.clearShadowLayer()
        c.drawRoundRect(tailRect, 3f, 3f, tailPaint)
        c.drawRect(plateRect, platePaint)
    }

    // ---- gauges ----

    private fun drawRpmGauge(c: Canvas, f: FrameModel) {
        val rpm = f.rpm
        val hot = rpm != null && rpm >= f.redline
        ring(c, lInner, rpm?.let { Fmt.clamp01(it, 0.0, TACH_MAX_RPM) }, if (hot) C_RED else C_ACCENT)
        c.drawText("RPM", LCX, GCY - 34f + midGauge, gaugeLabel)
        c.drawText(tRpm.get(textKey(rpm, 0.1)) { Fmt.int(rpm?.let { Math.round(it / 10) * 10.0 }) },
            LCX, GCY + 2f + midRpm, rpmValue)
        if (Warning.REDLINE in f.warnings && floor(f.tSec * 6.0).toLong() % 2L == 1L) {
            c.drawText("SHIFT UP", LCX, GCY + 38f, shiftText)
        } else {
            c.drawText("rpm", LCX, GCY + 38f, unitText)
        }

        c.drawLines(ticks, 0, TICKS_PER_SIDE, tickPaint)
        // Coolant on the left side arc, filling upward from the bottom-left.
        val clt = f.coolant
        val cltFrac = clt?.let { Fmt.clamp01(it, 40.0, 120.0) } ?: 0.0
        c.drawArc(lOuter, 180f - SIDE_SWEEP / 2f, SIDE_SWEEP, false, sideTrack)
        sideFill.color = if (Warning.OVERHEAT in f.warnings) C_RED else C_ACCENT
        if (cltFrac > 0) c.drawArc(lOuter, 180f - SIDE_SWEEP / 2f + SIDE_SWEEP * (1f - cltFrac.toFloat()),
            SIDE_SWEEP * cltFrac.toFloat(), false, sideFill)
        c.drawText("H", LCX - 58f, GCY - OUTER_R - 10f, sideLabel)
        c.drawText("C", LCX - 58f, GCY + OUTER_R + 20f, sideLabel)
        c.drawText("COOLANT", 70f, 444f, sideNameL)
        c.drawText(tClt.get(textKey(clt)) { Fmt.temp(clt, false) }, 140f, 444f, sideValueL)
    }

    private fun drawSpeedGauge(c: Canvas, f: FrameModel) {
        val speed = f.speed
        ring(c, rInner, speed?.let { Fmt.clamp01(it, 0.0, SPEED_MAX) }, C_ACCENT)
        c.drawText("SPEED", RCX, GCY - 38f + midGauge, gaugeLabel)
        c.drawText(tSpeed.get(textKey(speed)) { Fmt.int(speed) }, RCX, GCY + 4f + midSpeed, speedValue)
        c.drawText("km/h", RCX, GCY + 42f, unitText)

        c.drawLines(ticks, TICKS_PER_SIDE, TICKS_PER_SIDE, tickPaint)
        // Throttle on the right side arc, filling upward from the bottom-right.
        val thr = f.throttle
        val thrFrac = thr?.let { Fmt.clamp01(it, 0.0, 100.0) } ?: 0.0
        c.drawArc(rOuter, -SIDE_SWEEP / 2f, SIDE_SWEEP, false, sideTrack)
        sideFill.color = C_ACCENT
        if (thrFrac > 0) c.drawArc(rOuter, SIDE_SWEEP / 2f, -SIDE_SWEEP * thrFrac.toFloat(), false, sideFill)
        c.drawText("100", RCX + 58f, GCY - OUTER_R - 10f, sideLabel)
        c.drawText("0", RCX + 58f, GCY + OUTER_R + 20f, sideLabel)
        c.drawText("THROTTLE", 730f, 444f, sideNameR)
        c.drawText(tThr.get(textKey(thr)) { if (thr == null) Fmt.int(null) else Fmt.int(thr) + "%" },
            660f, 444f, sideValueR)
    }

    /** Inner ring: 270° track from bottom-left, filled to [frac]. */
    private fun ring(c: Canvas, r: RectF, frac: Double?, color: Int) {
        c.drawArc(r, 135f, 270f, false, ringTrack)
        if (frac != null && frac > 0.0) {
            ringFill.color = color
            ringFill.setShadowLayer(glowRadius(12f), 0f, 0f, color)
            c.drawArc(r, 135f, 270f * frac.toFloat(), false, ringFill)
        }
    }

    // ---- centre widgets ----

    private fun drawCentre(c: Canvas, f: FrameModel) {
        modeSel.draw(c, f.driveMode)

        for (col in 0 until SPARK_COLS) {
            val v = spark[(sparkHead + col) % SPARK_COLS] // oldest first
            val lit = if (v < 0f) 0 else Math.round(v * SPARK_ROWS).coerceAtLeast(1)
            for (row in 0 until SPARK_ROWS) {
                val on = row < lit
                dotPaint.color = if (on) C_TEXT else C_TRACK
                dotPaint.alpha = if (on) 140 + 115 * col / (SPARK_COLS - 1) else 255
                c.drawCircle(SPARK_X + col * SPARK_STEP, SPARK_Y + (SPARK_ROWS - 1 - row) * SPARK_STEP, 1.6f, dotPaint)
            }
        }
        c.drawText("AVG", SPARK_X - 2f, SPARK_Y + SPARK_ROWS * SPARK_STEP + 14f, sparkLabel)
        val trip = f.tripKml
        c.drawText(tTrip.get(textKey(trip, 10.0)) { Fmt.kml(trip) + " km/l" },
            SPARK_X + 26f, SPARK_Y + SPARK_ROWS * SPARK_STEP + 15f, sparkValue)

        c.drawCircle(GEAR_X, GEAR_Y, 20f, gearRing)
        c.drawArc(gearRect, -120f, 60f, false, gearNotch)
        val gear = f.gear
        c.drawText(tGear.get(textKey(gear?.toDouble())) { Fmt.gear(gear) }, GEAR_X, GEAR_Y + midGear, gearValue)
        c.drawText("GEAR", GEAR_X + 28f, GEAR_Y - 2f, gearLabel)
        c.drawText("AUTO", GEAR_X + 28f, GEAR_Y + 13f, sparkLabel)
    }

    // ---- top and bottom bars ----

    private fun drawBars(c: Canvas, f: FrameModel) {
        val oil = f.oil
        barItem(c, 310f, "OIL", tOil.get(textKey(oil, unsupported = f.oilUnsupported)) { Fmt.temp(oil, f.oilUnsupported) })
        val iat = f.iat
        barItem(c, 400f, "AIR", tAir.get(textKey(iat)) { Fmt.temp(iat, false) })
        val kml = f.instantKml
        barItem(c, 490f, "KM/L", tKml.get(textKey(kml, 10.0)) { Fmt.kml(kml) })
        c.drawRect(120f, 88f, 680f, 89f, barRule)

        c.drawRect(120f, 424f, 680f, 425f, barRule)
        date.time = f.wallMs
        c.drawText(tDay.get(f.wallMs / 60_000L) { day.format(date) }, 300f, 448f, bottomText)
        c.drawText(tClock.get(f.wallMs / 60_000L) { clock.format(date) }, 400f, 449f, timeText)
        c.drawText("DashboardX", 500f, 448f, bottomText)
    }

    private fun barItem(c: Canvas, x: Float, label: String, value: String) {
        c.drawText(label, x, 58f, barLabel)
        c.drawText(value, x, 78f, barValue)
    }
}
