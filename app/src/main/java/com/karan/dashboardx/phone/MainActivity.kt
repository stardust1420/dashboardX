package com.karan.dashboardx.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.karan.dashboardx.DashRuntime
import com.karan.dashboardx.audio.EngineSoundController
import com.karan.dashboardx.data.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Phone screen: the live dashboard, landscape and immersive, with a gear button (top right) that
 * opens [SettingsActivity]. The style's own Sound toggle switches the engine sound; a tap anywhere
 * else cycles the style (both handled by [DashView]). While the dashboard needs setup, the gear also
 * reads "Set up" so first launch is obvious.
 *
 * Audio: owns one [EngineSoundController] (create to destroy), fed every drawn frame through
 * [DashView.onFrame]. As on the car screen, the engine sound is off at every start and is switched
 * off whenever the activity stops.
 */
class MainActivity : Activity() {
    private lateinit var gear: TextView
    private lateinit var dash: DashView
    private var sound: EngineSoundController? = null // main thread
    private val scope: CoroutineScope = MainScope()
    private var statusJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DashRuntime.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Draw under the camera cutout too, so the dash runs edge to edge.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val root = FrameLayout(this)
        dash = DashView(this)
        root.addView(dash, FrameLayout.LayoutParams(-1, -1))
        val controller = EngineSoundController(this)
        sound = controller
        // The audio thread switched itself off (posted on the main thread): show the toggle as off.
        controller.onStateChanged = { if (sound === controller) dash.soundOn = controller.enabled }
        dash.onFrame = { f -> controller.update(f.rpm, f.throttle) }
        dash.onSoundTap = {
            controller.enabled = !controller.enabled
            dash.soundOn = controller.enabled
        }
        gear = TextView(this).apply {
            text = GEAR
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            minWidth = dp(56)
            minHeight = dp(56)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = buttonBackground()
            contentDescription = "Settings"
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(gear, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(16); marginEnd = dp(16)
        })
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        DashRuntime.acquire(this)
        statusJob = scope.launch {
            DashRuntime.status.collect { s ->
                gear.text = if (s == ConnectionStatus.IDLE_NEEDS_SETUP) "$GEAR  Set up" else GEAR
            }
        }
    }

    override fun onStop() {
        sound?.enabled = false
        dash.soundOn = false
        statusJob?.cancel()
        statusJob = null
        DashRuntime.release()
        super.onStop()
    }

    override fun onDestroy() {
        sound?.onStateChanged = null
        sound?.release()
        sound = null
        dash.onSoundTap = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buttonBackground() = GradientDrawable().apply {
        cornerRadius = dp(28).toFloat()
        setColor(0x99202020.toInt())
        setStroke(dp(1), 0x66FFFFFF)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private companion object {
        const val GEAR = "\u2699\uFE0E" // text presentation, not emoji
    }
}
