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
 * The car dashboard: a [NavigationTemplate] whose map area is our full-screen [SurfaceRenderer].
 * Each style draws its own Sound toggle; a tap on it switches the engine sound, a tap on the
 * drive-mode selector steps the mode, a tap anywhere else on the dash cycles and persists the
 * style, like on the phone. The template must carry one action, so it gets a blank one
 * ([CarIcons.blank]) that does nothing.
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

    init {
        lifecycle.addObserver(this)
        lifecycle.addObserver(renderer)
    }

    override fun onCreate(owner: LifecycleOwner) {
        DashRuntime.acquire(carContext)
        renderer.style = DashRuntime.settings.style
        val controller = EngineSoundController(carContext)
        sound = controller
        // The audio thread switched itself off (posted on the main thread): show the toggle as off.
        controller.onStateChanged = { if (sound === controller) renderer.soundOn = controller.enabled }
        renderer.onFrame = { f -> controller.update(f.rpm, f.throttle) }
        renderer.onSoundTap = {
            controller.enabled = !controller.enabled
            renderer.soundOn = controller.enabled
        }
        renderer.onTap = {
            val next = renderer.style.next()
            renderer.style = next
            DashRuntime.settings.style = next
        }
        DashRuntime.onChime = chimeHook
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
    }

    override fun onStop(owner: LifecycleOwner) {
        sound?.enabled = false
        renderer.soundOn = false
    }

    override fun onDestroy(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        renderer.onFrame = null
        renderer.onTap = null
        renderer.onSoundTap = null
        sound?.onStateChanged = null
        sound?.release()
        sound = null
        if (DashRuntime.onChime === chimeHook) DashRuntime.onChime = null
        DashRuntime.release()
    }

    override fun onGetTemplate(): Template {
        // NavigationTemplate requires an action strip with at least one action.
        val blank = Action.Builder().setIcon(CarIcons.blank).setOnClickListener {}.build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(blank).build())
            .build()
    }
}
