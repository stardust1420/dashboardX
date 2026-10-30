package com.karan.dashboardx.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.karan.dashboardx.DashRuntime
import com.karan.dashboardx.car.FrameFailureLog
import com.karan.dashboardx.data.Style
import com.karan.dashboardx.render.Fonts
import com.karan.dashboardx.render.FrameModel
import com.karan.dashboardx.render.GaugeRenderer
import com.karan.dashboardx.render.Viewport
import com.karan.dashboardx.render.rendererFor

/** Full-screen live dashboard: the current style drawn every frame, fitted to the view. */
class DashView(ctx: Context) : View(ctx) {
    private val fonts = Fonts(ctx)
    private var style: Style? = null
    private var renderer: GaugeRenderer? = null
    private var vp = Viewport.fit(0, 0, 1, 1)

    /** Called on the UI thread with each frame's model, before it is drawn (the engine sound feed). */
    var onFrame: ((FrameModel) -> Unit)? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private val failureLog = FrameFailureLog { DashRuntime.diag.add(it.replaceFirst("car:", "phone:")) } // UI thread only

    init {
        DashRuntime.init(ctx)
        isClickable = true
        contentDescription = "Dashboard. Tap the mode selector to change drive mode, anywhere else to change style."
        setBackgroundColor(Color.BLACK)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        vp = Viewport.fit(0, 0, w, h)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (dx * dx + dy * dy <= slop * slop) {
                    // On the drive-mode selector: step the mode. Anywhere else: change the style.
                    val hit = renderer?.modeHit?.contains(vp.toLogicalX(e.x), vp.toLogicalY(e.y)) == true
                    if (hit) {
                        DashRuntime.cycleDriveMode()
                        invalidate()
                    } else {
                        performClick()
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** A tap cycles the style; onDraw swaps the renderer when [Settings.style] changes. */
    override fun performClick(): Boolean {
        super.performClick()
        val st = DashRuntime.settings
        st.style = st.style.next()
        invalidate()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val save = c.save()
        try {
            val s = DashRuntime.settings.style
            val r = renderer?.takeIf { style == s } ?: rendererFor(s, fonts).also { renderer = it; style = s }
            // Wall clock: CarState readings are stamped with System.currentTimeMillis().
            val f = DashRuntime.frame(System.currentTimeMillis())
            onFrame?.invoke(f)
            c.translate(vp.dx, vp.dy)
            c.scale(vp.scale, vp.scale)
            c.clipRect(0f, 0f, Viewport.LOGICAL_W, Viewport.LOGICAL_H)
            r.draw(c, f)
            failureLog.success()
        } catch (e: Exception) {
            // Keep the UI alive: log (rate-limited) and try again next frame.
            failureLog.failure("draw", e)
        } finally {
            c.restoreToCount(save)
        }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}
