package com.karan.dashboardx.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.karan.dashboardx.alerts.Warning
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Style F, "Most Wanted": the game's cover art ([Fonts.mostWantedArt]) fills the left third and
 * fades into a teal-to-asphalt panel holding a street-racing round tach: an amber needle on the
 * outer ring only (so it never crosses the big gear in the middle), lit rpm arc, digital speed below. Throttle and coolant sit in two segmented bars left
 * of the tach, oil / air / km/l along the top left of the panel, the drive-mode selector over the foot of the art. Without the art the panel simply runs full width.
 *
 * Everything static (art, fade, panel, tach face, ticks, numerals, redline arc) is baked into one
 * bitmap, rebuilt only when the redline changes.
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

        const val ART_W = 320f // 600×900 cover scaled to the full 480 height
        const val FADE_FROM = 236f

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

    private val gearMid = middleOffset(gearText)
    private val speedMid = middleOffset(speedText)
    private val shiftMid = middleOffset(shiftText)

    private val tGear = TextCache()
    private val tSpeed = TextCache()
    private val tOil = TextCache()
    private val tAir = TextCache()
    private val tKml = TextCache()

    override fun draw(c: Canvas, f: FrameModel) {
        if (backdrop == null || bakedRedline != f.redline) {
            backdrop?.recycle()
            backdrop = bake(f.redline)
            bakedRedline = f.redline
        }
        c.drawBitmap(backdrop!!, 0f, 0f, null)
        drawTach(c, f)
        drawBars(c, f)
        drawTopStats(c, f)
        modeSel.draw(c, f.driveMode)
        drawStatusOverlay(c, f, fonts)
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

    private fun bake(redline: Int): Bitmap {
        val bmp = Bitmap.createBitmap(Viewport.LOGICAL_W.toInt(), Viewport.LOGICAL_H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, 800f, 480f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 480f, C_PANEL_TOP, C_PANEL_BOTTOM, Shader.TileMode.CLAMP)
        })
        // Warm sodium-light haze behind the tach, like the cover's sky.
        c.drawRect(0f, 0f, 800f, 480f, Paint().apply {
            shader = RadialGradient(CX, CY - 40f, 300f, 0x40FF9A1F, 0x00FF9A1F, Shader.TileMode.CLAMP)
        })
        fonts.mostWantedArt?.let { art -> drawArt(c, art) }
        drawFace(c, redline)
        return bmp
    }

    /** The cover, scaled to the left [ART_W] px, its right edge faded into the panel. */
    private fun drawArt(c: Canvas, art: Bitmap) {
        val layer = c.saveLayer(0f, 0f, ART_W, 480f, null)
        c.drawBitmap(art, null, RectF(0f, 0f, ART_W, 480f), Paint(Paint.FILTER_BITMAP_FLAG))
        c.drawRect(FADE_FROM, 0f, ART_W, 480f, Paint().apply {
            shader = LinearGradient(FADE_FROM, 0f, ART_W, 0f, 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
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
