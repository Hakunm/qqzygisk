package com.qm.qqzygisk.hook.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import androidx.core.content.edit
import com.qm.qqzygisk.hook.app.base.SettingData
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal object HookSettings {
    private const val PREFERENCES_NAME = "qqzygisk_hook_settings"
    private const val EXTERNAL_SYNC_INTERVAL_MS = 2_000L

    @Volatile
    private var preferences: SharedPreferences? = null

    @Volatile
    private var preferencesFile: File? = null

    @Volatile
    private var lastSyncedModified = -1L

    @Volatile
    private var lastSyncCheckAt = 0L

    @Volatile
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val values = ConcurrentHashMap<String, Boolean>()
    private val defaults = ConcurrentHashMap<String, Boolean>()
    private val stringValues = ConcurrentHashMap<String, String>()
    private val stringDefaults = ConcurrentHashMap<String, String>()
    private val intValues = ConcurrentHashMap<String, Int>()
    private val intDefaults = ConcurrentHashMap<String, Int>()

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
        preferencesFile = runCatching {
            File(File(appContext.dataDir, "shared_prefs"), "$PREFERENCES_NAME.xml")
        }.getOrNull()
        lastSyncedModified = preferencesFile?.takeIf { it.isFile }?.lastModified() ?: -1L
        lastSyncCheckAt = System.currentTimeMillis()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key != null) {
                when {
                    defaults.containsKey(key) -> {
                        values[key] = prefs.getBoolean(key, defaults.getValue(key))
                    }
                    stringDefaults.containsKey(key) -> {
                        stringValues[key] = prefs.getString(key, stringDefaults.getValue(key))
                            ?: stringDefaults.getValue(key)
                    }
                    intDefaults.containsKey(key) -> {
                        intValues[key] = prefs.getInt(key, intDefaults.getValue(key))
                    }
                    else -> prefs.all[key]?.let { value ->
                        when (value) {
                            is Boolean -> values[key] = value
                            is String -> stringValues[key] = value
                            is Int -> intValues[key] = value
                        }
                    }
                }
            }
        }
        preferenceListener = listener
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        defaults.forEach { (key, defaultValue) ->
            values[key] = sharedPreferences.getBoolean(key, defaultValue)
        }
        stringDefaults.forEach { (key, defaultValue) ->
            stringValues[key] = sharedPreferences.getString(key, defaultValue) ?: defaultValue
        }
        intDefaults.forEach { (key, defaultValue) ->
            intValues[key] = sharedPreferences.getInt(key, defaultValue)
        }
    }

    /** 调用方须先初始化设置并持有监听器；用于已显示界面的开关即时刷新。 */
    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        ensureInitialized()
        checkNotNull(preferences) { "Hook settings are not initialized" }
            .registerOnSharedPreferenceChangeListener(listener)
    }

    fun isEnabled(key: String, defaultValue: Boolean = false): Boolean {
        defaults.putIfAbsent(key, defaultValue)
        ensureInitialized()
        syncExternalChangesIfNeeded()
        values[key]?.let { return it }

        return preferences?.getBoolean(key, defaultValue)
            ?.also { values[key] = it }
            ?: defaultValue
    }

    fun setEnabled(key: String, enabled: Boolean) {
        values[key] = enabled
        preferences?.edit(commit = true) { putBoolean(key, enabled) }
        markLocalWrite()
    }

    fun getString(key: String, defaultValue: String): String {
        stringDefaults.putIfAbsent(key, defaultValue)
        ensureInitialized()
        syncExternalChangesIfNeeded()
        stringValues[key]?.let { return it }

        return preferences?.getString(key, defaultValue)
            ?.also { stringValues[key] = it }
            ?: defaultValue
    }

    fun setString(key: String, value: String) {
        stringValues[key] = value
        preferences?.edit(commit = true) { putString(key, value) }
        markLocalWrite()
    }

    fun getInt(key: String, defaultValue: Int): Int {
        intDefaults.putIfAbsent(key, defaultValue)
        ensureInitialized()
        syncExternalChangesIfNeeded()
        intValues[key]?.let { return it }

        return preferences?.getInt(key, defaultValue)
            ?.also { intValues[key] = it }
            ?: defaultValue
    }

    fun setInt(key: String, value: Int) {
        intValues[key] = value
        preferences?.edit(commit = true) { putInt(key, value) }
        markLocalWrite()
    }

    /** 兜底初始化：早于 attachBaseContext 到达的回调也能在宿主 Application 可用后立即读到真实配置。 */
    private fun ensureInitialized() {
        if (preferences != null) return
        val context = runCatching { AppParasitics.appContext }.getOrNull() ?: return
        runCatching { initialize(context) }
    }

    private fun markLocalWrite() {
        val modified = preferencesFile?.takeIf { it.isFile }?.lastModified() ?: return
        lastSyncedModified = modified
        lastSyncCheckAt = System.currentTimeMillis()
    }

    /**
     * 宿主多进程场景下（例如主进程切换开关，子进程已缓存旧值），`SharedPreferences` 不会跨进程触发
     * 监听器。这里以 2 秒节流检查底层 XML 的 mtime，仅在文件确实被外部改写时刷新内存快照。
     */
    private fun syncExternalChangesIfNeeded() {
        val file = preferencesFile ?: return
        val now = System.currentTimeMillis()
        if (now - lastSyncCheckAt < EXTERNAL_SYNC_INTERVAL_MS) return
        lastSyncCheckAt = now
        val modified = if (file.isFile) file.lastModified() else return
        if (modified == lastSyncedModified) return
        synchronized(this) {
            if (modified == lastSyncedModified) return
            val parsed = runCatching { parsePrefsXml(file) }.getOrNull() ?: return
            lastSyncedModified = modified
            parsed.booleans.forEach { (k, v) -> values[k] = v }
            parsed.strings.forEach { (k, v) -> stringValues[k] = v }
            parsed.ints.forEach { (k, v) -> intValues[k] = v }
        }
    }

    private class ParsedPrefs(
        val booleans: Map<String, Boolean>,
        val strings: Map<String, String>,
        val ints: Map<String, Int>,
    )

    private fun parsePrefsXml(file: File): ParsedPrefs {
        val booleans = HashMap<String, Boolean>()
        val strings = HashMap<String, String>()
        val ints = HashMap<String, Int>()
        file.inputStream().use { stream ->
            val parser = Xml.newPullParser()
            parser.setInput(stream, "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = parser.getAttributeValue(null, "name")
                    if (!name.isNullOrEmpty()) {
                        when (parser.name) {
                            "boolean" -> parser.getAttributeValue(null, "value")
                                ?.toBooleanStrictOrNull()
                                ?.let { booleans[name] = it }
                            "int" -> parser.getAttributeValue(null, "value")
                                ?.toIntOrNull()
                                ?.let { ints[name] = it }
                            "string" -> strings[name] = parser.nextText()
                        }
                    }
                }
                event = parser.next()
            }
        }
        return ParsedPrefs(booleans, strings, ints)
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
