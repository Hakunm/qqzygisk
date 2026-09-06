package com.qm.qqzygisk.hook.app.hooker

import android.content.Context
import android.widget.Toast
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.chat.ChatMenu
import com.qm.qqzygisk.hook.app.chat.ChatMenuPosition
import com.qm.qqzygisk.hook.app.chat.ChatMenuType
import com.qm.qqzygisk.hook.app.chat.ChatPicOnScreen
import com.qm.qqzygisk.hook.app.chat.NtImageRkeyProvider
import com.qm.qqzygisk.hook.app.chat.NtPicResolver
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
        AntiRevokeHooker.retry()
    }

    private fun openSavePanel(
        context: Context,
        picElement: Any,
    ) {
        val sources = linkedSetOf<String>()
        runCatching {
            Log.info("保存图片 PicElement ${NtPicResolver.describe(picElement)}")
            val snapshot = NtImageRkeyProvider.snapshot()
            Log.info(
                "rkey ready=${snapshot != null} types=${snapshot?.byType?.keys} " +
                    "expires=${snapshot?.expiresAtMillis}",
            )
            sources.addAll(NtPicResolver.resolve(picElement))
        }.onFailure { Log.error("解析 PicElement 失败", it) }
        runCatching {
            ChatPicOnScreen.capture(context, picElement)?.let(sources::add)
        }.onFailure { Log.warn("截取聊天界面图片失败", it) }
        if (sources.isEmpty()) {
            Log.warn("PicElement 没有可用的本地路径或图片地址")
        } else {
            Log.info(
                "图片候选 ${sources.size} 个: " +
                    sources.joinToString(" | ") { it.take(180) },
            )
        }
        runCatching {
            SaveImagePanel.show(context, sources.toList(), forceSave = true)
        }.onFailure {
            Log.error("打开图片面板失败", it)
            Toast.makeText(context, "无法打开图片面板", Toast.LENGTH_SHORT).show()
        }
    }
}
