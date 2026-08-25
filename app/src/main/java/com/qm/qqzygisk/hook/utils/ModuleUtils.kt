package com.qm.qqzygisk.hook.utils

import android.app.AndroidAppHelper
import com.qm.qqzygisk.BuildConfig
import com.v7878.zygisk.ZygoteLoader
import java.io.File

internal object ModuleUtils {
    private var isZygiskLoaded = false

    /** Xposed 模块是否已被装载 */
    private var isXpLoaded = false
    var modulePackageName = BuildConfig.APPLICATION_ID

    val isXpEnvironment get() = isXpLoaded

    private const val HIDDEN_APK_PATH = "/data/user/0/com.tencent.mobileqq/files/mmkv\u200B/fugfhj"

    var moduleAppFilePath = HIDDEN_APK_PATH
        private set

    /**
     * Hook 宿主的包名
     */
    val packageName: String? get() = if (isXpEnvironment) AndroidAppHelper.currentApplicationInfo().packageName else ZygoteLoader.getPackageName()

    fun onZygiskLoadModule() {
        isZygiskLoaded = true
        ensureModuleApkPath()
    }

    fun onXpLoadModule(appFilePath: String) {
        isXpLoaded = true
        moduleAppFilePath = appFilePath
    }

    /**
     * Settings Compose/theme IDs live in the module APK. The Zygisk payload is dex-only,
     * so the APK must exist at [HIDDEN_APK_PATH] (or a readable fallback) before addAssetPath.
     */
    fun ensureModuleApkPath(): String {
        if (isXpEnvironment) return moduleAppFilePath
        val hidden = File(HIDDEN_APK_PATH)
        if (hidden.isFile && hidden.length() > 1_000L) {
            moduleAppFilePath = hidden.absolutePath
            return moduleAppFilePath
        }
        val sources = buildList {
            runCatching { ZygoteLoader.getModuleDir() }.getOrNull()?.let { dir ->
                add(File(dir, "app-release.apk"))
            }
            add(File("/data/adb/modules/com_qm.qqhook/app-release.apk"))
            add(File("/data/adb/qqhook/app-release.apk"))
        }
        val src = sources.firstOrNull { it.isFile && it.length() > 1_000L }
        if (src != null) {
            runCatching {
                hidden.parentFile?.mkdirs()
                src.copyTo(hidden, overwrite = true)
            }
            moduleAppFilePath = if (hidden.isFile && hidden.length() > 1_000L) {
                hidden.absolutePath
            } else {
                src.absolutePath
            }
        }
        return moduleAppFilePath
    }
}