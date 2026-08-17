package com.qm.qqzygisk.hook.app.base

data class SettingData(
    val key: String,
    val name: String,
    val description: String?,
    val defaultEnabled: Boolean,
)

abstract class BaseSetting {
    abstract val key: String
    abstract val name: String
    open val description: String? = null
    open val defaultEnabled: Boolean = false

    open val isShow: Boolean = true

    fun toSettingData() = SettingData(key, name, description, defaultEnabled)
}
