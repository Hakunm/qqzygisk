package com.qm.qqzygisk.hook.parasitic.activity.delegate

import androidx.annotation.Keep
import com.qm.qqzygisk.hook.parasitic.activity.delegate.caller.IActivityManagerProxyCaller
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

@Keep
class IActivityManagerProxy_com_qm_qqtest(private val baseInstance: Any) : InvocationHandler {

    override fun invoke(proxy: Any?, method: Method?, args: Array<Any>?) = IActivityManagerProxyCaller.callInvoke(baseInstance, method, args)
}