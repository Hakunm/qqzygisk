package com.qm.qqzygisk.hook.core

import androidx.annotation.Keep
import com.qm.qqzygisk.hook.utils.ModuleUtils
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage

@Keep
class XpHook: IXposedHookZygoteInit, IXposedHookLoadPackage {
    override fun initZygote(sparam: IXposedHookZygoteInit.StartupParam) {
        ModuleUtils.onXpLoadModule(sparam.modulePath)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        ApplicationHook.init(lpparam.packageName, lpparam.classLoader)
    }
}