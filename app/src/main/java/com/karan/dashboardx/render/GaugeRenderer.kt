package com.karan.dashboardx.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.karan.dashboardx.alerts.Warning
import com.karan.dashboardx.data.ConnectionStatus
import com.karan.dashboardx.data.Style
import kotlin.math.floor

/** Tachometer full scale shared by every style (plan default). */
const val TACH_MAX_RPM = 7000.0

/**
 * One visual style. [draw] paints a complete frame in the 800×480 logical space (the caller
 * applies the [Viewport] transform), finishing with [drawStatusOverlay]. Implementations
 * allocate their Paints/Paths/RectFs once and must not allocate per frame beyond formatted text.
 * One instance is used from one thread.
 */
interface GaugeRenderer {
    fun draw(c: Canvas, f: FrameModel)
}

fun rendererFor(style: Style, fonts: Fonts): GaugeRenderer = when (style) {
    Style.RACING -> RacingRenderer(fonts)
    Style.HUD -> HudRenderer(fonts)
    Style.ARCADE -> ArcadeRenderer(fonts)
    Style.LUXURY -> LuxuryRenderer(fonts)
    Style.CLUSTER -> ClusterRenderer(fonts)
}

/** Canvas `shadowBlur` (≈ 2σ) to Android shadow-layer radius (σ = 0.57735·r + 0.5). */
fun glowRadius(shadowBlur: Float): Float = ((shadowBlur / 2f - 0.5f) / 0.57735f).coerceAtLeast(0.5f)

/** Offset to add to a y so text drawn with [p] is vertically centred on it (canvas `middle`). Setup-time only. */
fun middleOffset(p: Paint): Float {
    val fm = p.fontMetrics
    return -(fm.ascent + fm.descent) / 2f
}

/**
 * Re-formats text only when its key changes, so steady values don't build a String every frame.
 * Keys come from [textKey]; the formatter must depend only on what the key encodes.
 */
class TextCache {
    @PublishedApi internal var key = Long.MIN_VALUE
    @PublishedApi internal var text = ""

    inline fun get(k: Long, make: () -> String): String {
        if (k != key || text.isEmpty()) {
            key = k
            text = make()
        }
        return text
    }
}

private const val KEY_NULL = Long.MIN_VALUE + 1
private const val KEY_UNSUPPORTED = Long.MIN_VALUE + 2

/** Cache key for [v] rounded at [scale] (1 = integer, 10 = one decimal, 0.1 = tens). */
fun textKey(v: Double?, scale: Double = 1.0, unsupported: Boolean = false): Long = when {
    v != null -> Math.round(v * scale)
    unsupported -> KEY_UNSUPPORTED
    else -> KEY_NULL
}

private class StatusOverlay {
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 5, 7, 10) }
    private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF2A3340.toInt()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f; textAlign = Paint.Align.CENTER
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 18f; color = 0xFFFF2D3D.toInt()
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 230, 236, 242); textSize = 15f; textAlign = Paint.Align.CENTER
    }
    private val tagFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 5, 7, 10) }
    private val tagEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.argb(140, 79, 195, 247)
    }
    private val rect = RectF()
    private val demoRect = RectF()
    private val hintRect = RectF()
    private var fonts: Fonts? = null
    private var textMid = 0f
    private var smallMid = 0f

    fun draw(c: Canvas, f: FrameModel, fonts: Fonts) {
        if (this.fonts !== fonts) {
            this.fonts = fonts
            fonts.orbitron(text, 500)
            textMid = middleOffset(text)
            fonts.orbitron(small, 500)
            smallMid = middleOffset(small)
            // Fixed strings: measure once here, not per frame.
            val dw = small.measureText(DEMO) / 2f + 10f
            demoRect.set(14f, 12f, 14f + dw * 2f, 38f)
            val hw = small.measureText(NO_DATA) / 2f + 16f
            hintRect.set(400f - hw, 430f, 400f + hw, 462f)
        }
        val msg = when (f.status) {
            ConnectionStatus.CONNECTING -> "Connecting to OBD…"
            ConnectionStatus.RECONNECTING -> "Reconnecting…"
            ConnectionStatus.IDLE_NEEDS_SETUP -> "Set up DashboardX on your phone"
            ConnectionStatus.DEMO, ConnectionStatus.CONNECTED -> null
        }
        if (msg != null) {
            c.drawRect(0f, 0f, Viewport.LOGICAL_W, Viewport.LOGICAL_H, dim)
            val half = text.measureText(msg) / 2f + 28f
            rect.set(400f - half, 240f - 34f, 400f + half, 240f + 34f)
            c.drawRoundRect(rect, 14f, 14f, pill)
            c.drawRoundRect(rect, 14f, 14f, pillEdge)
            c.drawText(msg, 400f, 240f + textMid, text)
        }
        if (f.status == ConnectionStatus.DEMO) {
            c.drawRoundRect(demoRect, 6f, 6f, tagFill)
            c.drawRoundRect(demoRect, 6f, 6f, tagEdge)
            c.drawText(DEMO, demoRect.centerX(), demoRect.centerY() + smallMid, small)
        }
        if (f.noCarData && f.status == ConnectionStatus.CONNECTED) {
            c.drawRoundRect(hintRect, 10f, 10f, pill)
            c.drawRoundRect(hintRect, 10f, 10f, pillEdge)
            c.drawText(NO_DATA, 400f, hintRect.centerY() + smallMid, small)
        }
        // Red edge flash at 3 Hz (on for the first half of each 1/3 s period).
        if (Warning.OVERHEAT in f.warnings && floor(f.tSec * 6.0).toLong() % 2L == 0L) {
            val h = edge.strokeWidth / 2f
            c.drawRect(h, h, Viewport.LOGICAL_W - h, Viewport.LOGICAL_H - h, edge)
        }
    }
}

private const val DEMO = "DEMO"
private const val NO_DATA = "No data from car \u2014 is the engine on?"

// Paints are mutable, so each render thread (phone UI, car surface) gets its own set.
private val overlays = ThreadLocal.withInitial { StatusOverlay() }

/**
 * Shared status layer: dims and labels CONNECTING/RECONNECTING/NEEDS_SETUP; a small DEMO tag
 * (top-left) in demo mode; a "No data from car" hint when [FrameModel.noCarData]; flashes the
 * edge on OVERHEAT. Allocation-free per frame.
 */
fun drawStatusOverlay(c: Canvas, f: FrameModel, fonts: Fonts) {
    overlays.get()!!.draw(c, f, fonts)
}
