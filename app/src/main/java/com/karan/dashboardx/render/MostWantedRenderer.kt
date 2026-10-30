package com.karan.dashboardx.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.karan.dashboardx.alerts.Warning
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Style F, "Most Wanted": the game's cover art ([Fonts.mostWantedArt]) fills the left third and
 * fades into a teal-to-asphalt panel holding a street-racing round tach: an amber needle on the
 * outer ring only (so it never crosses the big gear in the middle), lit rpm arc, digital speed below. Throttle and coolant sit in two segmented bars left
 * of the tach, oil / air / km/l along the top left of the panel, the drive-mode selector over the foot of the art,
 * the Sound toggle in the bottom-right corner. The art takes [ART_SHARE] of the screen width, edge included (cropping
 * sky and road to fit, not the title), and the panel is scaled to fit the rest ([panelLeft], [panelScale]); without
 * the art the backdrop is plain panel. On a wide screen the Sound toggle slides into the right corner.
 *
 * Everything static (art, fade, panel, tach face, ticks, numerals, redline arc) is baked into one
 * bitmap, rebuilt only when the redline or the screen width changes.
 */
class MostWantedRenderer(private val fonts: Fonts) : GaugeRenderer {

    private companion object {
        const val C_PANEL_TOP = 0xFF2C3B3A.toInt()
        const val C_PANEL_BOTTOM = 0xFF0C0B09.toInt()
        const val C_FACE_IN = 0xFF141A1B.toInt()
        const val C_FACE_OUT = 0xFF050606.toInt()
        const val C_STEEL = 0xFF9DB8B4.toInt()
        const val C_TEXT = 0xFFF2EEE6.toInt()
        const val C_SOFT = 0xFFB9C4C0.toInt()
        const val C_DIM = 0xFF55625F.toInt()
        const val C_OFF = 0xFF1E2524.toInt()
        const val C_AMBER = 0xFFFF9A1F.toInt()
        const val C_RED = 0xFFFF2E1F.toInt()
        const val C_NOS = 0xFF3CC8FF.toInt()

        const val ART_SHARE = 0.5f // of the whole screen width
        const val ART_W = 320f // panel layout: the art's edge in the 800-wide design, the panel to its right
        const val FADE_SHARE = 0.35f // the art's right part that fades into the panel

        val FADE_ALPHA = intArrayOf(0xFF000000.toInt(), 0xD8000000.toInt(), 0x80000000.toInt(), 0x28000000, 0)
        val FADE_POS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        const val CX = 596f
        const val CY = 262f
        const val R = 170f
        const val START_DEG = 135f
        const val SWEEP_DEG = 270f
        const val PROGRESS_INSET = 10f
        const val SKEW = -0.18f // italic lean for the racing numerals

        const val BAR_TOP = 120f
        const val BAR_BOTTOM = 392f
        const val BAR_W = 16f
        const val BAR_SEGS = 18
        const val THR_X = 346f
        const val H2O_X = 378f
        const val TOP_Y = 46f
        const val NEEDLE_FROM = R - 78f // inner end of the needle, clear of the gear digit
    }

    private fun text(size: Float, color: Int, align: Paint.Align, weight: Int) =
        fonts.orbitron(Paint(Paint.ANTI_ALIAS_FLAG), weight).apply {
            textSize = size; this.color = color; textAlign = align; textSkewX = SKEW
        }

