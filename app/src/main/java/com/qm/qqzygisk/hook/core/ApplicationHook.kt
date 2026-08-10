package com.qm.qqzygisk.hook.core

import com.qm.qqzygisk.hook.app.QQEntry
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
object ApplicationHook {
    fun init(packageName: String, classloader: ClassLoader) {
        when (packageName) {
            "com.tencent.mobileqq" -> QQEntry.init(classloader, packageName)
        }
    }
}