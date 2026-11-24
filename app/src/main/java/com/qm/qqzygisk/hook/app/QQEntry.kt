package com.qm.qqzygisk.hook.app

import android.app.Activity
import android.os.SystemClock
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.base.SettingData
import com.qm.qqzygisk.hook.app.data.HostData
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.app.chat.NtImageRkeyProvider
import com.qm.qqzygisk.hook.app.hooker.AntiRevokeHooker
import com.qm.qqzygisk.hook.app.hooker.BiliMiniAppWebCardHooker
import com.qm.qqzygisk.hook.app.hooker.ChatMenuHooker
import com.qm.qqzygisk.hook.app.hooker.ChineseSimplifyHooker
import com.qm.qqzygisk.hook.app.hooker.DisableReplyAtHooker
import com.qm.qqzygisk.hook.app.hooker.EmoticonButtonHooker
import com.qm.qqzygisk.hook.app.hooker.EmoticonPanelHooker
import com.qm.qqzygisk.hook.app.hooker.EmotionToPicHooker
import com.qm.qqzygisk.hook.app.hooker.MsgFontHooker
import com.qm.qqzygisk.hook.app.hooker.QQPatchBlockHooker
import com.qm.qqzygisk.hook.app.hooker.RecalledBadgeHooker
import com.qm.qqzygisk.hook.app.hooker.RepeaterHooker
import com.qm.qqzygisk.hook.app.hooker.SettingHooker
import com.qm.qqzygisk.hook.app.hooker.StartActivityHooker
import com.qm.qqzygisk.hook.app.hooker.SystemCameraHooker
import com.qm.qqzygisk.hook.app.host.HostRuntime
import com.qm.qqzygisk.hook.app.host.QQHostBridge
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.parasitic.AppParasitics.setInstrumentation
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ModuleUtils
import com.qm.qqzygisk.hook.utils.onAppLifecycle
import com.qm.qqzygisk.hook.utils.registerModuleAppActivities
import java.util.concurrent.atomic.AtomicBoolean

object QQEntry {
    val settings = mutableSetOf<SettingData>()

    /**
     * 依赖宿主启动完成的 hook：它们的安装会强制初始化宿主 UI 类
     * （菜单布局 -> ViewUtils -> BaseApplication.context），必须等宿主自己的 Activity 生命周期。
     */
    private val hostUiHookers = listOf(ChatMenuHooker, RepeaterHooker, SettingHooker)

    /**
     * 宿主就绪 hook 安装闸门：一次性、重入/并发安全，失败即本进程终态。
     * 宿主每个 Activity 生命周期事件都会进入 [onHostActivityLifecycle]，但只有第一个
     * 真正执行安装。
     */
    private val hostHookInstallGate = HostHookInstallGate()

    /** 宿主 ClassLoader 对比日志只输出一次 */
    private val hostClassLoaderLogged = AtomicBoolean(false)

    /** 防止 LoadedApk.createOrUpdateClassLoaderLocked 多次回调重复清空设置与重复挂钩 */
    private val initialized = AtomicBoolean(false)

    /** 宿主的 LoadedApk 包名（与 `Context.packageName` 同源），热补丁拦截在 Application 构造前用它过滤宿主 Context */
    internal var hostPackageName: String = ""
        private set

