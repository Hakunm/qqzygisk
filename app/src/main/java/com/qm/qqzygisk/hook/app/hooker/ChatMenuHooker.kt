package com.qm.qqzygisk.hook.app.hooker

import android.content.Context
import android.widget.Toast
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.chat.ChatMenu
import com.qm.qqzygisk.hook.app.chat.ChatMenuPosition
import com.qm.qqzygisk.hook.app.chat.ChatMenuType
import com.qm.qqzygisk.hook.app.chat.NtImageRkeyProvider
import com.qm.qqzygisk.hook.app.chat.SaveImagePanel
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log

object ChatMenuHooker : BaseHooker() {
    override val key = "chat_menu_entry"
    override val name = "聊天长按保存图片"
    override val description = "在含图片的消息长按菜单中保存图片"
    override val defaultEnabled = false

    private val enabled get() = HookSettings.isEnabled(key, defaultEnabled)

    override fun initOnce() {
        ChatMenu.addMenuItem(
            title = "保存",
            type = ChatMenuType.Pic,
            icon = R.drawable.ic_save,
            visible = { enabled },
            position = ChatMenuPosition.Front,
        ) { context, element ->
            openSavePanel(context, element)
        }
        runCatching {
            NtImageRkeyProvider.installHook()
        }.onFailure {
            Log.error("安装 NT 图片 rkey hook 失败", it)
        }
    }

    private fun openSavePanel(context: Context, picElement: Any) {
        runCatching {
            SaveImagePanel.show(context, resolveImageUrls(picElement))
        }.onFailure {
            Log.error("打开图片面板失败", it)
            Toast.makeText(context, "无法打开图片面板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveImageUrls(picElement: Any): List<String> {
        val originUrl = invokeStringGetter(picElement, "getOriginImageUrl").orEmpty()
        val legacyUrl = invokeStringGetter(picElement, "getMd5HexStr")
            ?.takeIf(String::isNotBlank)
            ?.let { "https://gchat.qpic.cn/gchatpic_new/0/0-0-${it.uppercase()}/0" }
        val candidates = when {
            originUrl.startsWith("https://") || originUrl.startsWith("http://") -> {
                listOf(NtImageRkeyProvider.sign(originUrl))
            }

            originUrl.startsWith("/download") -> {
                val ntUrl = "https://multimedia.nt.qq.com.cn$originUrl"
                listOf(NtImageRkeyProvider.sign(ntUrl), ntUrl)
            }

            originUrl.startsWith("/") -> listOf("https://gchat.qpic.cn$originUrl")
            else -> emptyList()
        }
        return (candidates + listOfNotNull(legacyUrl)).distinct()
    }

    private fun invokeStringGetter(instance: Any, methodName: String): String? {
        return runCatching {
            instance.javaClass.getMethod(methodName).invoke(instance) as? String
        }.getOrNull()
    }
}
