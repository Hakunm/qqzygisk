package com.qm.qqzygisk.hook.parasitic.activity.delegate.impl

import android.os.Handler
import com.qm.qqzygisk.hook.parasitic.activity.delegate.HandlerDelegate_com_qm_qqtest

/**
 *  HandlerDelegateImpl 注入 Stub
 */
object HandlerDelegateImpl_Impl {

    val wrapperClassName get() = "com.qm.qqzygisk.hook.parasitic.activity.delegate.HandlerDelegate_com_qm_qqtest"

    fun createWrapper(baseInstance: Handler.Callback? = null): Handler.Callback =
        HandlerDelegate_com_qm_qqtest(baseInstance)
}