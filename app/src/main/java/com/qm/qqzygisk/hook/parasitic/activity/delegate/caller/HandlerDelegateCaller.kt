package com.qm.qqzygisk.hook.parasitic.activity.delegate.caller

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.lazyClass
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.parasitic.activity.config.ActivityProxyConfig

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
                @Suppress("DEPRECATION")
                if (intent?.hasExtra(ActivityProxyConfig.proxyIntentName) == true)
                    intentResolver.set(intent.getParcelableExtra(ActivityProxyConfig.proxyIntentName))
            }
            EXECUTE_TRANSACTION -> {
                val callbacks = ClientTransactionClass.resolve()
                    .optional(silent = true)
                    .firstMethodOrNull {
                        name = "getCallbacks"
                    }?.of(msg.obj)
                    ?.invokeQuietly<List<Any>>()
                    ?.takeIf { it.isNotEmpty() }
                callbacks?.filter { it.javaClass.name.contains("LaunchActivityItem") }?.forEach { item ->
                    val itemResolver = item.asResolver().optional(silent = true)
                        .firstFieldOrNull { name = "mIntent" }
                    val intent = itemResolver?.get<Intent>()
                    val mExtras = mExtrasResolver?.copy()?.of(intent)?.getQuietly<Bundle>()
                    mExtras?.classLoader = AppParasitics.currentApplication?.classLoader
                    if (intent?.hasExtra(ActivityProxyConfig.proxyIntentName) == true) {
                        @Suppress("DEPRECATION")
                        val subIntent = intent.getParcelableExtra<Intent>(ActivityProxyConfig.proxyIntentName)
                            val currentActivityThread = ActivityThreadClass.resolve()
                                .optional(silent = true)
                                .firstMethodOrNull { name = "currentActivityThread" }
                                ?.invoke()
                            val token = msg.obj.asResolver()
                                .optional(silent = true)
                                .firstMethodOrNull { name = "getActivityToken" }
                                ?.invokeQuietly()
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
                        itemResolver.set(subIntent)
                    }
                }
            }
        }; return baseInstance?.handleMessage(msg) ?: false
    }
}