package com.qm.qqzygisk.hook.app.hooker

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HookSettings

object MsgFontHooker : BaseHooker() {
    override val key = "default_font"
    private val VASMsgFont = "com.tencent.qqnt.kernel.nativeinterface.VASMsgFont".toAppClass()

    override val name: String = "强制使用默认字体"

    override fun initOnce() {
        VASMsgFont.resolve()
            .firstConstructor{ parameterCount = 5 }
            .hook {
                before {
                    if (HookSettings.isEnabled(key, defaultEnabled)) {
                        this.args[0] = 0
                    }
                }
            }
    }
}
