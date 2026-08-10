package com.qm.qqzygisk.hook.utils

import android.app.AndroidAppHelper
import com.qm.qqzygisk.BuildConfig
import com.v7878.zygisk.ZygoteLoader

internal object ModuleUtils {
    private var isZygiskLoaded = false

    /** Xposed 模块是否已被装载 */
    private var isXpLoaded = false
    var modulePackageName = BuildConfig.APPLICATION_ID

    val isXpEnvironment get() = isXpLoaded

    var moduleAppFilePath = "/data/user/0/com.tencent.mobileqq/files/mmkv\u200B/fugfhj"
        private set

    /**
     * Hook 宿主的包名
     */
    val packageName: String? get() = if (isXpEnvironment) AndroidAppHelper.currentApplicationInfo().packageName else ZygoteLoader.getPackageName()

    fun onZygiskLoadModule() {
        isZygiskLoaded = true
    }

    fun onXpLoadModule(appFilePath: String) {
        isXpLoaded = true
        moduleAppFilePath = appFilePath
    }
}