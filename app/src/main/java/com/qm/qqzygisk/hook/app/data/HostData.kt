package com.qm.qqzygisk.hook.app.data

import com.highcapable.kavaref.extension.toClass
import com.qm.qqzygisk.BuildConfig
import org.lsposed.lsparanoid.Obfuscate
import kotlin.properties.Delegates

@Obfuscate
object HostData {
    var appClassLoader by Delegates.notNull<ClassLoader>()

    fun String.toAppClass() = toClass(appClassLoader)

    fun init(loader: ClassLoader) {
        appClassLoader = loader
    }

    fun toVerStr() = BuildConfig.VERSION_NAME
}