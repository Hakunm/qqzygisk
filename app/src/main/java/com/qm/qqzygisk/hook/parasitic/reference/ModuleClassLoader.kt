package com.qm.qqzygisk.hook.parasitic.reference

import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.ModuleUtils
import com.v7878.r8.annotations.DoNotObfuscateType
import com.v7878.r8.annotations.DoNotShrinkType
import org.lsposed.lsparanoid.Obfuscate

/**
 * 自动处理 (Xposed) 宿主环境与模块环境的 [ClassLoader]
 */
@Obfuscate
@DoNotShrinkType
@DoNotObfuscateType
class ModuleClassLoader private constructor() : ClassLoader(AppParasitics.baseClassLoader) {

    companion object {

        /** 当前 [ModuleClassLoader] 单例 */
        private var instance: ModuleClassLoader? = null

        /** 排除的 Hook APP (宿主) [Class] 类名数组 */
        private val excludeHostClasses = mutableSetOf<String>()

        /** 排除的模块 [Class] 类名数组 */
        private val excludeModuleClasses = mutableSetOf<String>()

        /**
         * 获取 [ModuleClassLoader] 单例
         * @return [ModuleClassLoader]
         */
        internal fun instance() = instance ?: ModuleClassLoader().apply { instance = this }

        /**
         * 添加到 Hook APP (宿主) [Class] 排除列表
         *
         * 排除列表中的 [Class] 将会使用宿主的 [ClassLoader] 进行装载
         *
         * - 排除列表仅会在 (Xposed) 宿主环境生效
         * @param name 需要添加的 [Class] 完整类名
         */
        fun excludeHostClasses(vararg name: String) {
            excludeHostClasses.addAll(name.toList())
        }

        /**
         * 添加到模块 [Class] 排除列表
         *
         * 排除列表中的 [Class] 将会使用模块 (当前宿主环境的模块注入进程) 的 [ClassLoader] 进行装载
         *
         * - 排除列表仅会在 (Xposed) 宿主环境生效
         * @param name 需要添加的 [Class] 完整类名
         */
        fun excludeModuleClasses(vararg name: String) {
            excludeModuleClasses.addAll(name.toList())
        }

        init {
            excludeHostClasses.add("androidx.lifecycle.ReportFragment")
        }
    }

    /** 默认 [ClassLoader] */
    private val baseLoader get() = AppParasitics.baseClassLoader

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        return AppParasitics.currentApplication?.classLoader?.let { hostLoader ->
            excludeHostClasses.takeIf { it.isNotEmpty() }?.forEach { runCatching { if (name == it) return@let hostLoader.loadClass(name) } }
            excludeModuleClasses.takeIf { it.isNotEmpty() }?.forEach { runCatching { if (name == it) return@let baseLoader.loadClass(name) } }
            runCatching { return@let baseLoader.loadClass(name) }
            runCatching { baseLoader.loadClass(name) }.getOrNull() ?: hostLoader.loadClass(name)
        } ?: super.loadClass(name, resolve)
    }
}