    fun init(
        loader: ClassLoader,
        packageName: String,
    ) {
        if (!initialized.compareAndSet(false, true)) {
            HostData.alignHostClassLoader(loader)
            AntiRevokeHooker.retry()
            return
        }
        settings.clear()
        hostPackageName = packageName
        HostData.init(loader)
        // 热补丁入口先于其它宿主 hook 安装，不能被 UI/业务类解析失败挡住。
        QQPatchBlockHooker.load()
        HostRuntime.install(QQHostBridge)
        Log.info("running on: ${HostData.toVerStr()}")
        runCatching { NtImageRkeyProvider.installHook() }
            .onFailure { Log.error("安装 NT 图片 rkey hook 失败", it) }
        val generalSettingActivityClass = HostRuntime.current.proxyActivityClass
            ?: "com.tencent.mobileqq.activity.photo.CameraPreviewActivity".toAppClass()
        val hookedInstrumentationClass = "com.tencent.biz.richframework.hook.instrumentation.HookedInstrumentation".toAppClass()
        hookedInstrumentationClass
            .resolve()
            .firstMethod { name = "hookInstrumentation" }
            .hook {
                after {
                    setInstrumentation()
                }
            }

        onAppLifecycle {
            attachBaseContext { baseContext, hasCalledSuper ->
                HookSettings.initialize(baseContext)
                if (!hasCalledSuper) {
                    Log.info("hook settings: ${HookSettings.dump(settings)}")
                } else {
                    alignHostClassLoader(baseContext.classLoader, "attachBaseContext")
                    AntiRevokeHooker.onHostContextReady(baseContext)
                }
            }
            onCreate {
                registerModuleAppActivities(proxy = generalSettingActivityClass)
                alignHostClassLoader(classLoader ?: javaClass.classLoader, "Application.onCreate")
                AntiRevokeHooker.onHostContextReady(this)
            }
            beforeActivityCreate { activity ->
                onHostActivityLifecycle(activity)
            }
        }
        val hooks =
            listOf(
                // 此项已在宿主 ClassLoader 就绪时安装；这里只注册设置。
                QQPatchBlockHooker,
                ChatMenuHooker,
                RepeaterHooker,
                BiliMiniAppWebCardHooker,
                DisableReplyAtHooker,
                EmoticonButtonHooker,
                EmoticonPanelHooker,
                EmotionToPicHooker,
                StartActivityHooker,
                SystemCameraHooker,
                SettingHooker,
                MsgFontHooker,
                ChineseSimplifyHooker,
                AntiRevokeHooker,
            )
        hooks.forEach { hooker ->
            if (hooker.isShow) {
                settings.add(hooker.toSettingData())
                settings.addAll(hooker.extraSettings)
            }
            if (hooker !== QQPatchBlockHooker && hooker !== BiliMiniAppWebCardHooker &&
                hooker !== DisableReplyAtHooker && hooker !== ChineseSimplifyHooker && hooker !in hostUiHookers
            ) hooker.load()
        }
        StartActivityHooker.decorators.forEach { hooker ->
            if (hooker.isShow) {
                settings.add(hooker.toSettingData())
                settings.addAll(hooker.extraSettings)
            }
        }
        AppParasitics.registerToAppLifecycle(packageName)
        Log.info("runed on: ${HostData.toVerStr()}")
    }

    /**
     * 宿主自己的 Activity 走到创建入口时安装依赖宿主资源的 hook。
     *
     * 该回调由 [InstrumentationDelegate] 在转发宿主原始 `callActivityOnCreate` 之前直接派发
     * （不是框架 Instrumentation 的 HookTransformer），因此依赖宿主 UI 的安装在首个 Activity
     * （含通知进入的聊天）方法体执行前同步完成。
     *
     * 安装由 [HostHookInstallGate] 保证一次性：重入/并发的 Activity 生命周期事件不会重复安装；
     * 失败（含部分 hook 安装后失败）对该进程是终态，不会在每个 Activity 反复改写 hook 入口。
     */
    private fun onHostActivityLifecycle(activity: Activity) {
        if (!isHostActivity(activity)) return
        HookSettings.initialize(activity)
        // 先于一次性 UI 安装闸门完成 ClassLoader 对齐与防撤回补齐：即使 UI hook 进入失败终态或后续
        // Activity 发生 ClassLoader 切换，防撤回仍能对齐并补齐安装。
        alignHostClassLoader(activity.javaClass.classLoader, "Activity")
        AntiRevokeHooker.onHostContextReady(activity)
        try {
            hostHookInstallGate.install { installHostHooks(activity) }
        } catch (th: Throwable) {
            Log.error("依赖宿主资源的 hook 安装异常, 本进程不再重试", th)
        }
        // “已撤回”标记独立安装：形状漂移/失败只跳过本 hook，允许后续 Activity 重试，也不影响其它 hook。
        runCatching { RecalledBadgeHooker.install(activity) }
            .onFailure { Log.warn("安装已撤回标记 hook 失败", it) }
        // 展示转换独立安装，协议漂移不能令菜单/设置等宿主 UI hook 进入失败终态。
        BiliMiniAppWebCardHooker.load()
        // 引用自动 @ 独立安装，协议漂移不影响其它宿主 UI hook。
        DisableReplyAtHooker.load()
        // 正文转换只在宿主 UI ClassLoader 就绪后安装，失败不阻断其他功能。
        ChineseSimplifyHooker.load()
    }

