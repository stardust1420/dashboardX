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
import com.karan.dashboardx.render.FrameModel
import com.karan.dashboardx.render.GaugeRenderer
import com.karan.dashboardx.render.Viewport
import com.karan.dashboardx.render.rendererFor
import java.util.EnumMap

/**
 * Draws the current [style] into the host's Surface at ~30 fps on a dedicated render thread,
 * fitted to the whole surface: full screen, with the host's Sound button floating on top.
 * A tap anywhere on the surface calls [onTap] (hosts with Car API level 5+).
 *
 * Threading: surface and lifecycle callbacks arrive on the main thread; frames are drawn on
 * [thread]. All loop start/stop decisions run on the render thread, so there is never more than
 * one frame chain. [lock] keeps surface teardown from racing a frame in progress.
 */
class SurfaceRenderer(carContext: CarContext) : SurfaceCallback, DefaultLifecycleObserver {
    /** Set by the Style action; read once per frame on the render thread. */
    @Volatile var style: Style = Style.RACING

    /** Called on the render thread with each frame's model, before it is drawn (the engine sound feed). */
    @Volatile var onFrame: ((FrameModel) -> Unit)? = null

    /** Called on the main thread when the user taps the surface (the style switch). */
    var onTap: (() -> Unit)? = null

    private val fonts = Fonts(carContext)
    private val renderers = EnumMap<Style, GaugeRenderer>(Style::class.java) // render thread only

    private val thread = HandlerThread("DashCarRender").apply { start() }
    private val handler = Handler(thread.looper)

    private val lock = Any()
    private var surface: Surface? = null // guarded by lock
    @Volatile private var viewport = Viewport.fitSurface(1, 1)

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
        val w = surfaceContainer.width
        val h = surfaceContainer.height
        DashRuntime.diag.add("car: surface ${w}x$h @${surfaceContainer.dpi}dpi")
        viewport = Viewport.fitSurface(w, h)
        startLoop()
    }

    /** Logged only: the gauges fill the whole surface, under the action strip. */
    override fun onVisibleAreaChanged(visibleArea: Rect) {
        DashRuntime.diag.add("car: visible area $visibleArea")
    }

    /** Logged only, like [onVisibleAreaChanged]. */
    override fun onStableAreaChanged(stableArea: Rect) {
        DashRuntime.diag.add("car: stable area $stableArea")
    }

    override fun onClick(x: Float, y: Float) {
        onTap?.invoke()
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
        var drew = false
        try {
            canvas.drawColor(Color.BLACK)
            val save = canvas.save()
            try {
                // Wall clock: CarState readings are stamped with System.currentTimeMillis().
                val f = DashRuntime.frame(System.currentTimeMillis())
                onFrame?.invoke(f)
                canvas.translate(vp.dx, vp.dy)
                canvas.scale(vp.scale, vp.scale)
                canvas.clipRect(0f, 0f, Viewport.LOGICAL_W, Viewport.LOGICAL_H)
                r.draw(canvas, f)
                drew = true
            } catch (e: Exception) {
                // A bug in one frame must not kill the render thread; log it (rate-limited) and
                // post the black frame so lock/unlock stay balanced. The next frame retries.
                failureLog.failure("draw", e)
            } finally {
                canvas.restoreToCount(save)
            }
        } finally {
            try {
                s.unlockCanvasAndPost(canvas)
                if (drew) failureLog.success()
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
