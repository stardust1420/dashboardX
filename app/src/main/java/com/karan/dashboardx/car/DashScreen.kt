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

/**
 * The car dashboard: a [NavigationTemplate] whose map area is our [SurfaceRenderer], with an
 * action strip of Style (cycle and persist the style) and Sound (on/off).
 * Holds the [DashRuntime] from create to destroy.
 */
class DashScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    private val renderer = SurfaceRenderer(carContext)

    /** Engine sound on/off; off at every start. TEMP: Task 11 replaces this with EngineSoundController. */
    private var soundOn = false

    init {
        lifecycle.addObserver(this)
        lifecycle.addObserver(renderer)
    }

    override fun onCreate(owner: LifecycleOwner) {
        // TEMP until Task 12's settings screen: an unconfigured car shows the demo, whichever
        // screen (phone or car) started the process.
        DashRuntime.demoWhenUnconfigured = true
        DashRuntime.acquire(carContext)
        renderer.style = DashRuntime.settings.style
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        DashRuntime.release()
    }

    override fun onGetTemplate(): Template {
        val style = Action.Builder()
            .setIcon(CarIcons.style)
            .setOnClickListener {
                val next = renderer.style.next()
                renderer.style = next
                DashRuntime.settings.style = next
                invalidate()
            }
            .build()
        val sound = Action.Builder()
            .setIcon(if (soundOn) CarIcons.soundOn else CarIcons.soundOff)
            .setOnClickListener {
                soundOn = !soundOn
                invalidate()
            }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(style).addAction(sound).build())
            .build()
    }
}
