package com.qm.qqzygisk.hook.parasitic

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AndroidAppHelper
import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.lazyClass
import com.highcapable.kavaref.extension.lazyClassOrNull
import com.highcapable.kavaref.extension.toClassOrNull
import com.qm.qqzygisk.hook.extension.InstrumentationDelegate
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.parasitic.activity.config.ActivityProxyConfig
import com.qm.qqzygisk.hook.parasitic.activity.delegate.impl.HandlerDelegateImpl
import com.qm.qqzygisk.hook.parasitic.activity.delegate.impl.IActivityManagerProxyImpl
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ModuleUtils

@JvmOverloads
fun Context.registerReceiver(
    filter: IntentFilter,
    flags: Int? = null,
    exported: Boolean = true,
    body: BroadcastReceiver.(context: Context, intent: Intent) -> Unit
): BroadcastReceiver {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return
            body(context, intent)
        }
    }
    var receiverFlags = flags

    if (exported)
        receiverFlags = if (receiverFlags == null) Context.RECEIVER_EXPORTED
        else receiverFlags or Context.RECEIVER_EXPORTED

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        if (receiverFlags != null)
            registerReceiver(receiver, filter, receiverFlags)
        else registerReceiver(receiver, filter)
    else registerReceiver(receiver, filter)

    return receiver
}

internal object AppParasitics {
    /** Android 系统框架名称 */
    internal const val SYSTEM_FRAMEWORK_NAME = "android"

    /** 当前 Hook APP (宿主) 的生命周期演绎者数组 */
    private val appLifecycleActors = mutableMapOf<String, AppLifecycleActor>()

    /**
     * 当前 Hook APP (宿主) 的全局生命周期 Application
     */
    var hostApplication: Application? = null

    val appApplication get() = ActivityThreadClass.resolve().firstMethod { name = "currentApplication" }.invoke<Application>()

    val appContext get() = hostApplication ?: appApplication

    /**
     * 获取当前宿主的 [Application]
     * @return [Application] or null
     */
    internal val currentApplication
        get() = ActivityThreadClass.resolve()
            .optional(silent = true)
            .firstMethodOrNull { name = "currentApplication" }
            ?.invoke<Application>()

    /**
     * 获取当前宿主的包名
     * @return [String]
     */
    internal val currentPackageName get() = ModuleUtils.packageName

    /** [Activity] 代理是否已经注册 */
    private var isActivityProxyRegistered = false

    /**
     * 当前环境中使用的 [ClassLoader]
     *
     * 装载位于宿主环境与模块环境时均使用当前 DEX 内的 [ClassLoader]
     * @return [ClassLoader]
     * @throws IllegalStateException 如果 [ClassLoader] 为空
     */
    internal val baseClassLoader get() = classOf<ModuleUtils>().classLoader ?: error("Operating system not supported")

    val ActivityThreadClass by lazyClass("android.app.ActivityThread")
    private val ContextImplClass by lazyClass("android.app.ContextImpl")
    private val ActivityManagerNativeClass by lazyClass("android.app.ActivityManagerNative")
    private val SingletonClass by lazyClass("android.util.Singleton")
    private val IActivityManagerClass by lazyClass("android.app.IActivityManager")
    private val ActivityTaskManagerClass by lazyClassOrNull("android.app.ActivityTaskManager")
    private val IActivityTaskManagerClass by lazyClass("android.app.IActivityTaskManager")

