package com.karan.dashboardx.obd

import android.content.SharedPreferences

/** Minimal in-memory SharedPreferences for JVM tests. */
class FakePrefs : SharedPreferences {
    private val m = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = HashMap(m)
    override fun getString(k: String, d: String?) = m[k] as? String ?: d
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(k: String, d: MutableSet<String>?) = m[k] as? MutableSet<String> ?: d
    override fun getInt(k: String, d: Int) = m[k] as? Int ?: d
    override fun getLong(k: String, d: Long) = m[k] as? Long ?: d
    override fun getFloat(k: String, d: Float) = m[k] as? Float ?: d
    override fun getBoolean(k: String, d: Boolean) = m[k] as? Boolean ?: d
    override fun contains(k: String) = m.containsKey(k)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false
        override fun putString(k: String, v: String?) = apply { pending[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { pending[k] = v }
        override fun putInt(k: String, v: Int) = apply { pending[k] = v }
        override fun putLong(k: String, v: Long) = apply { pending[k] = v }
        override fun putFloat(k: String, v: Float) = apply { pending[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { pending[k] = v }
        override fun remove(k: String) = apply { removed += k }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) m.clear()
            removed.forEach { m.remove(it) }
            m.putAll(pending)
        }
    }
}
