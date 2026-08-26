package com.qm.qqzygisk.hook.app

import android.app.Instrumentation
import android.content.Context
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.base.SettingData
import com.qm.qqzygisk.hook.app.data.HostData
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.app.chat.NtImageRkeyProvider
import com.qm.qqzygisk.hook.app.detect.AntiDetect
import com.qm.qqzygisk.hook.app.hooker.AntiRevokeHooker
import com.qm.qqzygisk.hook.app.hooker.ChatMenuHooker
import com.qm.qqzygisk.hook.app.hooker.EmoticonButtonHooker
import com.qm.qqzygisk.hook.app.hooker.EmoticonPanelHooker
import com.qm.qqzygisk.hook.app.hooker.EmotionToPicHooker
import com.qm.qqzygisk.hook.app.hooker.MsgFontHooker
import com.qm.qqzygisk.hook.app.hooker.RepeaterHooker
import com.qm.qqzygisk.hook.app.hooker.SettingHooker
import com.qm.qqzygisk.hook.app.hooker.StartActivityHooker
import com.qm.qqzygisk.hook.app.hooker.SystemCameraHooker
import com.qm.qqzygisk.hook.extension.InstrumentationDelegate
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.parasitic.AppParasitics.setInstrumentation
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ModuleUtils
import com.qm.qqzygisk.hook.utils.onAppLifecycle
import com.qm.qqzygisk.hook.utils.registerModuleAppActivities

object QQEntry {
    val settings = mutableSetOf<SettingData>()

    fun init(
        loader: ClassLoader,
        packageName: String,
    ) {
        settings.clear()
        HostData.init(loader)
        Log.info("running on: ${HostData.toVerStr()}")
        runCatching { AntiDetect.install() }
            .onFailure { Log.error("安装检测链拦截失败", it) }
        runCatching { NtImageRkeyProvider.installHook() }
            .onFailure { Log.error("安装 NT 图片 rkey hook 失败", it) }
        val generalSettingActivityClass = "com.tencent.mobileqq.activity.photo.CameraPreviewActivity".toAppClass()
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
            attachBaseContext { baseContext, _ ->
                HookSettings.initialize(baseContext)
                Log.info("hook settings: ${HookSettings.dump(settings)}")
            }
            onCreate {
                runCatching { ModuleUtils.ensureModuleApkPath() }
                registerModuleAppActivities(proxy = generalSettingActivityClass)
                ChatMenuHooker.load()
                AntiRevokeHooker.retry()
                RepeaterHooker.retry()
            }
        }
        val hooks =
            listOf(
                ChatMenuHooker,
                RepeaterHooker,
                EmoticonButtonHooker,
                EmoticonPanelHooker,
                EmotionToPicHooker,
                StartActivityHooker,
                SystemCameraHooker,
                SettingHooker,
                MsgFontHooker,
                AntiRevokeHooker,
            )
        hooks.forEach { hooker ->
            if (hooker.isShow) settings.add(hooker.toSettingData())
            if (hooker !== ChatMenuHooker) hooker.load()
        }
        StartActivityHooker.decorators.forEach { hooker ->
            if (hooker.isShow) settings.add(hooker.toSettingData())
        }
        AppParasitics.registerToAppLifecycle(packageName)
        Log.info("runed on: ${HostData.toVerStr()}")
    }
}
