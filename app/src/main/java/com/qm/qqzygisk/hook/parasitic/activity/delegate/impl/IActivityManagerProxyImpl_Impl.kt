package com.qm.qqzygisk.hook.parasitic.activity.delegate.impl

import com.qm.qqzygisk.hook.parasitic.activity.delegate.IActivityManagerProxy_com_qm_qqtest
import com.qm.qqzygisk.hook.parasitic.activity.delegate.caller.IActivityManagerProxyCaller
import java.lang.reflect.Proxy

/**
 *  IActivityManagerProxyImpl 注入 Stub
 */
object IActivityManagerProxyImpl_Impl {

    fun createWrapper(clazz: Class<*>?, instance: Any): Any? =
        Proxy.newProxyInstance(IActivityManagerProxyCaller.currentClassLoader, arrayOf(clazz),
            IActivityManagerProxy_com_qm_qqtest(instance)
        )
}