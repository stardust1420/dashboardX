package com.karan.dashboardx.settings

import android.content.SharedPreferences
import com.karan.dashboardx.data.Style

val DEFAULT_STYLE = Style.RACING
const val DEFAULT_ADAPTER_IS_BLE = false
const val DEFAULT_DEMO_MODE = false
const val DEFAULT_SOUND_VOLUME = 0.7f
const val DEFAULT_FUEL_CALIBRATION = 1.0
const val DEFAULT_DISPLACEMENT_L = 1.591
const val DEFAULT_REDLINE_RPM = 6200
const val DEFAULT_COOLANT_WARN_C = 105

class Settings(private val prefs: SharedPreferences) {
    var style: Style
        get() = prefs.getString(K_STYLE, null)?.let { n -> Style.entries.firstOrNull { it.name == n } } ?: DEFAULT_STYLE
        set(v) { prefs.edit().putString(K_STYLE, v.name).apply() }

    var adapterAddress: String?
        get() = prefs.getString(K_ADDR, null)
        set(v) { prefs.edit().apply { if (v == null) remove(K_ADDR) else putString(K_ADDR, v) }.apply() }

    var adapterIsBle: Boolean
        get() = prefs.getBoolean(K_BLE, DEFAULT_ADAPTER_IS_BLE)
        set(v) { prefs.edit().putBoolean(K_BLE, v).apply() }

    var demoMode: Boolean
        get() = prefs.getBoolean(K_DEMO, DEFAULT_DEMO_MODE)
        set(v) { prefs.edit().putBoolean(K_DEMO, v).apply() }

    var soundVolume: Float
        get() = prefs.getFloat(K_VOL, DEFAULT_SOUND_VOLUME)
        set(v) { prefs.edit().putFloat(K_VOL, v.coerceIn(0f, 1f)).apply() }

    var fuelCalibration: Double
        get() = prefs.getString(K_CAL, null)?.toDoubleOrNull() ?: DEFAULT_FUEL_CALIBRATION
        set(v) { prefs.edit().putString(K_CAL, v.toString()).apply() }

    var displacementL: Double
        get() = prefs.getString(K_DISP, null)?.toDoubleOrNull() ?: DEFAULT_DISPLACEMENT_L
        set(v) { prefs.edit().putString(K_DISP, v.toString()).apply() }

    var redlineRpm: Int
        get() = prefs.getInt(K_REDLINE, DEFAULT_REDLINE_RPM)
        set(v) { prefs.edit().putInt(K_REDLINE, v).apply() }

    var coolantWarnC: Int
        get() = prefs.getInt(K_COOLANT, DEFAULT_COOLANT_WARN_C)
        set(v) { prefs.edit().putInt(K_COOLANT, v).apply() }

    private companion object {
        const val K_STYLE = "style"
        const val K_ADDR = "adapterAddress"
        const val K_BLE = "adapterIsBle"
        const val K_DEMO = "demoMode"
        const val K_VOL = "soundVolume"
        const val K_CAL = "fuelCalibration"
        const val K_DISP = "displacementL"
        const val K_REDLINE = "redlineRpm"
        const val K_COOLANT = "coolantWarnC"
    }
}
