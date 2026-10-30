package com.karan.dashboardx.car

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.karan.dashboardx.DashRuntime
import com.karan.dashboardx.alerts.Warning
import com.karan.dashboardx.audio.Chime
import com.karan.dashboardx.audio.EngineSoundController

/**
 * The car dashboard: a [NavigationTemplate] whose map area is our full-screen [SurfaceRenderer],
 * with a single Sound (on/off) action. A tap on the drive-mode selector steps the mode; a tap
 * anywhere else on the dash cycles and persists the style, like on the phone.
 * Holds the [DashRuntime] from create to destroy.
 *
 * Audio: owns one [EngineSoundController] (create to destroy), fed every drawn frame through
 * [SurfaceRenderer.onFrame]. The engine sound is off at every start and is switched off whenever
 * the screen stops, so it never plays while the car screen is not visible. Warning chimes play
 * through [DashRuntime.onChime] whatever the Sound setting.
 */
class DashScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    private val renderer = SurfaceRenderer(carContext)

    private var sound: EngineSoundController? = null // main thread
    private val chimeHook: (Warning) -> Unit = { w -> Chime.play(carContext, w) }

    /** The template shows the sound as on but onStop turned it off: refresh the icon on next start. */
    private var iconStale = false

    init {
        lifecycle.addObserver(this)
        lifecycle.addObserver(renderer)
    }

    override fun onCreate(owner: LifecycleOwner) {
        DashRuntime.acquire(carContext)
        renderer.style = DashRuntime.settings.style
        val controller = EngineSoundController(carContext)
        sound = controller
        // The audio thread switched itself off (posted on the main thread): show the icon as off.
        controller.onStateChanged = { if (sound === controller) invalidate() }
        renderer.onFrame = { f -> controller.update(f.rpm, f.throttle) }
        renderer.onTap = {
            val next = renderer.style.next()
            renderer.style = next
            DashRuntime.settings.style = next
        }
        DashRuntime.onChime = chimeHook
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (iconStale) {
            iconStale = false
            invalidate()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        val s = sound ?: return
        if (s.enabled) {
            s.enabled = false
            iconStale = true
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        renderer.onFrame = null
        renderer.onTap = null
        sound?.onStateChanged = null
        sound?.release()
        sound = null
        if (DashRuntime.onChime === chimeHook) DashRuntime.onChime = null
        DashRuntime.release()
    }

    override fun onGetTemplate(): Template {
        val sound = Action.Builder()
            .setIcon(if (sound?.enabled == true) CarIcons.soundOn else CarIcons.soundOff)
            .setOnClickListener {
                sound?.let { it.enabled = !it.enabled }
                invalidate()
            }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(sound).build())
            .build()
    }
}
