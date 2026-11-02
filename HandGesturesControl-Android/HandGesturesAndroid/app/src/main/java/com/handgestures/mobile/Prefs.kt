package com.handgestures.mobile

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private var sp: SharedPreferences? = null
    fun init(c: Context) {
        if (sp == null) sp = c.applicationContext.getSharedPreferences("hg_prefs", Context.MODE_PRIVATE)
    }
    private val p: SharedPreferences get() = sp!!

    var gain: Float
        get() = p.getFloat("gain", 1.5f)
        set(v) { p.edit().putFloat("gain", v).apply() }
    var smooth: Float
        get() = p.getFloat("smooth", 0.75f)
        set(v) { p.edit().putFloat("smooth", v).apply() }
    var pinchStrict: Float
        get() = p.getFloat("pinchStrict", 0.5f)
        set(v) { p.edit().putFloat("pinchStrict", v).apply() }
    var showPointer: Boolean
        get() = p.getBoolean("showPointer", true)
        set(v) { p.edit().putBoolean("showPointer", v).apply() }
    var showMini: Boolean
        get() = p.getBoolean("showMini", true)
        set(v) { p.edit().putBoolean("showMini", v).apply() }
    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) { p.edit().putBoolean("haptics", v).apply() }

    fun map(g: String): String = p.getString("m_$g", DEFAULT_MAP[g] ?: "none") ?: "none"
    fun setMap(g: String, v: String) { p.edit().putString("m_$g", v).apply() }
    fun touchMap(g: String): String = p.getString("t_$g", DEFAULT_TOUCH[g] ?: "none") ?: "none"
    fun setTouchMap(g: String, v: String) { p.edit().putString("t_$g", v).apply() }
    fun pinch(f: String): String = p.getString("p_$f", DEFAULT_PINCH[f] ?: "none") ?: "none"
    fun setPinch(f: String, v: String) { p.edit().putString("p_$f", v).apply() }
    fun reset() { p.edit().clear().apply() }
}
