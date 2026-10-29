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
import com.karan.dashboardx.data.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Phone screen: the live dashboard, landscape and immersive, with a gear button (top right) that
 * opens [SettingsActivity]. A tap anywhere else cycles the style (handled by [DashView]).
 * While the dashboard needs setup, the gear also reads "Set up" so first launch is obvious.
 */
class MainActivity : Activity() {
    private lateinit var gear: TextView
    private val scope: CoroutineScope = MainScope()
    private var statusJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DashRuntime.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        root.addView(DashView(this), FrameLayout.LayoutParams(-1, -1))
        gear = TextView(this).apply {
            text = GEAR
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            minWidth = dp(56)
            minHeight = dp(56)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(0x99202020.toInt())
                setStroke(dp(1), 0x66FFFFFF)
            }
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
        statusJob?.cancel()
        statusJob = null
        DashRuntime.release()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
