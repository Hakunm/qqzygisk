package com.qm.qqzygisk.hook.utils

import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import java.util.concurrent.ConcurrentHashMap

/**
 * 枚举宿主 APK 的类名清单。
 *
 * 版本通用化的基础：QQ 每个版本都会位移混淆短名（如 `cd.a` -> `fd.a`），
 * 类名硬编码只适配单版本。改为按"包前缀 + 成员签名特征"在清单里搜目标类，
 * 混淆名变化也能命中。
 *
 * 清单来自 `BaseDexClassLoader.pathList.dexElements[].dexFile.entries()`，仅在首次目标定位
 * 时按需枚举并在进程内复用；跨启动只保存目标定位结果，不保存全量类名清单。
 */
internal object HostClassScanner {
    private class ClassIndex(val loader: ClassLoader, val names: Set<String>) {
        val packages = ConcurrentHashMap<String, Set<String>>()
    }

    @Volatile
    private var cachedIndex: ClassIndex? = null

    private fun index(): ClassIndex {
        val hostLoader = appClassLoader
        cachedIndex?.takeIf { it.loader === hostLoader }?.let { return it }
        synchronized(this) {
            cachedIndex?.takeIf { it.loader === hostLoader }?.let { return it }
            val startedAt = android.os.SystemClock.uptimeMillis()
            val result = mutableSetOf<String>()
            val pathListField = BaseDexClassLoader::class.java
                .getDeclaredField("pathList").apply { isAccessible = true }
            for (loader in generateSequence(hostLoader) { it.parent }) {
                if (loader !is BaseDexClassLoader) continue
                val pathList = pathListField.get(loader) ?: continue
                val elements = pathList.javaClass.getDeclaredField("dexElements")
                    .apply { isAccessible = true }.get(pathList) as Array<*>
                for (element in elements) {
                    if (element == null) continue
                    val dexFile = element.javaClass.getDeclaredField("dexFile")
                        .apply { isAccessible = true }.get(element) as? DexFile ?: continue
                    val entries = dexFile.entries()
                    while (entries.hasMoreElements()) result.add(entries.nextElement())
                }
            }
            check(result.isNotEmpty()) { "宿主 ClassLoader 中没有可枚举的 DEX，未缓存失败结果" }
            val index = ClassIndex(hostLoader, result)
            cachedIndex = index
            Log.info("宿主类名枚举完成: count=${result.size} elapsed=${android.os.SystemClock.uptimeMillis() - startedAt}ms")
            return index
        }
    }

    /** 所有以内层包 `prefix`（如 `com.tencent.mobileqq.setting.processor`）开头的类名。 */
    fun classesInPackage(prefix: String): Set<String> {
        val index = index()
        return index.packages.getOrPut(prefix) {
            val dotted = "$prefix."
            index.names.filterTo(mutableSetOf()) { it.startsWith(dotted) }
        }
    }

    /** 类名二段/一段情况：默认包与顶层混淆包（如 `zc.a`）、以及任意嵌套层包。 */
    fun classNames(): Set<String> = index().names

    fun load(name: String): Class<*>? = runCatching {
        Class.forName(name, false, appClassLoader)
    }.getOrNull()
}