    /**
     * 安装依赖宿主资源的 hook。
     *
     * @return 是否全部安装成功
     */
    private fun installHostHooks(activity: Activity): Boolean {
        alignHostClassLoader(activity.javaClass.classLoader, "installHostHooks")
        val start = SystemClock.elapsedRealtime()
        val chatMenu = ChatMenuHooker.load()
        val repeater = RepeaterHooker.load()
        val setting = HostRuntime.current.installSettingsEntry(activity)
        val emoticonFailure = runCatching { EmoticonButtonHooker.installHostHooks() }.exceptionOrNull()
        val cost = SystemClock.elapsedRealtime() - start
        val installed = chatMenu && repeater && setting && emoticonFailure == null
        if (installed) {
            Log.info("依赖宿主资源的 hook 安装完成, 耗时 ${cost}ms")
        } else {
            Log.error(
                "依赖宿主资源的 hook 未全部安装, 本进程不再重试: " +
                    "chatMenu=$chatMenu repeater=$repeater setting=$setting emoticon=${emoticonFailure == null}, 耗时 ${cost}ms",
                emoticonFailure,
            )
        }
        return installed
    }

    /**
     * 把宿主类解析用的 ClassLoader 对齐到真实运行宿主代码的 ClassLoader。
     *
     * 宿主（RFix/qfix）可能在模块初始化（早期 LoadedApk 时机）之后才替换真正加载宿主类的
     * ClassLoader，模块初始化时拿到的 ClassLoader 不一定是线上 QQ 实际使用的那个。
     * 在 Application 与 Activity 生命周期入口均执行对比并（必要时）对齐，确保后台冷启动和前台页面
     * 都使用同一来源，避免 hook 到过期的宿主类副本。扫描缓存按 ClassLoader 标识重建，无需手动失效。
     */
    private fun alignHostClassLoader(
        hostLoader: ClassLoader?,
        stage: String,
    ) {
        if (hostLoader == null) return
        val moduleLoader = HostData.appClassLoader
        if (hostClassLoaderLogged.compareAndSet(false, true)) {
            Log.info(
                "宿主 ClassLoader 对比($stage): module=${moduleLoader.javaClass.name}@${System.identityHashCode(moduleLoader)} " +
                    "host=${hostLoader.javaClass.name}@${System.identityHashCode(hostLoader)} same=${moduleLoader === hostLoader}",
            )
        }
        if (moduleLoader !== hostLoader) {
            HostData.init(hostLoader)
            Log.warn("宿主 ClassLoader 与模块初始化时不同($stage), 已对齐为: ${hostLoader.javaClass.name}")
        }
    }

    /**
     * 只接受宿主自己的 Activity：跳过模块自身注入的 Activity（代理），
     * 也跳过其它包（插件等）的 Activity，等宿主自己的 Activity 真正创建。
     */
    private fun isHostActivity(activity: Activity): Boolean {
        if (activity.javaClass.name.startsWith(ModuleUtils.modulePackageName)) return false
        return activity.packageName == hostPackageName
    }
}