    internal fun registerModuleAppActivities(context: Context, proxy: Any?) {
        if (isActivityProxyRegistered) return

        runCatching {
            ActivityProxyConfig.apply {
                proxyIntentName = "${ModuleUtils.modulePackageName}.ACTIVITY_PROXY_INTENT"
                proxyClassName = proxy?.let {
                    when (it) {
                        is String, is CharSequence -> it.toString()
                        is Class<*> -> it.name
                        else -> error("This proxy [$it] type is not allowed")
                    }
                }?.takeIf { it.isNotBlank() } ?: context.packageManager?.runCatching {
                    @SuppressLint("QueryPermissionsNeeded")
                    queryIntentActivities(
                        getLaunchIntentForPackage(context.packageName)!!,
                        0
                    ).first().activityInfo.name
                }?.getOrNull() ?: ""
                val checkIsActivity = proxyClassName.toClassOrNull(context.classLoader)
                    ?.resolve()?.optional(silent = true)
                    ?.firstMethodOrNull {
                        name = "setIntent"
                        parameters(Intent::class)
                        superclass()
                    } != null
                if (!checkIsActivity) {
                    if (proxyClassName.isBlank()) error("Cound not got launch intent for package \"${context.packageName}\"")
                    else error("Could not found \"$proxyClassName\" or Class is not a type of Activity")
                }

                val sCurrentActivityThread = setInstrumentation()

                val mH = sCurrentActivityThread.asResolver()
                    .optional(silent = true)
                    .firstFieldOrNull { name = "mH" }
                    ?.get<Handler>() ?: error("Could not found mH in ActivityThread")
                val mCallbackResolver = Handler::class.resolve()
                    .optional(silent = true)
                    .firstFieldOrNull { name = "mCallback" }
                    ?.of(mH)
                val mCallback = mCallbackResolver?.get<Handler.Callback>()
                if (mCallback != null) {
                    if (mCallback.javaClass.name != HandlerDelegateImpl.wrapperClassName)
                        mCallbackResolver.set(HandlerDelegateImpl.createWrapper(mCallback))
                } else mCallbackResolver?.set(HandlerDelegateImpl.createWrapper())

                /** Patched [ActivityManager] */
                val gDefault = ActivityManagerNativeClass.resolve()
                    .optional(silent = true)
                    .firstFieldOrNull { name = "gDefault" }
                    ?.get()
                    ?: ActivityManager::class.resolve()
                        .optional(silent = true)
                        .firstFieldOrNull {
                            name = "IActivityManagerSingleton"
                        }?.get()
                val mInstanceResolver = SingletonClass.resolve()
                    .optional(silent = true)
                    .firstFieldOrNull { name = "mInstance" }
                    ?.of(gDefault)
                val mInstance = mInstanceResolver?.get()
                mInstance?.let {
                    mInstanceResolver.set(
                        IActivityManagerProxyImpl.createWrapper(
                            IActivityManagerClass,
                            it
                        )
                    )
                }
                val singleton = ActivityTaskManagerClass?.resolve()
                    ?.optional(silent = true)
                    ?.firstFieldOrNull { name = "IActivityTaskManagerSingleton" }
                    ?.get()
                SingletonClass.resolve()
                    .optional(silent = true)
                    .firstMethodOrNull { name = "get" }
                    ?.of(singleton)
                    ?.invokeQuietly()
                val mInstanceResolver2 = mInstanceResolver?.copy()?.of(singleton)
                val mInstance2 = mInstanceResolver2?.get()
                mInstance2?.let {
                    mInstanceResolver2.set(
                        IActivityManagerProxyImpl.createWrapper(
                            IActivityTaskManagerClass,
                            it
                        )
                    )
                }
                isActivityProxyRegistered = true
            }
        }.onFailure { Log.error("registerModuleAppActivities",it) }
    }

    internal fun setInstrumentation(): Any {
        val sCurrentActivityThread = ActivityThreadClass.resolve()
            .optional(silent = true)
            .firstFieldOrNull { name = "sCurrentActivityThread" }
            ?.get()

        val instrumentation = sCurrentActivityThread?.asResolver()
            ?.optional(silent = true)
            ?.firstMethodOrNull { name = "getInstrumentation" }
            ?.invoke<Instrumentation>()
            ?: error("Could not found Instrumentation in ActivityThread")

        sCurrentActivityThread.asResolver()
            .optional()
            .firstFieldOrNull { name = "mInstrumentation" }
            ?.set(InstrumentationDelegate.wrapper(instrumentation))
            ?: error("Could not set mInstrumentation in ActivityThread")

        return sCurrentActivityThread
    }

