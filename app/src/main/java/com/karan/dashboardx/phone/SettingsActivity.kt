package com.karan.dashboardx.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.karan.dashboardx.DashRuntime

/**
 * Phone settings, built in code. Every change saves to [com.karan.dashboardx.settings.Settings]
 * at once. Only the adapter choice (in [AdapterPicker]) and the Demo switch call
 * [DashRuntime.restart]; every other change calls [DashRuntime.reloadSettings], which keeps the
 * OBD link and the trip average. Number fields validate on IME Done, on focus loss and
 * when the screen is left; invalid input shows an error and is not saved.
 */
class SettingsActivity : Activity() {
    private lateinit var picker: AdapterPicker
    private lateinit var adapterSummary: TextView
    private lateinit var demoSwitch: Switch
    private val fields = ArrayList<NumberField>()

    private val settings get() = DashRuntime.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DashRuntime.init(this)
        picker = AdapterPicker(this) { refresh() }
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        fields.forEach { it.commit() } // keep a typed-but-not-confirmed valid value
        picker.pause() // no BLE scan while this screen is not in front
        super.onPause()
    }

    override fun onDestroy() {
        picker.dispose()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (!picker.onRequestPermissionsResult(requestCode, grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!picker.onActivityResult(requestCode, resultCode)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    /** Re-reads values that can change behind this screen (the picker turns demo mode off). */
    private fun refresh() {
        val s = settings
        adapterSummary.text = AdapterDevices.savedSummary(s.adapterName, s.adapterAddress, s.adapterIsBle)
        if (demoSwitch.isChecked != s.demoMode) demoSwitch.isChecked = s.demoMode
    }

    /** Logs and applies a change. [restart] only for a change of data source (Demo switch). */
    private fun changed(what: String, restart: Boolean = false) {
        DashRuntime.diag.add("settings: $what")
        if (restart) DashRuntime.restart(this) else DashRuntime.reloadSettings(this)
    }

    private fun buildUi(): View {
        val s = settings
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
            // Lets an EditText lose focus (and so validate) without focus jumping to another field.
            isFocusableInTouchMode = true
        }

        // Header
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("DashboardX settings", 24f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("Done") { finish() })
        })
        col.addView(text("Changes apply immediately.", 14f, MUTED))

        // Adapter
        section(col, "OBD adapter")
        adapterSummary = text("", 17f, Color.WHITE)
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(adapterSummary, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("Choose") { picker.start() })
        })

        // Demo mode
        section(col, "Demo mode")
        demoSwitch = Switch(this).apply {
            text = "Show simulated data instead of the car"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            isChecked = s.demoMode
            setOnCheckedChangeListener { _, on ->
                if (on != settings.demoMode) {
                    settings.demoMode = on
                    changed("demo mode ${if (on) "on" else "off"}", restart = true)
                }
            }
        }
        col.addView(demoSwitch, LinearLayout.LayoutParams(-1, -2))

        // Volume
        section(col, "Engine sound volume")
        val volLabel = text("", 16f, MUTED)
        fun showVol(p: Int) { volLabel.text = "$p%" }
        val seek = SeekBar(this).apply {
            max = 100
            progress = (s.soundVolume * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    showVol(p)
                    if (fromUser) settings.soundVolume = p / 100f // the synth re-reads it live
                }
                private var dragStart = -1
                override fun onStartTrackingTouch(sb: SeekBar) { dragStart = sb.progress }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    settings.soundVolume = sb.progress / 100f
                    if (sb.progress != dragStart) changed("sound volume ${sb.progress}%")
                }
            })
        }
        showVol(seek.progress)
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(seek, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(volLabel, LinearLayout.LayoutParams(dp(64), -2))
        })

        // Fuel calibration
        section(col, "Fuel calibration (${SettingsInput.CAL_MIN}–${SettingsInput.CAL_MAX})")
        fields += NumberField(col, decimal = true, initial = SettingsInput.formatCalibration(s.fuelCalibration)) { t ->
            when (val p = SettingsInput.calibration(t)) {
                is Parsed.Err -> p.message
                is Parsed.Ok -> {
                    if (p.value != settings.fuelCalibration) {
                        settings.fuelCalibration = p.value
                        changed("fuel calibration ${p.value}")
                    }
                    null
                }
            }
        }

        // Displacement
        section(col, "Engine displacement (L)")
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val ids = SettingsInput.DISPLACEMENTS.map { litres ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "$litres L"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding(dp(8), dp(8), dp(24), dp(8))
            }
            group.addView(rb)
            rb.id
        }
        group.check(ids[SettingsInput.displacementIndex(s.displacementL)])
        group.setOnCheckedChangeListener { _, checkedId ->
            val i = ids.indexOf(checkedId)
            if (i >= 0) {
                val litres = SettingsInput.DISPLACEMENTS[i]
                if (litres != settings.displacementL) {
                    settings.displacementL = litres
                    changed("displacement $litres L")
                }
            }
        }
        col.addView(group)

        // Redline
        section(col, "Redline RPM (${SettingsInput.REDLINE_MIN}–${SettingsInput.REDLINE_MAX})")
        fields += NumberField(col, decimal = false, initial = s.redlineRpm.toString()) { t ->
            when (val p = SettingsInput.redline(t)) {
                is Parsed.Err -> p.message
                is Parsed.Ok -> {
                    if (p.value != settings.redlineRpm) {
                        settings.redlineRpm = p.value
                        changed("redline ${p.value}")
                    }
                    null
                }
            }
        }

        // Coolant
        section(col, "Coolant warning °C (${SettingsInput.COOLANT_MIN}–${SettingsInput.COOLANT_MAX})")
        fields += NumberField(col, decimal = false, initial = s.coolantWarnC.toString()) { t ->
            when (val p = SettingsInput.coolant(t)) {
                is Parsed.Err -> p.message
                is Parsed.Ok -> {
                    if (p.value != settings.coolantWarnC) {
                        settings.coolantWarnC = p.value
                        changed("coolant warning ${p.value}")
                    }
                    null
                }
            }
        }

        // Diagnostics
        section(col, "Diagnostics")
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Connection status, supported PIDs and the adapter log", 16f, MUTED), LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("Open") { startActivity(Intent(this@SettingsActivity, DiagnosticsActivity::class.java)) })
        })

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(col, -1, -2)
        }
    }

    /**
     * A labelled number EditText. [save] validates and saves the text, returning an error message
     * (nothing saved) or null (saved, or unchanged).
     */
    private inner class NumberField(parent: LinearLayout, decimal: Boolean, initial: String, val save: (String) -> String?) {
        val edit = EditText(this@SettingsActivity).apply {
            setText(initial)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSelectAllOnFocus(true)
        }
        val error = text("", 14f, ERROR).apply { visibility = View.GONE }

        init {
            parent.addView(edit, LinearLayout.LayoutParams(dp(200), -2))
            parent.addView(error)
            edit.setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    if (commit()) {
                        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
                        v.clearFocus()
                    }
                    true
                } else {
                    false
                }
            }
            edit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
        }

        /** Validates and saves; returns true when the value was valid. */
        fun commit(): Boolean {
            val msg = save(edit.text.toString())
            error.text = msg ?: ""
            error.visibility = if (msg == null) View.GONE else View.VISIBLE
            edit.error = msg
            return msg == null
        }
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(text(title, 15f, ACCENT).apply { setPadding(0, dp(22), 0, dp(6)) })
    }

    private fun text(s: String, sp: Float, color: Int) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setOnClickListener { onClick() }
    }

    private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val BG = 0xFF101418.toInt()
        const val MUTED = 0xFF9AA5AE.toInt()
        const val ACCENT = 0xFF4FC3F7.toInt()
        const val ERROR = 0xFFFF6E6E.toInt()
    }
}
