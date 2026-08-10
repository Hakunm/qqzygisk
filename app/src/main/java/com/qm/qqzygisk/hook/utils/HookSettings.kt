package com.qm.qqzygisk.hook.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

internal object HookSettings {
    private const val PREFERENCES_NAME = "qqzygisk_hook_settings"

    @Volatile
    private var preferences: SharedPreferences? = null

    private val values = ConcurrentHashMap<String, Boolean>()
    private val defaults = ConcurrentHashMap<String, Boolean>()

    @Synchronized
    fun initialize(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences = prefs
        defaults.forEach { (key, defaultValue) ->
            values[key] = prefs.getBoolean(key, defaultValue)
        }
    }

    fun isEnabled(key: String, defaultValue: Boolean = true): Boolean {
        defaults.putIfAbsent(key, defaultValue)
        return values[key]
            ?: preferences?.getBoolean(key, defaultValue)?.also { values[key] = it }
            ?: defaultValue
    }

    fun setEnabled(key: String, enabled: Boolean) {
        values[key] = enabled
        preferences?.edit()?.putBoolean(key, enabled)?.apply()
    }
}
