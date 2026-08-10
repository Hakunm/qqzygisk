package com.qm.qqzygisk.hook.utils

import com.v7878.zygisk.ZygoteLoader
import java.io.File
import java.util.Properties

internal object ModuleConfig {
    private const val CONFIG_FILE = "config.properties"

    @Volatile
    private var values: Map<String, String> = emptyMap()

    fun load() {
        val moduleDir = ZygoteLoader.getModuleDir() ?: return
        val config = File(moduleDir, CONFIG_FILE)
        if (!config.isFile) {
            values = emptyMap()
            return
        }

        val properties = Properties()
        config.reader(Charsets.UTF_8).use(properties::load)
        values = properties.stringPropertyNames().associateWith { properties.getProperty(it) }
    }

    operator fun get(key: String): String? = values[key]

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return values[key]?.toBooleanStrictOrNull() ?: defaultValue
    }
}
