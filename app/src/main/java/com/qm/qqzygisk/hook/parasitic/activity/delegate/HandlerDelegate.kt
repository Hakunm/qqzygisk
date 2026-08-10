package com.qm.qqzygisk.hook.parasitic.activity.delegate

import android.os.Handler
import android.os.Message
import androidx.annotation.Keep
import com.qm.qqzygisk.hook.parasitic.activity.delegate.caller.HandlerDelegateCaller

@Keep
class HandlerDelegate_com_qm_qqtest(private val baseInstance: Handler.Callback?) : Handler.Callback {

    override fun handleMessage(msg: Message) = HandlerDelegateCaller.callHandleMessage(baseInstance, msg)
}