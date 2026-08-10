package com.qm.qqzygisk.hook.parasitic.activity.delegate.impl

import android.app.ActivityManager

/**
 * 代理当前 [ActivityManager] 调用接口实现
 */
internal object IActivityManagerProxyImpl {

    /**
     * 创建 [ActivityManager] 代理
     * @param clazz 代理的目标 [Class]
     * @param instance 代理的目标实例
     * @return [Any] 代理包装后的实例
     */
    internal fun createWrapper(clazz: Class<*>?, instance: Any) = IActivityManagerProxyImpl_Impl.createWrapper(clazz, instance)
}