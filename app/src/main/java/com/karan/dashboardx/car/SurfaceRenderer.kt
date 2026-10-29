package com.karan.dashboardx.car

import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.karan.dashboardx.DashRuntime
import com.karan.dashboardx.data.Style
import com.karan.dashboardx.render.Fonts
import com.karan.dashboardx.render.GaugeRenderer
import com.karan.dashboardx.render.Viewport
import com.karan.dashboardx.render.rendererFor
import java.util.EnumMap

/**
 * Draws the current [style] into the host's Surface at ~30 fps on a dedicated render thread,
 * fitted to the host's visible area (the part not covered by the action strip and other chrome).
 *
 * Threading: surface and lifecycle callbacks arrive on the main thread; frames are drawn on
 * [thread]. All loop start/stop decisions run on the render thread, so there is never more than
 * one frame chain. [lock] keeps surface teardown from racing a frame in progress.
 */
class SurfaceRenderer(carContext: CarContext) : SurfaceCallback, DefaultLifecycleObserver {
    /** Set by the Style action; read once per frame on the render thread. */
    @Volatile var style: Style = Style.RACING

    private val fonts = Fonts(carContext)
    private val renderers = EnumMap<Style, GaugeRenderer>(Style::class.java) // render thread only

    private val thread = HandlerThread("DashCarRender").apply { start() }
    private val handler = Handler(thread.looper)

    private val lock = Any()
    private var surface: Surface? = null // guarded by lock
    private var surfaceW = 0 // main thread
    private var surfaceH = 0 // main thread
    private var visibleArea: Rect? = null // main thread
    @Volatile private var viewport = Viewport.fitVisibleOrSurface(1, 1, 0, 0, 0, 0)

    @Volatile private var started = false
    private var looping = false // render thread only
    private val failureLog = FrameFailureLog(DashRuntime.diag::add) // render thread only

    private val frame = object : Runnable {
        override fun run() {
            if (!shouldRun()) {
                looping = false
                return
            }
            val t0 = System.nanoTime()
            drawFrame()
            val tookMs = (System.nanoTime() - t0) / 1_000_000L
            handler.postDelayed(this, (FRAME_MS - tookMs).coerceAtLeast(1L))
        }
    }

    // --- SurfaceCallback (main thread) ---

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        synchronized(lock) { surface = surfaceContainer.surface }
        surfaceW = surfaceContainer.width
        surfaceH = surfaceContainer.height
        DashRuntime.diag.add("car: surface ${surfaceW}x$surfaceH @${surfaceContainer.dpi}dpi")
        updateViewport()
        startLoop()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        DashRuntime.diag.add("car: visible area $visibleArea")
        updateViewport()
    }

    /** Logged only: the gauges fit the visible area, which already excludes the action strip. */
    override fun onStableAreaChanged(stableArea: Rect) {
        DashRuntime.diag.add("car: stable area $stableArea")
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        // Waits for an in-progress frame, so nothing draws to this surface after we return.
        synchronized(lock) { surface = null }
        stopLoop()
    }

    // --- Screen lifecycle (main thread) ---

    override fun onStart(owner: LifecycleOwner) {
        started = true
        startLoop()
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        stopLoop()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        started = false
        synchronized(lock) { surface = null }
        stopLoop()
        thread.quitSafely()
    }

    // --- internals ---

    private fun updateViewport() {
        val v = visibleArea
        viewport = if (v == null) Viewport.fitVisibleOrSurface(surfaceW, surfaceH, 0, 0, 0, 0)
        else Viewport.fitVisibleOrSurface(surfaceW, surfaceH, v.left, v.top, v.right, v.bottom)
    }

    private fun shouldRun(): Boolean = started && synchronized(lock) { surface != null }

    /** Starts the frame chain on the render thread unless one is already running. */
    private fun startLoop() {
        if (!shouldRun()) return
        handler.post {
            if (!looping) {
                looping = true
                frame.run()
            }
        }
    }

    /** Cancels the pending frame on the render thread (ordered after any earlier start). */
    private fun stopLoop() {
        handler.post {
            handler.removeCallbacks(frame)
            looping = false
        }
    }

    private fun drawFrame() = synchronized(lock) {
        val s = surface ?: return
        if (!s.isValid) return
        val st = style
        val r = renderers.getOrPut(st) { rendererFor(st, fonts) }
        val vp = viewport
        // Lock/unlock fail when the surface is destroyed mid-frame; failures are logged
        // (rate-limited) so a persistently black car screen is diagnosable, then retried next frame.
        val canvas = try {
            s.lockHardwareCanvas()
        } catch (e: IllegalArgumentException) {
            failureLog.failure("lockHardwareCanvas", e)
            return
        } catch (e: IllegalStateException) {
            failureLog.failure("lockHardwareCanvas", e)
            return
        }
        try {
            // Wall clock: CarState readings are stamped with System.currentTimeMillis().
            val f = DashRuntime.frame(System.currentTimeMillis())
            canvas.drawColor(Color.BLACK)
            val save = canvas.save()
            canvas.translate(vp.dx, vp.dy)
            canvas.scale(vp.scale, vp.scale)
            canvas.clipRect(0f, 0f, Viewport.LOGICAL_W, Viewport.LOGICAL_H)
            r.draw(canvas, f)
            canvas.restoreToCount(save)
        } finally {
            try {
                s.unlockCanvasAndPost(canvas)
                failureLog.success()
            } catch (e: IllegalArgumentException) {
                failureLog.failure("unlockCanvasAndPost", e)
            } catch (e: IllegalStateException) {
                failureLog.failure("unlockCanvasAndPost", e)
            }
        }
    }

    private companion object {
        const val FRAME_MS = 33L
    }
}
