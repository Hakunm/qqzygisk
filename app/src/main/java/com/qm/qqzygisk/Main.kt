package com.qm.qqzygisk

import com.qm.qqzygisk.hook.core.LoadedApkHook
import com.qm.qqzygisk.hook.utils.ModuleConfig
import com.qm.qqzygisk.hook.utils.ModuleUtils
import com.v7878.r8.annotations.DoNotObfuscate
import com.v7878.r8.annotations.DoNotObfuscateType
import com.v7878.r8.annotations.DoNotShrink
import com.v7878.r8.annotations.DoNotShrinkType
import org.lsposed.lsparanoid.Obfuscate

@Suppress("unused")
@Obfuscate
@DoNotShrinkType
@DoNotObfuscateType
object Main {
    @Suppress("unused")
    @JvmStatic
    @DoNotShrink
    @DoNotObfuscate
    @Throws(Throwable::class)
    fun premain() {
        ModuleConfig.load()
    }

    @Suppress("unused")
    @JvmStatic
    @DoNotShrink
    @DoNotObfuscate
    @Throws(Throwable::class)
    fun main() {
        ModuleUtils.onZygiskLoadModule()
        LoadedApkHook.init()
    }
}
