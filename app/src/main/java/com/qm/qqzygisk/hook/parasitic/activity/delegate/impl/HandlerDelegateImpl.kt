package com.qm.qqzygisk.hook.parasitic.activity.delegate.impl

import android.os.Handler
import android.os.Handler.Callback


internal object HandlerDelegateImpl {

    /**
     * 获取 [Handler.Callback] 实例 [Class] 名称
     * @return [String]
     */
    internal val wrapperClassName get() = HandlerDelegateImpl_Impl.wrapperClassName

    /**
     * 从 [Handler.Callback] 创建实例
     * @param baseInstance [Handler.Callback] 实例 - 可空
     * @return [Handler.Callback]
     */
    internal fun createWrapper(baseInstance: Handler.Callback? = null) = HandlerDelegateImpl_Impl.createWrapper(baseInstance)
}