    /** 已注入模块资源的 Resources 实例 */
    private val injectedResources = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Resources, Boolean>())

    private var isSetImplHooked = false

    internal fun injectModuleAppResources(hostResources: Resources) {
        runCatching {
            if (currentPackageName == ModuleUtils.modulePackageName)
                return Log.error("You cannot inject module resources into yourself")
            hostResources.assets.asResolver()
                .firstMethod {
                    name = "addAssetPath"
                    parameters(String::class)
                }.invoke(ModuleUtils.moduleAppFilePath)
            injectedResources.add(hostResources)
        }.onFailure {
            Log.error("Failed to inject module resources into [$hostResources]", it)
        }
    }

    /**
     * 注入当前 Hook APP (宿主) 全局生命周期
     * @param packageName 包名
     */
    internal fun registerToAppLifecycle(packageName: String) {
        if (appLifecycleActors.isEmpty()) return

        /** Hook [Application] 装载方法 */
        runCatching {
            // Hook Resources.setImpl，在系统替换底层 ResourcesImpl 时重新注入模块资源
//            Resources::class.resolve()
//                .optional(silent = true)
//                .firstMethodOrNull { name = "setImpl" }
//                ?.hook {
//                    after {
//                        runCatching {
//                            val res = this.instance as? Resources ?: return@after
//                            if (injectedResources.contains(res)) {
//                                Log.info("setImpl re-inject: resources=$res assets=${res.assets}")
//                                res.assets.asResolver()
//                                    .firstMethod {
//                                        name = "addAssetPath"
//                                        parameters(String::class)
//                                    }.invoke(ModuleUtils.moduleAppFilePath)
//                            }
//                        }.onFailure { Log.error("setImpl re-inject failed", it) }
//                    }
//                }
            Application::class.resolve()
                .optional(silent = true)
                .apply {
                    // 最早的初始化时机
                    firstMethod {
                        name = "attach";
                        parameters(Context::class)
                    }
                        .hook {
                            before {
                                runCatching {
                                    appLifecycleActors.forEach { (_, actor) ->
                                        (this.args[0] as? Context?)?.also { actor.attachBaseContextCallback?.invoke(it, false) }
                                    }
                                }.onFailure { Log.error("attach",it) }
                            }
                            after {
                                runCatching {
                                    appLifecycleActors.forEach { (_, actor) ->
                                        (this.args[0] as? Context?)?.also { actor.attachBaseContextCallback?.invoke(it, true) }
                                    }
                                }.onFailure { Log.error("attach",it) }
                            }
                        }

                    // 应用终止时调用
                    // 用于清理资源、保存状态
                    firstMethod {
                        name = "onTerminate"
                    }
                        .hook {
                            after {
                                runCatching {
                                    appLifecycleActors.forEach { (_, actor) ->
                                        (this.instance as? Application?)?.also { actor.onTerminateCallback?.invoke(it) }
                                    }
                                }.onFailure { Log.error("onTerminate",it) }
                            }
                        }

                    // 系统内存不足时调用
                    // 用于释放非必要资源
                    firstMethod {
                        name = "onLowMemory"
                    }
                        .hook {
                            after {
                                runCatching {
                                    appLifecycleActors.forEach { (_, actor) ->
                                        (this.instance as? Application?)?.also { actor.onLowMemoryCallback?.invoke(it) }
                                    }
                                }.onFailure { Log.error("onLowMemory",it) }
                            }
                        }

                    // 系统内存压力变化时调用
                    // 根据 level 参数决定释放多少资源
                    firstMethod {
                        name = "onTrimMemory"
                        parameters(Int::class)
                    }
                        .hook {
                            after {
                                runCatching {
                                    val self = this.instance as? Application? ?: return@after
                                    val type = this.args[0] as? Int? ?: return@after
                                    appLifecycleActors.forEach { (_, actor) -> actor.onTrimMemoryCallback?.invoke(self, type) }
                                }.onFailure { Log.error("onTrimMemory", it) }
                            }
                        }

                    // 配置变更时调用
                    // 如屏幕旋转、语言切换、深色模式等
                    firstMethod {
                        name = "onConfigurationChanged"
                    }
                        .hook {
                            after {
                                runCatching {
                                    val self = this.instance as? Application? ?: return@after
                                    val config = this.args[0] as? Configuration? ?: return@after
                                    appLifecycleActors.forEach { (_, actor) -> actor.onConfigurationChangedCallback?.invoke(self, config) }
                                }.onFailure { Log.error("onConfigurationChanged", it) }
                            }
                        }
                }

            // Application 完全初始化后调用
            // 用于执行模块初始化逻辑、注册广播接收器
            Instrumentation::class
                .resolve()
                .optional(silent = true)
                .firstMethodOrNull { name = "callApplicationOnCreate" }
                ?.hook {
                    after {
                        runCatching {
                            (this.args[0] as? Application?)?.also {
                                /**
                                 * 注册广播
                                 * @param result 回调 - ([Context] 当前实例, [Intent] 当前对象)
                                 */
                                fun IntentFilter.registerReceiver(result: (Context, Intent) -> Unit) {
                                    it.registerReceiver(filter = this, exported = true) { context, intent ->
                                        result(context, intent)
                                    }
                                }
                                hostApplication = it
                                appLifecycleActors.forEach { (_, actor) ->
                                    actor.onCreateCallback?.invoke(it)
                                    actor.onReceiverActionsCallbacks.takeIf { e -> e.isNotEmpty() }?.forEach { (_, e) ->
                                        if (e.first.isNotEmpty()) IntentFilter().apply {
                                            e.first.forEach { action -> addAction(action) }
                                        }.registerReceiver(e.second)
                                    }
                                    actor.onReceiverFiltersCallbacks.takeIf { e -> e.isNotEmpty() }
                                        ?.forEach { (_, e) -> e.first.registerReceiver(e.second) }
                                }
                            }
                        }.onFailure { Log.error("callApplicationOnCreate",it) }
                    }
                }
        }.onFailure { Log.error("registerToAppLifecycle", it) }
    }
    /**
     * 当前 Hook APP (宿主) 的生命周期演绎者
     */
    internal class AppLifecycleActor {

        internal companion object {

            /** 是否在发生异常时将异常抛出给宿主 */
            internal var isOnFailureThrowToApp: Boolean? = null

            /**
             * 获取、创建新的 [AppLifecycleActor]
             * @param instance 实例
             * @return [AppLifecycleActor]
             */
            internal fun get(instance: Any) =
                appLifecycleActors[instance.toString()] ?: AppLifecycleActor().apply { appLifecycleActors[instance.toString()] = this }
        }

        /** [Application.attachBaseContext] 回调 */
        internal var attachBaseContextCallback: ((Context, Boolean) -> Unit)? = null

        /** [Application.onCreate] 回调 */
        internal var onCreateCallback: (Application.() -> Unit)? = null

        /** [Application.onTerminate] 回调 */
        internal var onTerminateCallback: (Application.() -> Unit)? = null

        /** [Application.onLowMemory] 回调 */
        internal var onLowMemoryCallback: (Application.() -> Unit)? = null

        /** [Application.onTrimMemory] 回调 */
        internal var onTrimMemoryCallback: ((Application, Int) -> Unit)? = null

        /** [Application.onConfigurationChanged] 回调 */
        internal var onConfigurationChangedCallback: ((Application, Configuration) -> Unit)? = null

        /** 系统广播监听回调 */
        internal val onReceiverActionsCallbacks = mutableMapOf<String, Pair<Array<out String>, (Context, Intent) -> Unit>>()

        /** 系统广播监听回调 */
        internal val onReceiverFiltersCallbacks = mutableMapOf<String, Pair<IntentFilter, (Context, Intent) -> Unit>>()
    }
}