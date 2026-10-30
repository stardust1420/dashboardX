package com.karan.dashboardx.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.karan.dashboardx.alerts.Warning
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Style B, "Fighter-jet HUD": a port of drawB() in .superpowers/mockups/styles.html. */
class HudRenderer(private val fonts: Fonts) : GaugeRenderer {

    private companion object {
        const val C_BG = 0xFF020805.toInt()
        const val C_GREEN = 0xFF3DFF8F.toInt()
        const val C_RED = 0xFFFF4D4D.toInt()

        const val TOP = 70f
        const val BOT = 390f
        const val MID = (TOP + BOT) / 2f
        const val RPM_X = 40f
        const val SPEED_X = 630f
        const val RPM_PER_UNIT = 0.09f // px per rpm
        const val SPEED_PER_UNIT = 3f // px per km/h

        const val CX = 400f
        const val CY = 220f
        const val GEAR_X = 585f // between the reticle and the speed tape
    }

    private val glow = glowRadius(8f)

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = width; this.color = color
        setShadowLayer(glow, 0f, 0f, color)
    }

    private fun text(size: Float, align: Paint.Align, color: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fonts.shareTechMono
            textSize = size; textAlign = align; this.color = color
            setShadowLayer(glow, 0f, 0f, color)
        }

    private val bgPaint = Paint().apply { color = C_BG }

    private val modeSel = ModeSelector(250f, 28f, 550f, 70f, text(17f, Paint.Align.CENTER, C_GREEN),
        intArrayOf(C_GREEN, C_GREEN, C_RED), 0xFF2FB86A.toInt(), C_BG, 0f, litText = C_BG)
    override val modeHit: RectF get() = modeSel.hit
    // Under the speed tape.
    private val soundBtn = SoundButton(660f, 402f, 712f, 446f, C_GREEN, 0xFF2FB86A.toInt(), C_BG, 0f)
    override val soundHit: RectF get() = soundBtn.hit
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_BG }
    private val line = stroke(C_GREEN, 2f)
    private val lineRed = stroke(C_RED, 2f)
    private val boxStroke = stroke(C_GREEN, 2.5f)
    private val thrArc = stroke(C_GREEN, 6f)

    private val tickText = text(20f, Paint.Align.RIGHT, C_GREEN)
    private val tickTextRed = text(20f, Paint.Align.RIGHT, C_RED)
    private val boxValue = text(30f, Paint.Align.RIGHT, C_GREEN)
    private val gearValue = text(30f, Paint.Align.CENTER, C_GREEN)
    private val tapeLabel = text(16f, Paint.Align.CENTER, C_GREEN)
    private val thrText = text(16f, Paint.Align.CENTER, C_GREEN)
    private val shiftText = text(26f, Paint.Align.CENTER, C_RED)
    private val roLabel = text(14f, Paint.Align.CENTER, C_GREEN)
    private val roBig = text(24f, Paint.Align.CENTER, C_GREEN)
    private val roSmall = text(17f, Paint.Align.CENTER, C_GREEN)

    private val midTick = middleOffset(tickText)
    private val midBox = middleOffset(boxValue)
    private val midGear = middleOffset(gearValue)
    private val midTape = middleOffset(tapeLabel)
    private val midThr = middleOffset(thrText)
    private val midShift = middleOffset(shiftText)
    private val midRoLabel = middleOffset(roLabel)
    private val midRoBig = middleOffset(roBig)
    private val midRoSmall = middleOffset(roSmall)

    // Reticle: four outer cardinal ticks plus the crosshair pieces, as drawLines() pairs.
    private val reticle: FloatArray = run {
        val pts = ArrayList<Float>()
        for (i in 0 until 4) {
            val a = i * PI / 2
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            pts += listOf(CX + ca * 100, CY + sa * 100, CX + ca * 116, CY + sa * 116)
        }
        pts += listOf(CX - 30, CY, CX - 10, CY, CX + 10, CY, CX + 30, CY, CX, CY - 6, CX, CY + 6)
        pts.toFloatArray()
    }
    private val reticleOval = android.graphics.RectF(CX - 90, CY - 90, CX + 90, CY + 90)

    private fun boxPath(x: Float) = Path().apply {
        moveTo(x - 6, MID - 22); lineTo(x + 104, MID - 22); lineTo(x + 122, MID)
        lineTo(x + 104, MID + 22); lineTo(x - 6, MID + 22); close()
    }
    private val rpmBox = boxPath(RPM_X)
    private val speedBox = boxPath(SPEED_X)

    // Pre-built tick labels (index = value / step); beyond these the rare fallback formats on demand.
    private val rpmLabels = Array(24) { Fmt.int(it * 500.0) }
    private val speedLabels = Array(60) { Fmt.int(it * 10.0) }

    private val tRpm = TextCache()
    private val tSpeed = TextCache()
    private val tGear = TextCache()
    private val tThr = TextCache()
    private val tClt = TextCache()
    private val tOil = TextCache()
    private val tIat = TextCache()
    private val tEco = TextCache()

    override fun draw(c: Canvas, f: FrameModel, ext: Float) {
        c.drawRect(-ext, 0f, Viewport.LOGICAL_W + ext, Viewport.LOGICAL_H, bgPaint)
        // The tapes run to the top, so they slide only half way out: the top-right corner stays
        // clear for the phone's gear and the car host's action button.
        val side = ext / 2f
        c.shifted(-side) {
            tape(c, RPM_X, f.rpm, RPM_PER_UNIT, 500, "RPM", rpmLabels, f.redline.toDouble(), tRpm, rpmBox)
        }
        c.shifted(side) {
            tape(c, SPEED_X, f.speed, SPEED_PER_UNIT, 10, "KM/H", speedLabels, Double.MAX_VALUE, tSpeed, speedBox)
            drawGear(c, f)
        }
        drawReticle(c, f)
        drawReadouts(c, f)
        modeSel.draw(c, f.driveMode)
        soundBtn.draw(c, f.soundOn, side)
        drawStatusOverlay(c, f, fonts, ext)
    }

    private fun tape(
        c: Canvas, x: Float, value: Double?, perUnit: Float, step: Int, label: String,
        labels: Array<String>, redFrom: Double, cache: TextCache, box: Path,
    ) {
        if (value != null) {
            val half = (MID - TOP) / perUnit
            val first = floor((value - half) / step).toInt()
            val last = floor((value + half) / step).toInt()
            val save = c.save()
            c.clipRect(x - 10, TOP, x + 130, BOT)
            for (i in first..last) {
                if (i < 0) continue
                val v = i * step
                val y = MID - ((v - value) * perUnit).toFloat()
                val red = v >= redFrom
                c.drawLine(x + 90, y, x + 110, y, if (red) lineRed else line)
                val s = if (i < labels.size) labels[i] else Fmt.int(v.toDouble())
                c.drawText(s, x + 82, y + midTick, if (red) tickTextRed else tickText)
            }
            c.restoreToCount(save)
        }
        c.drawLine(x + 120, TOP, x + 120, BOT, line)
        c.drawPath(box, boxFill)
        c.drawPath(box, boxStroke)
        c.drawText(cache.get(textKey(value)) { Fmt.int(value) }, x + 98, MID + 1 + midBox, boxValue)
        c.drawText(label, x + 55, TOP - 18 + midTape, tapeLabel)
    }

    private fun drawGear(c: Canvas, f: FrameModel) {
        c.drawRect(GEAR_X - 24, MID - 22, GEAR_X + 24, MID + 22, line)
        c.drawText("GEAR", GEAR_X, MID - 38 + midTape, tapeLabel)
        val gear = f.gear
        c.drawText(tGear.get(textKey(gear?.toDouble())) { Fmt.gear(gear) }, GEAR_X, MID + 1 + midGear, gearValue)
    }

    private fun drawReticle(c: Canvas, f: FrameModel) {
        c.drawCircle(CX, CY, 90f, line)
        val thr = f.throttle
        if (thr != null) {
            val sweep = Fmt.clamp01(thr, 0.0, 100.0).toFloat() * 360f
            if (sweep > 0f) c.drawArc(reticleOval, -90f, sweep, false, thrArc)
        }
        c.drawLines(reticle, line)
        c.drawText(tThr.get(textKey(thr)) { "THR " + Fmt.int(thr) + "%" }, CX, CY + 50 + midThr, thrText)
        if (Warning.REDLINE in f.warnings && floor(f.tSec * 6.0).toLong() % 2L == 1L) {
            c.drawText("SHIFT", CX, CY - 50 + midShift, shiftText)
        }
    }

    private fun drawReadouts(c: Canvas, f: FrameModel) {
        val clt = f.coolant
        val oil = f.oil
        val iat = f.iat
        val eco = f.instantKml
        readout(c, 0, "CLT", tClt.get(textKey(clt)) { temp(clt, false) })
        readout(c, 1, "OIL", tOil.get(textKey(oil, unsupported = f.oilUnsupported)) { temp(oil, f.oilUnsupported) })
        readout(c, 2, "IAT", tIat.get(textKey(iat)) { temp(iat, false) })
        readout(c, 3, "ECO", tEco.get(textKey(eco, 10.0)) { Fmt.kml(eco) + " KM/L" })
    }

    private fun temp(v: Double?, unsupported: Boolean): String =
        if (v != null) Fmt.int(v) + "°C" else if (unsupported) "N/A" else "--"

    private fun readout(c: Canvas, i: Int, name: String, value: String) {
        val x = 196f + i * 104f
        val y = 372f
        c.drawRect(x, y, x + 96f, y + 64f, line)
        c.drawText(name, x + 48, y + 16 + midRoLabel, roLabel)
        if (value.length > 6) c.drawText(value, x + 48, y + 42 + midRoSmall, roSmall)
        else c.drawText(value, x + 48, y + 42 + midRoBig, roBig)
    }
}
