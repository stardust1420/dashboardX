package com.karan.dashboardx.phone

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.karan.dashboardx.DashRuntime

/**
 * Diagnostics: connection status, supported/unsupported PIDs and the adapter traffic log
 * ([DashRuntime.diag]), refreshed every second while visible with the newest line at the bottom.
 * Share sends the whole log as text.
 *
 * Holds the runtime while started, so the adapter keeps talking (and logging) while this screen
 * is the only one visible.
 */
class DiagnosticsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var pidView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private var shownCount = -1
    private var shownLast: String? = null

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DashRuntime.init(this)
        val pad = dp(16)
        statusView = text(18f, Color.WHITE)
        pidView = text(15f, MUTED)
        logView = text(12f, 0xFFD0D8DE.toInt()).apply { typeface = Typeface.MONOSPACE }
        scroll = ScrollView(this).apply { addView(logView, -1, -2) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(22f, Color.WHITE).apply { text = "Diagnostics" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("Share") { share() })
            addView(button("Done") { finish() })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(pad, pad, pad, pad)
            addView(header)
            addView(statusView)
            addView(pidView.apply { setPadding(0, dp(4), 0, dp(8)) })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        DashRuntime.acquire(this)
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        super.onPause()
    }

    override fun onStop() {
        DashRuntime.release()
        super.onStop()
    }

    private fun refresh() {
        val status = DashRuntime.status.value
        statusView.text = DiagReport.statusLine(status)
        pidView.text = DiagReport.pidLines(status, DashRuntime.state.value.unsupported).joinToString("\n")

        val lines = DashRuntime.diag.lines()
        if (lines.size == shownCount && lines.lastOrNull() == shownLast) return
        // Follow the tail only if the user has not scrolled up to read something.
        val atBottom = shownCount < 0 || scroll.scrollY + scroll.height >= logView.height - dp(48)
        shownCount = lines.size
        shownLast = lines.lastOrNull()
        logView.text = if (lines.isEmpty()) "(log is empty)" else lines.joinToString("\n")
        if (atBottom) scroll.post { scroll.scrollTo(0, logView.height) }
    }

    private fun share() {
        val text = DiagReport.shareText(
            DashRuntime.status.value,
            DashRuntime.state.value.unsupported,
            DashRuntime.diag.lines(),
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DashboardX diagnostics")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(send, "Share diagnostics log"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can share text", Toast.LENGTH_SHORT).show()
        }
    }

    private fun text(sp: Float, color: Int) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val REFRESH_MS = 1000L
        const val BG = 0xFF101418.toInt()
        const val MUTED = 0xFF9AA5AE.toInt()
    }
}