    private fun mono(size: Float, color: Int, align: Paint.Align) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.shareTechMono; textSize = size; this.color = color; textAlign = align
    }

    // ---- baked backdrop ----
    private var backdrop: Bitmap? = null
    private var bakedRedline = Int.MIN_VALUE
    private var bakedExt = -1 // whole logical px: the bitmap is 800 + 2·bakedExt wide
    private val tachRect = RectF(CX - R, CY - R, CX + R, CY + R)
    private val progressRect = RectF(tachRect).apply { inset(PROGRESS_INSET, PROGRESS_INSET) }

    // ---- per-frame paints ----
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.BUTT
    }
    private val rimFlash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = C_RED
        setShadowLayer(glowRadius(18f), 0f, 0f, C_RED)
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val needlePath = Path().apply {
        moveTo(NEEDLE_FROM, -4f)
        lineTo(R - 16f, -1.5f)
        lineTo(R - 16f, 1.5f)
        lineTo(NEEDLE_FROM, 4f)
        close()
    }
    private val segPaint = Paint()

    private val gearText = text(96f, C_TEXT, Paint.Align.CENTER, 900).apply {
        setShadowLayer(glowRadius(16f), 0f, 0f, 0x99FF9A1F.toInt())
    }
    private val speedText = text(40f, C_TEXT, Paint.Align.CENTER, 800)
    private val unitText = text(13f, C_SOFT, Paint.Align.CENTER, 600)
    private val shiftText = text(20f, C_RED, Paint.Align.CENTER, 900)
    private val barLabel = mono(14f, C_SOFT, Paint.Align.CENTER)
    private val statLabel = mono(14f, C_DIM, Paint.Align.LEFT)
    private val statValue = mono(22f, C_TEXT, Paint.Align.LEFT)

    private val modeSel = ModeSelector(20f, 420f, 304f, 464f, text(15f, C_TEXT, Paint.Align.CENTER, 900),
        intArrayOf(C_STEEL, C_AMBER, C_RED), C_SOFT, 0xD90A0C0C.toInt(), 5f)
    override val modeHit: RectF get() = modeSel.hit
    private val soundBtn = SoundButton(744f, 420f, 788f, 464f, C_AMBER, C_SOFT, 0xD90A0C0C.toInt(), 5f)
    override val soundHit: RectF get() = soundBtn.hit

    private val gearMid = middleOffset(gearText)
    private val speedMid = middleOffset(speedText)
    private val shiftMid = middleOffset(shiftText)

    private val tGear = TextCache()
    private val tSpeed = TextCache()
    private val tOil = TextCache()
    private val tAir = TextCache()
    private val tKml = TextCache()

    override fun draw(c: Canvas, f: FrameModel, ext: Float) {
        // Whole px, so the baked face and the live needle slide by exactly the same amount.
        val e = ceil(ext).toInt()
        if (backdrop == null || bakedRedline != f.redline || bakedExt != e) {
            backdrop?.recycle()
            backdrop = bake(f.redline, e)
            bakedRedline = f.redline
            bakedExt = e
        }
        val dx = e.toFloat()
        c.drawBitmap(backdrop!!, -dx, 0f, null)
        val save = c.save()
        c.toPanel(panelLeft(e) - dx, panelScale(e))
        drawTach(c, f)
        drawBars(c, f)
        drawTopStats(c, f)
        c.restoreToCount(save)
        modeSel.draw(c, f.driveMode, -dx)
        soundBtn.draw(c, f.soundOn, dx)
        drawStatusOverlay(c, f, fonts, ext)
    }

    /** Width of the art, from the left edge of a screen [ext] px wider on each side. */
    private fun artWidth(ext: Int): Float = ART_SHARE * (Viewport.LOGICAL_W + 2 * ext)

    /** Left edge of the panel in backdrop px (the backdrop starts [ext] px left of the 800-wide core). */
    private fun panelLeft(ext: Int): Float = artWidth(ext)

    /** The panel's 480-px-wide design (x [ART_W]..800) shrunk to the width the art leaves. */
    private fun panelScale(ext: Int): Float =
        ((Viewport.LOGICAL_W + 2 * ext - artWidth(ext)) / (Viewport.LOGICAL_W - ART_W)).coerceAtMost(1f)

    /** Maps the panel design onto the canvas: its left edge at [left], scaled by [k] about the middle row. */
    private fun Canvas.toPanel(left: Float, k: Float) {
        translate(left, Viewport.LOGICAL_H / 2f)
        scale(k, k)
        translate(-ART_W, -Viewport.LOGICAL_H / 2f)
    }

    private fun angleFor(rpm: Double): Float =
        START_DEG + SWEEP_DEG * Fmt.clamp01(rpm, 0.0, TACH_MAX_RPM).toFloat()

    private fun drawTach(c: Canvas, f: FrameModel) {
        val rpm = f.rpm
        val redline = Warning.REDLINE in f.warnings
        val flashOn = redline && floor(f.tSec * 6.0).toLong() % 2L == 1L
        val hot = rpm != null && rpm >= f.redline
        val col = if (hot) C_RED else C_AMBER

        if (rpm != null && rpm > 0.0) {
            progressPaint.color = col
            progressPaint.setShadowLayer(glowRadius(12f), 0f, 0f, col)
            c.drawArc(progressRect, START_DEG, angleFor(rpm) - START_DEG, false, progressPaint)
        }
        if (flashOn) c.drawCircle(CX, CY, R + 4f, rimFlash)

        val gear = f.gear
        c.drawText(tGear.get(textKey(gear?.toDouble())) { Fmt.gear(gear) }, CX, CY - 18f + gearMid, gearText)
        val speed = f.speed
        c.drawText(tSpeed.get(textKey(speed)) { Fmt.int(speed) }, CX, CY + 82f + speedMid, speedText)
        c.drawText("KM/H", CX, CY + 118f, unitText)
        if (flashOn) c.drawText("SHIFT", CX, CY - 94f + shiftMid, shiftText)

        // Needle drawn along +x, rotated into place; rests at 0 when there is no rpm.
        needlePaint.color = col
        needlePaint.setShadowLayer(glowRadius(10f), 0f, 0f, col)
        val save = c.save()
        c.translate(CX, CY)
        c.rotate(angleFor(rpm ?: 0.0))
        c.drawPath(needlePath, needlePaint)
        c.restoreToCount(save)
    }

    private fun drawBars(c: Canvas, f: FrameModel) {
        bar(c, THR_X, f.throttle, 0.0, 100.0) { C_NOS }
        bar(c, H2O_X, f.coolant, 40.0, 120.0) { frac -> if (frac >= 0.8) C_RED else C_AMBER }
        c.drawText("THR", THR_X + BAR_W / 2f, BAR_BOTTOM + 22f, barLabel)
        c.drawText("H2O", H2O_X + BAR_W / 2f, BAR_BOTTOM + 22f, barLabel)
    }

    /** Vertical segmented bar filling upward; [color] gets each lit segment's height share (0..1). */
    private inline fun bar(c: Canvas, x: Float, v: Double?, lo: Double, hi: Double, color: (Double) -> Int) {
        val lit = if (v == null) 0 else Math.round(Fmt.clamp01(v, lo, hi) * BAR_SEGS).toInt()
        val step = (BAR_BOTTOM - BAR_TOP) / BAR_SEGS
        for (i in 0 until BAR_SEGS) {
            segPaint.color = if (i < lit) color(i.toDouble() / BAR_SEGS) else C_OFF
            val bottom = BAR_BOTTOM - i * step
            c.drawRect(x, bottom - step + 3f, x + BAR_W, bottom, segPaint)
        }
    }

    private fun drawTopStats(c: Canvas, f: FrameModel) {
        val oil = f.oil
        c.drawText("OIL", 430f, TOP_Y - 24f, statLabel)
        c.drawText(tOil.get(textKey(oil, unsupported = f.oilUnsupported)) { Fmt.temp(oil, f.oilUnsupported) },
            430f, TOP_Y, statValue)
        val iat = f.iat
        c.drawText("AIR", 520f, TOP_Y - 24f, statLabel)
        c.drawText(tAir.get(textKey(iat)) { Fmt.temp(iat, false) }, 520f, TOP_Y, statValue)
        val kml = f.instantKml
        // Not at the right edge: the host's buttons float over the top-right corner.
        c.drawText("KM/L", 610f, TOP_Y - 24f, statLabel)
        c.drawText(tKml.get(textKey(kml, 10.0)) { Fmt.kml(kml) }, 610f, TOP_Y, statValue)
    }

    // ---- one-off bake ----

    /** The backdrop [ext] px wider on each side: the art from its left edge, then the scaled panel face. */
    private fun bake(redline: Int, ext: Int): Bitmap {
        val w = Viewport.LOGICAL_W.toInt() + 2 * ext
        val bmp = Bitmap.createBitmap(w, Viewport.LOGICAL_H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, w.toFloat(), 480f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 480f, C_PANEL_TOP, C_PANEL_BOTTOM, Shader.TileMode.CLAMP)
        })
        val left = panelLeft(ext)
        val k = panelScale(ext)
        // Warm sodium-light haze behind the tach, like the cover's sky.
        val hazeX = left + (CX - ART_W) * k
        val hazeY = 240f + (CY - 40f - 240f) * k
        c.drawRect(0f, 0f, w.toFloat(), 480f, Paint().apply {
            shader = RadialGradient(hazeX, hazeY, 300f * k, 0x40FF9A1F, 0x00FF9A1F, Shader.TileMode.CLAMP)
        })
        fonts.mostWantedArt?.let { art -> drawArt(c, art, artWidth(ext)) }
        c.toPanel(left, k)
        drawFace(c, redline)
        return bmp
    }

    /**
     * The cover, scaled to the left [w] px, its right edge faded into the panel. Wider than the cover's
     * own 2:3 it crops rows, mostly road from the bottom (under the mode selector), keeping the title.
     */
    private fun drawArt(c: Canvas, art: Bitmap, w: Float) {
        val rows = (art.width * 480f / w).toInt().coerceAtMost(art.height)
        val top = ((art.height - rows) * 0.18f).toInt()
        val src = Rect(0, top, art.width, top + rows)
        val fade = w * (1f - FADE_SHARE)
        val layer = c.saveLayer(0f, 0f, w, 480f, null)
        c.drawBitmap(art, src, RectF(0f, 0f, w, 480f), Paint(Paint.FILTER_BITMAP_FLAG))
        // Past w: w is fractional, and a part-covered last column would keep its art (a thin line).
        c.drawRect(fade, 0f, ceil(w) + 1f, 480f, Paint().apply {
            // Smoothstep, not linear, so bright highlights are gone before the edge instead of ending in a line.
            shader = LinearGradient(fade, 0f, w, 0f, FADE_ALPHA, FADE_POS, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        c.restoreToCount(layer)
    }

    private fun drawFace(c: Canvas, redline: Int) {
        val aa = Paint.ANTI_ALIAS_FLAG
        c.drawCircle(CX, CY, R, Paint(aa).apply {
            shader = RadialGradient(CX, CY, R, C_FACE_IN, C_FACE_OUT, Shader.TileMode.CLAMP)
        })
        c.drawCircle(CX, CY, R, Paint(aa).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = C_STEEL })
        c.drawCircle(CX, CY, R + 7f, Paint(aa).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = C_DIM })

        // Unlit rpm track, then the redline zone over it.
        val track = Paint(aa).apply { style = Paint.Style.STROKE; strokeWidth = 7f; color = C_OFF }
        c.drawArc(progressRect, START_DEG, SWEEP_DEG, false, track)
        val red = angleFor(redline.toDouble())
        c.drawArc(progressRect, red, START_DEG + SWEEP_DEG - red, false,
            Paint(aa).apply { style = Paint.Style.STROKE; strokeWidth = 7f; color = 0xFF6E1410.toInt() })

        val minor = Paint(aa).apply { strokeWidth = 2f; color = C_SOFT }
        val major = Paint(aa).apply { strokeWidth = 4f; color = C_TEXT }
        val num = text(26f, C_TEXT, Paint.Align.CENTER, 800)
        val numMid = middleOffset(num)
        val steps = (TACH_MAX_RPM / 500.0).toInt()
        for (i in 0..steps) {
            val rpm = i * 500.0
            val isMajor = i % 2 == 0
            val a = Math.toRadians(angleFor(rpm).toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val outer = R - 22f
            val inner = outer - if (isMajor) 18f else 10f
            val p = if (isMajor) major else minor
            p.color = if (rpm >= redline) C_RED else if (isMajor) C_TEXT else C_SOFT
            c.drawLine(CX + ca * inner, CY + sa * inner, CX + ca * outer, CY + sa * outer, p)
            if (isMajor) {
                num.color = p.color
                val rn = inner - 22f
                c.drawText((i / 2).toString(), CX + ca * rn, CY + sa * rn + numMid, num)
            }
        }
        c.drawText("x1000 RPM", CX, CY + 40f, mono(12f, C_DIM, Paint.Align.CENTER))
    }
}
