package com.qm.qqzygisk.hook.app.hooker

import android.view.View
import android.widget.ImageView
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 微信 8.0.79/3182、8.0.80/3220 在初始化时直接绑定 ChatFooter 的表情按钮。 */
internal object WeChatEmoticonButtonHooker {
    private val installed = mutableSetOf<Method>()
    private var failureLogged = false

    @Synchronized
    fun install(): Boolean = try {
        val footer = Class.forName("com.tencent.mm.pluginsdk.ui.chat.ChatFooter", false, appClassLoader)
        val initialize = footer.getDeclaredMethod("y0").apply {
            check(!Modifier.isStatic(modifiers) && returnType == Void.TYPE)
            isAccessible = true
        }
        if (initialize !in installed) {
            val button = footer.getField("E").apply {
                check(!Modifier.isStatic(modifiers) && ImageView::class.java.isAssignableFrom(type))
            }
            initialize.hook {
                after {
                    val view = button.get(instance) as? View ?: return@after
                    EmoticonButtonHooker.attachToView(view)
                }
            }
            installed += initialize
            Log.info("微信聊天表情按钮定位已安装：ChatFooter.y0")
        }
        true
    } catch (error: Throwable) {
        if (!failureLogged) {
            failureLogged = true
            Log.warn("微信聊天表情按钮定位未安装（${error.javaClass.simpleName}），保留通用图标识别")
        }
        false
    }
}
