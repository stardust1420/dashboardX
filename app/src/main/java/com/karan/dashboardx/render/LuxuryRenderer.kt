package com.karan.dashboardx.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.karan.dashboardx.alerts.Warning
import com.karan.dashboardx.data.DriveMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Style D, "Luxury": a premium-infotainment drive-mode screen, staged like a showroom.
 *
 * Left: the picked drive mode with gear and speed, a mode track (Comfort / Sport / Sport+, tap to
 * change; it is this style's [modeHit]), a menu
 * of live readings whose rows carry a level bar, one row highlighted, and a strip of secondary
 * readings. Right: the car under a spotlight on a glossy floor with its reflection; the floor glow
 * takes the mode's colour and brightens with rpm. The car is [Fonts.carPhoto] when present, else a
 * drawn side profile (without reflection).
 */
class LuxuryRenderer(private val fonts: Fonts) : GaugeRenderer {

    private companion object {
        const val C_TEXT = 0xFFE8ECF0.toInt()
        const val C_SOFT = 0xFFAEB5BE.toInt()
        const val C_DIM = 0xFF6F7781.toInt()
        const val C_RULE = 0xFF2A2F36.toInt()
        const val C_TRACK = 0xFF262B32.toInt()
        const val C_RED = 0xFFFF3B2F.toInt()
        const val C_COMFORT = 0xFFDDE6F2.toInt()
        const val C_COMFORT_GLOW = 0xFF6EA8FF.toInt()
        const val C_SPORT = 0xFFFF8A1F.toInt()

        const val LEFT = 44f
        const val ROW_RIGHT = 380f
        const val ROW_H = 38f
        val ROW_Y = floatArrayOf(272f, 316f, 360f)

        const val TRACK_Y = 226f
        const val TRACK_SEG = 104f
        const val TRACK_GAP = 6f

        const val STATS_Y = 432f
        const val STAT_W = 84f

        const val CAR_CX = 590f
        const val FLOOR_Y = 392f
        const val CAR_MAX_W = 360f
        const val CAR_MAX_H = 190f
        const val REFLECT = 0.42f // reflection height as a share of the car's
        const val GLOW_R = 230f
        const val GLOW_SQUASH = 0.16f
        const val VANISH_Y = 300f

        const val ROW_RPM = 0
        const val ROW_COOLANT = 1
        const val ROW_ECONOMY = 2
        const val ECONOMY_FULL = 25.0 // km/l at a full level bar
    }

    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun text(face: Typeface, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size; this.color = color; textAlign = align }

    // ---- backdrop and stage ----
    private val bgPaint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, 480f, 0xFF171A1F.toInt(), 0xFF060709.toInt(), Shader.TileMode.CLAMP)
    }
    private val stagePaint = Paint().apply {
        shader = RadialGradient(CAR_CX, 290f, 300f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }
    /** Spotlight pool on the floor: a soft, edgeless ellipse (a circle squashed at draw time). */
    private val poolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(CAR_CX, FLOOR_Y, 260f, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }
    /** Floor seams converging on the car, fading out sideways so they have no ends. */
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
        shader = RadialGradient(CAR_CX, FLOOR_Y, 240f, 0x1CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }
    private val seams: FloatArray = run {
        val pts = ArrayList<Float>()
        for (i in -4..4) {
            val xb = CAR_CX + i * 70f
            val t = (FLOOR_Y - VANISH_Y) / (480f - VANISH_Y)
            pts += listOf(CAR_CX + (xb - CAR_CX) * t, FLOOR_Y, xb, 480f)
        }
        pts.toFloatArray()
    }
    private val rulePaint = Paint().apply {
        strokeWidth = 1f
        shader = LinearGradient(LEFT, 0f, 700f, 0f, C_RULE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    // ---- type ----
    private val headerText = text(sansMedium, 17f, C_TEXT)
    private val clockText = text(sans, 17f, C_SOFT)
    private val modeText = text(sansLight, 50f, C_RED).apply { letterSpacing = 0.02f }
    private val subText = text(sans, 21f, C_RED)
    private val trackText = text(sansMedium, 18f, C_SOFT)
    private val rowLabel = text(sansMedium, 18f, C_TEXT)
    private val rowValue = text(sans, 18f, C_SOFT, Paint.Align.RIGHT)
    private val statLabel = text(sans, 12f, C_DIM)
    private val statValue = text(sansMedium, 16f, C_TEXT)

    private val midHeader = middleOffset(headerText)
    private val midRow = middleOffset(rowLabel)

    // ---- menu ----
    private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = C_RED; strokeCap = Paint.Cap.ROUND
    }
    private val chevronPath = Path().apply { moveTo(28f, 296f); lineTo(18f, 316f); lineTo(28f, 336f) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    override val modeHit = RectF(LEFT - 14f, TRACK_Y - 40f, LEFT + 3 * TRACK_SEG + 2 * TRACK_GAP + 14f, TRACK_Y + 20f)
    private val radio = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = C_SOFT }
    private val radioDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_TEXT }
    private val rowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(LEFT, 0f, ROW_RIGHT, 0f,
            Color.argb(235, 179, 34, 26), Color.argb(110, 90, 15, 11), Shader.TileMode.CLAMP)
    }
    private val rowEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = 0xFFFF4A3D.toInt() }
    private val rowSep = Paint().apply { strokeWidth = 1f; color = C_RULE }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f; strokeCap = Paint.Cap.ROUND }
    private val rowRect = RectF()
    private val statSep = Paint().apply { strokeWidth = 1f; color = C_RULE }

    private val warnFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = C_RED; strokeJoin = Paint.Join.ROUND }
    private val warnPath = Path()

    // ---- floor glow, one shader per mode; brightness via paint alpha ----
    private fun glowPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(CAR_CX, FLOOR_Y, GLOW_R,
            intArrayOf(color, Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }
    private val glowSport = glowPaint(C_SPORT)
    private val glowComfort = glowPaint(C_COMFORT_GLOW)
    private val glowSportPlus = glowPaint(C_RED)
    private fun lineShader(color: Int) = LinearGradient(CAR_CX - 220f, 0f, CAR_CX + 220f, 0f,
        intArrayOf(Color.TRANSPARENT, color, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    private val floorLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f }
    private val floorSport = lineShader(C_SPORT)
    private val floorComfort = lineShader(C_COMFORT_GLOW)
    private val floorSportPlus = lineShader(C_RED)

    // ---- car: photo (with a pre-rendered fading reflection) or a drawn fastback profile ----
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var photo: Bitmap? = null
    private var reflection: Bitmap? = null
    private val photoDst = RectF()
    private val reflectDst = RectF()
    private var photoChecked = false
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 286f, 0f, 370f, 0xFFC9CED4.toInt(), 0xFF5D646C.toInt(), Shader.TileMode.CLAMP)
    }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1F25.toInt() }
    private val tyrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E0F11.toInt() }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF9AA1AA.toInt() }
    private val bodyPath = Path().apply {
        moveTo(432f, 366f); lineTo(435f, 342f); quadTo(440f, 332f, 470f, 328f); lineTo(545f, 320f)
        quadTo(575f, 294f, 600f, 288f); lineTo(662f, 287f); quadTo(700f, 294f, 724f, 312f)
        lineTo(750f, 318f); quadTo(756f, 334f, 752f, 350f); lineTo(746f, 366f); close()
    }
    private val glassPath = Path().apply {
        moveTo(560f, 320f); quadTo(582f, 299f, 602f, 294f); lineTo(658f, 293f); quadTo(688f, 299f, 708f, 314f); close()
    }

    private val clock = SimpleDateFormat("HH:mm", Locale.US)
    private val date = Date()

    private val tClock = TextCache()
    private val tSub = TextCache()
    private val tRpm = TextCache()
    private val tClt = TextCache()
    private val tKml = TextCache()
    private val tThr = TextCache()
    private val tOil = TextCache()
    private val tAir = TextCache()
    private val tTrip = TextCache()

    override fun draw(c: Canvas, f: FrameModel) {
        c.drawRect(0f, 0f, Viewport.LOGICAL_W, Viewport.LOGICAL_H, bgPaint)
        c.drawRect(0f, 0f, Viewport.LOGICAL_W, Viewport.LOGICAL_H, stagePaint) // fades out by itself: no edges
        drawStage(c, f)
        drawHeader(c, f)
        drawMode(c, f)
        drawRows(c, f)
        drawStats(c, f)
        drawStatusOverlay(c, f, fonts)
    }

    private fun drawHeader(c: Canvas, f: FrameModel) {
        val y = 66f
        c.drawText("Verna 1.6 VTVT", LEFT, y + midHeader, headerText)
        val x = LEFT + headerText.measureText("Verna 1.6 VTVT") + 22f
        val clockStr = tClock.get(f.wallMs / 60_000L) { date.time = f.wallMs; clock.format(date) }
        c.drawText(clockStr, x, y + midHeader, clockText)
        if (f.warnings.isNotEmpty()) {
            val wx = x + clockText.measureText(clockStr) + 22f
            warnPath.rewind()
            warnPath.moveTo(wx + 9f, y - 9f); warnPath.lineTo(wx + 18f, y + 8f); warnPath.lineTo(wx, y + 8f); warnPath.close()
            c.drawPath(warnPath, warnFill)
            c.drawLine(wx + 9f, y - 3f, wx + 9f, y + 2f, warnFill)
            c.drawPoint(wx + 9f, y + 5f, warnFill)
        }
        c.drawLine(LEFT, 88f, 700f, 88f, rulePaint)
    }

    private fun modeColor(m: DriveMode) = when (m) {
        DriveMode.COMFORT -> C_COMFORT
        DriveMode.SPORT -> C_SPORT
        DriveMode.SPORT_PLUS -> C_RED
    }

    private fun drawMode(c: Canvas, f: FrameModel) {
        val col = modeColor(f.driveMode)
        modeText.color = col
        subText.color = col
        c.drawText(f.driveMode.label, LEFT, 150f, modeText)
        val gear = f.gear
        val speed = f.speed
        val key = (gear ?: 0) * 10_000L + (textKey(speed) and 0xFFFF)
        val sub = tSub.get(key) {
            val s = Fmt.int(speed) + " km/h"
            if (gear == null) s else ordinal(gear) + " gear,  " + s
        }
        c.drawText(sub, LEFT, 186f, subText)

        // Mode track: the picked mode is lit; a tap on it (modeHit) steps to the next.
        for (m in DriveMode.entries) {
            val x = LEFT + m.ordinal * (TRACK_SEG + TRACK_GAP)
            val on = m == f.driveMode
            trackPaint.color = if (on) modeColor(m) else C_TRACK
            if (on) trackPaint.setShadowLayer(glowRadius(14f), 0f, 0f, modeColor(m)) else trackPaint.clearShadowLayer()
            trackRect.set(x, TRACK_Y, x + TRACK_SEG, TRACK_Y + 6f)
            c.drawRoundRect(trackRect, 3f, 3f, trackPaint)
            trackText.color = if (on) modeColor(m) else C_SOFT
            c.drawText(m.label, x, TRACK_Y - 11f, trackText)
        }
        c.drawPath(chevronPath, chevron)
    }

    private val DriveMode.label: String
        get() = when (this) {
            DriveMode.COMFORT -> "Comfort"
            DriveMode.SPORT -> "Sport"
            DriveMode.SPORT_PLUS -> "Sport+"
        }

    private fun ordinal(g: Int) = g.toString() + when (g) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

    /** The highlighted row: coolant when overheating, rpm in Sport and Sport+, else economy. */
    private fun selectedRow(f: FrameModel) = when {
        Warning.OVERHEAT in f.warnings -> ROW_COOLANT
        f.driveMode != DriveMode.COMFORT -> ROW_RPM
        else -> ROW_ECONOMY
    }

    private fun drawRows(c: Canvas, f: FrameModel) {
        val sel = selectedRow(f)
        val rpm = f.rpm
        val clt = f.coolant
        val kml = f.instantKml
        row(c, ROW_RPM, sel, "Engine speed", tRpm.get(textKey(rpm, 0.1)) {
            if (rpm == null) "--" else String.format(Locale.US, "%,d rpm", Math.round(rpm / 10) * 10)
        }, rpm?.let { Fmt.clamp01(it, 0.0, TACH_MAX_RPM) })
        row(c, ROW_COOLANT, sel, "Coolant", tClt.get(textKey(clt)) { Fmt.temp(clt, false) + "C" },
            clt?.let { Fmt.clamp01(it, 40.0, 120.0) })
        val kmlFrac = when {
            kml == null -> null
            kml == Double.POSITIVE_INFINITY -> 1.0
            else -> Fmt.clamp01(kml, 0.0, ECONOMY_FULL)
        }
        row(c, ROW_ECONOMY, sel, "Economy", tKml.get(textKey(kml, 10.0)) { Fmt.kml(kml) + " km/l" }, kmlFrac)
    }

    private fun row(c: Canvas, i: Int, sel: Int, label: String, value: String, level: Double?) {
        val y = ROW_Y[i]
        val on = i == sel
        val top = y - ROW_H / 2f
        val bottom = y + ROW_H / 2f
        if (on) {
            rowRect.set(LEFT, top, ROW_RIGHT, bottom)
            c.drawRoundRect(rowRect, 4f, 4f, rowFill)
            c.drawRoundRect(rowRect, 4f, 4f, rowEdge)
        } else if (i != sel - 1) {
            c.drawLine(LEFT + 36f, bottom + 3f, ROW_RIGHT, bottom + 3f, rowSep)
        }
        radio.color = if (on) C_TEXT else C_SOFT
        c.drawCircle(LEFT + 18f, y, 6f, radio)
        if (on) c.drawCircle(LEFT + 18f, y, 3f, radioDot)
        rowLabel.color = if (on) C_TEXT else C_SOFT
        c.drawText(label, LEFT + 36f, y - 3f + midRow, rowLabel)
        rowValue.color = if (on) C_TEXT else C_SOFT
        c.drawText(value, ROW_RIGHT - 14f, y - 3f + midRow, rowValue)
        // Level bar: the value against its range, under the label and value.
        val x0 = LEFT + 36f
        val x1 = ROW_RIGHT - 14f
        val ly = y + 12f
        levelPaint.color = if (on) Color.argb(90, 255, 255, 255) else C_TRACK
        c.drawLine(x0, ly, x1, ly, levelPaint)
        if (level != null && level > 0.0) {
            levelPaint.color = if (on) C_TEXT else C_SOFT
            c.drawLine(x0, ly, x0 + (x1 - x0) * level.toFloat(), ly, levelPaint)
        }
    }

    /** Secondary readings under the menu, separated by hairlines. */
    private fun drawStats(c: Canvas, f: FrameModel) {
        val thr = f.throttle
        val oil = f.oil
        val iat = f.iat
        val trip = f.tripKml
        stat(c, 0, "Throttle", tThr.get(textKey(thr)) { if (thr == null) "--" else Fmt.int(thr) + "%" })
        stat(c, 1, "Oil", tOil.get(textKey(oil, unsupported = f.oilUnsupported)) { Fmt.temp(oil, f.oilUnsupported) })
        stat(c, 2, "Intake air", tAir.get(textKey(iat)) { Fmt.temp(iat, false) })
        stat(c, 3, "Trip avg", tTrip.get(textKey(trip, 10.0)) { Fmt.kml(trip) })
    }

    private fun stat(c: Canvas, i: Int, label: String, value: String) {
        val x = LEFT + i * STAT_W
        if (i > 0) c.drawLine(x - 10f, STATS_Y - 16f, x - 10f, STATS_Y + 14f, statSep)
        c.drawText(label, x, STATS_Y - 6f, statLabel)
        c.drawText(value, x, STATS_Y + 13f, statValue)
    }

    private fun drawStage(c: Canvas, f: FrameModel) {
        var save = c.save()
        c.scale(1f, 0.35f, CAR_CX, FLOOR_Y)
        c.drawCircle(CAR_CX, FLOOR_Y, 260f, poolPaint)
        c.restoreToCount(save)
        c.drawLines(seams, seamPaint)

        val rpmN = Fmt.clamp01(f.rpm ?: 0.0, 800.0, TACH_MAX_RPM).toFloat()
        val glow: Paint
        val line: Shader
        when (f.driveMode) {
            DriveMode.COMFORT -> { glow = glowComfort; line = floorComfort }
            DriveMode.SPORT -> { glow = glowSport; line = floorSport }
            DriveMode.SPORT_PLUS -> { glow = glowSportPlus; line = floorSportPlus }
        }
        glow.alpha = (90 + 165 * rpmN).toInt()
        save = c.save()
        c.scale(1f, GLOW_SQUASH, CAR_CX, FLOOR_Y)
        c.drawCircle(CAR_CX, FLOOR_Y, GLOW_R, glow)
        c.restoreToCount(save)

        if (!photoChecked) {
            photoChecked = true
            photo = fonts.carPhoto?.also { fitPhoto(it); reflection = makeReflection(it) }
        }
        val p = photo
        if (p != null) {
            reflection?.let { c.drawBitmap(it, null, reflectDst, photoPaint) }
            c.drawBitmap(p, null, photoDst, photoPaint)
        } else {
            c.drawPath(bodyPath, bodyPaint)
            c.drawPath(glassPath, glassPaint)
            for (x in floatArrayOf(486f, 692f)) {
                c.drawCircle(x, 368f, 25f, tyrePaint)
                c.drawCircle(x, 368f, 15f, rimPaint)
            }
        }
        floorLine.shader = line
        c.drawLine(CAR_CX - 220f, FLOOR_Y + 1f, CAR_CX + 220f, FLOOR_Y + 1f, floorLine)
    }

    /** Scales the photo to fit [CAR_MAX_W]×[CAR_MAX_H], centred on [CAR_CX], tyres on the floor. */
    private fun fitPhoto(b: Bitmap) {
        val s = minOf(CAR_MAX_W / b.width, CAR_MAX_H / b.height)
        val w = b.width * s
        val h = b.height * s
        photoDst.set(CAR_CX - w / 2f, FLOOR_Y - h, CAR_CX + w / 2f, FLOOR_Y)
        reflectDst.set(photoDst.left, FLOOR_Y, photoDst.right, FLOOR_Y + h * REFLECT)
    }

    /** The photo's bottom [REFLECT] share, flipped, fading from 30 % to clear. Built once. */
    private fun makeReflection(b: Bitmap): Bitmap? = try {
        val h = (b.height * REFLECT).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(b.width, h, Bitmap.Config.ARGB_8888)
        val rc = Canvas(out)
        val m = Matrix().apply { setScale(1f, -1f); postTranslate(0f, b.height.toFloat()) }
        rc.drawBitmap(b, m, photoPaint)
        val fade = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.argb(77, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        rc.drawRect(0f, 0f, b.width.toFloat(), h.toFloat(), fade)
        out
    } catch (e: Exception) {
        null
    }
}
