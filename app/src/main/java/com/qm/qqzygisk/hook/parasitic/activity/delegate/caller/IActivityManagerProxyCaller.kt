package com.qm.qqzygisk.hook.parasitic.activity.delegate.caller

import android.content.Intent
import com.highcapable.kavaref.extension.hasClass
import com.highcapable.kavaref.extension.isSubclassOf
import com.highcapable.kavaref.extension.toClassOrNull
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.parasitic.activity.config.ActivityProxyConfig
import com.qm.qqzygisk.hook.parasitic.activity.config.ActivityProxyStore
import com.qm.qqzygisk.hook.parasitic.activity.config.removeLaunchProtection
import com.qm.qqzygisk.hook.parasitic.activity.proxy.ModuleActivity
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * 代理当前 [ActivityManager] 调用类
 * 用户启动: SettingActivity↓
 * IActivityManagerProxyCaller 拦截
 *     ↓
 * 替换为: CameraPreviewActivity (代理)
 *     + extras: token（真实 Intent 留在进程内）
 *     ↓
 * 系统启动 CameraPreviewActivity
 *     ↓
 * HandlerDelegateCaller 拦截
 *     ↓
 * 从 token 取回真实 Intent
 *     ↓
 * 替换回: SettingActivity↓
 * 最终启动: SettingActivity
 */
internal object IActivityManagerProxyCaller {

    /**
     * 获取当前使用的 [ClassLoader]
     * @return [ClassLoader]
     */
    internal val currentClassLoader get() = AppParasitics.baseClassLoader

    /**
     * 调用代理的 [InvocationHandler.invoke] 方法
     * @param baseInstance 原始实例
     * @param method 被调用方法
     * @param args 被调用方法参数
     * @return [Any] or null
     */
    internal fun callInvoke(baseInstance: Any, method: Method?, args: Array<Any>?): Any? {
        if (method?.name == "startActivity") args?.indexOfFirst { it is Intent }?.also { index ->
            val argsInstance = (args[index] as? Intent) ?: return@also
            val component = argsInstance.component

            /**
             * 使用宿主包名判断当前启动的 [Activity] 位于当前宿主
             * 使用默认的 [ClassLoader] 判断当前 [Class] 处于模块中
             */
            if (component != null &&
                component.packageName == AppParasitics.currentPackageName &&
                javaClass.classLoader?.hasClass(component.className) == true
            ) {
                val targetClass = component.className.toClassOrNull()
                if (targetClass != null && targetClass isSubclassOf ModuleActivity::class) {
                    val token = ActivityProxyStore.put(argsInstance)
                    argsInstance.removeLaunchProtection()
                    args[index] = Intent().apply {
                        setClassName(
                            component.packageName,
                            ActivityProxyConfig.proxyClassName,
                        )
                        putExtra(ActivityProxyConfig.proxyTokenName, token)
                        removeLaunchProtection()
                    }
                }
            }
        }
        return method?.invoke(baseInstance, *(args ?: emptyArray()))
    }
}
