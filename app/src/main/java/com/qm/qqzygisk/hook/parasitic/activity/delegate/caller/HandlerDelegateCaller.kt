package com.qm.qqzygisk.hook.parasitic.activity.delegate.caller

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.lazyClass
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.parasitic.activity.config.ActivityProxyStore
import java.lang.reflect.Field

/**
 * 代理当前 [Handler.Callback] 调用类
 */
internal object HandlerDelegateCaller {

    /** 启动 [Activity] */
    private const val LAUNCH_ACTIVITY = 100

    /** 执行事务处理 */
    private const val EXECUTE_TRANSACTION = 159

    private val ActivityThreadClass by lazyClass("android.app.ActivityThread")
    private val ClientTransactionClass by lazyClass("android.app.servertransaction.ClientTransaction")

    private val mExtrasResolver by lazy {
        Intent::class.resolve().optional(silent = true).firstFieldOrNull { name = "mExtras" }
    }

    /**
     * 调用代理的 [Handler.Callback.handleMessage] 方法
     * @param baseInstance 原始实例
     * @param msg 当前消息实例
     * @return [Boolean]
     */
    internal fun callHandleMessage(baseInstance: Handler.Callback?, msg: Message): Boolean {
        when (msg.what) {
            LAUNCH_ACTIVITY -> {
                val intentResolver = msg.obj.asResolver()
                    .optional(silent = true)
                    .firstFieldOrNull { name = "intent" }
                val intent = intentResolver?.get<Intent>()
                val mExtras = mExtrasResolver?.copy()?.of(intent)?.getQuietly<Bundle>()
                mExtras?.classLoader = AppParasitics.currentApplication?.classLoader
                ActivityProxyStore.consume(intent)?.let { intentResolver?.set(it) }
            }
            EXECUTE_TRANSACTION -> {
                transactionItems(msg.obj).forEach { item ->
                    if (!item.javaClass.name.contains("LaunchActivityItem")) return@forEach
                    val intentField = intentFieldOf(item) ?: return@forEach
                    val intent = runCatching { intentField.get(item) as? Intent }.getOrNull()
                    val mExtras = mExtrasResolver?.copy()?.of(intent)?.getQuietly<Bundle>()
                    mExtras?.classLoader = AppParasitics.currentApplication?.classLoader
                    val subIntent = ActivityProxyStore.consume(intent) ?: return@forEach
                    runCatching { intentField.set(item, subIntent) }
                    replaceLaunchingActivityIntent(msg.obj, subIntent)
                }
            }
        }; return baseInstance?.handleMessage(msg) ?: false
    }

    private fun transactionItems(container: Any): List<Any> {
        for (name in arrayOf("getTransactionItems", "getCallbacks")) {
            val items = container.javaClass.methods
                .firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.apply { isAccessible = true }
                ?.let { method -> runCatching { method.invoke(container) as? List<*> }.getOrNull() }
                ?.filterNotNull()
            if (!items.isNullOrEmpty()) return items
        }
        return container.javaClass.declaredFields.flatMap { field ->
            field.isAccessible = true
            val value = runCatching { field.get(container) }.getOrNull()
            if (value is List<*>) value.filterNotNull() else emptyList()
        }
    }

    private fun intentFieldOf(item: Any): Field? {
        val exact = item.javaClass.declaredFields.firstOrNull { field ->
            field.name == "mIntent" && field.type == Intent::class.java
        }
        val typed = exact ?: item.javaClass.declaredFields.firstOrNull { field ->
            field.type == Intent::class.java
        }
        typed?.isAccessible = true
        return typed
    }

    private fun replaceLaunchingActivityIntent(transaction: Any, subIntent: Intent) {
        val currentActivityThread = ActivityThreadClass.resolve()
            .optional(silent = true)
            .firstMethodOrNull { name = "currentActivityThread" }
            ?.invoke()
        val token = transaction.asResolver()
            .optional(silent = true)
            .firstMethodOrNull { name = "getActivityToken" }
            ?.invokeQuietly()
            ?: transactionItems(transaction).firstNotNullOfOrNull { item ->
                item.javaClass.methods
                    .firstOrNull { it.name == "getActivityToken" && it.parameterCount == 0 }
                    ?.apply { isAccessible = true }
                    ?.let { runCatching { it.invoke(item) }.getOrNull() }
            }
        val launchingActivity = currentActivityThread?.asResolver()
            ?.optional(silent = true)
            ?.firstMethodOrNull {
                name = "getLaunchingActivity"
                parameters(IBinder::class)
            }?.invokeQuietly(token)
        launchingActivity?.asResolver()
            ?.optional(silent = true)
            ?.firstFieldOrNull { name = "intent" }
            ?.set(subIntent)
    }
}
