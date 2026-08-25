package com.qm.qqzygisk.hook.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.qm.qqzygisk.ui.activity.SettingActivity
import com.qm.qqzygisk.hook.parasitic.AppLifecycle
import com.qm.qqzygisk.hook.parasitic.AppParasitics

fun Any.set(key: String, value: Any?) {
    asResolver()
        .firstField {
            name = key
            superclass()
        }
        .set(value)
}

fun <T> Any.get(key: String): T? {
    return asResolver()
        .firstField { name = key }
        .get<T>()
}

fun Context.startModuleSettings() {
    val intent = Intent(this, SettingActivity::class.java)
    if (this !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
        .onFailure { first ->
            Log.error("startModuleSettings", first)
            runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                (applicationContext ?: this).startActivity(intent)
            }.onFailure { Log.error("startModuleSettings retry", it) }
        }
}

/**
 * 获取数组内容依次列出的字符串表示
 * @return [String]
 */
internal inline fun <reified T> Array<out T>.value() = if (isNotEmpty()) {
    var value = ""
    forEach { value += "$it, " }
    "[${value.trim().let { it.substring(0, it.lastIndex) }}]"
} else "[]"

/** 通过指定Activity注册新的模块Activity界面 */
fun Context.registerModuleAppActivities(proxy: Any? = null) = AppParasitics.registerModuleAppActivities(context = this, proxy)

fun Context.injectModuleAppResources() = resources?.injectModuleAppResources()

fun Resources.injectModuleAppResources() = AppParasitics.injectModuleAppResources(hostResources = this)

/**
 * 监听当前 Hook APP 生命周期装载事件
 *
 * @param isOnFailureThrowToApp 是否在发生异常时将异常抛出给宿主 - 默认是 (仅在第一个 Hooker 设置有效)
 * @param initiate 方法体
 */
inline fun onAppLifecycle(isOnFailureThrowToApp: Boolean = true, initiate: AppLifecycle.() -> Unit) =
    AppLifecycle(isOnFailureThrowToApp).apply(initiate).build()