package com.qm.qqzygisk.hook.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.qm.qqzygisk.hook.app.base.SettingData
import java.util.concurrent.ConcurrentHashMap

internal object HookSettings {
    private const val PREFERENCES_NAME = "qqzygisk_hook_settings"

    @Volatile
    private var preferences: SharedPreferences? = null

    @Volatile
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val values = ConcurrentHashMap<String, Boolean>()
    private val defaults = ConcurrentHashMap<String, Boolean>()

    @Synchronized
    fun initialize(context: Context) {
        if (preferences != null) return

        // Application.attach() runs before applicationContext is initialized on some QQ processes.
        val appContext = context.applicationContext ?: context
        val sharedPreferences = appContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        preferences = sharedPreferences
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key != null) {
                defaults[key]?.let { defaultValue ->
                    values[key] = prefs.getBoolean(key, defaultValue)
                } ?: prefs.all[key]?.let { value ->
                    if (value is Boolean) values[key] = value
                }
            }
        }
        preferenceListener = listener
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        defaults.forEach { (key, defaultValue) ->
            values[key] = sharedPreferences.getBoolean(key, defaultValue)
        }
    }

    fun isEnabled(key: String, defaultValue: Boolean = false): Boolean {
        defaults.putIfAbsent(key, defaultValue)
        values[key]?.let { return it }

        return preferences?.getBoolean(key, defaultValue)
            ?.also { values[key] = it }
            ?: defaultValue
    }

    fun setEnabled(key: String, enabled: Boolean) {
        values[key] = enabled
        preferences?.edit(commit = true) { putBoolean(key, enabled) }
    }

    fun dump(settings: Iterable<SettingData>): String {
        return settings.joinToString(
            prefix = "{",
            postfix = "}",
            separator = ", ",
        ) { setting ->
            "${setting.key}=${isEnabled(setting.key, setting.defaultEnabled)}"
        }
    }
}
