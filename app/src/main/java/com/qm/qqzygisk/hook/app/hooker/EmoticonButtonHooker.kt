package com.qm.qqzygisk.hook.app.hooker

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.chat.SaveImagePanel
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.extension.hookAll
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log

/**
 * 长按 QQ 聊天输入栏表情图标，打开自定义图片面板。
 */
object EmoticonButtonHooker : BaseHooker() {
    override val key = "emoticon_image_panel"
    override val name = "长按表情按钮"
    override val description = "长按聊天框表情图标打开图片面板"
    override val defaultEnabled = true

    private val enabled get() = HookSettings.isEnabled(key, defaultEnabled)
    private const val ATTACHED_TAG = 0x51A1E201

    override fun initOnce() {
        View::class.resolve()
            .firstMethod {
                name = "setOnClickListener"
                parameters(View.OnClickListener::class)
            }
            .hook {
                after {
                    attachIfChatEmoticonIcon(instance as? View ?: return@after)
                }
            }

        View::class.resolve()
            .firstMethod {
                name = "setContentDescription"
                parameters(CharSequence::class)
            }
            .hook {
                after {
                    attachIfChatEmoticonIcon(instance as? View ?: return@after)
                }
            }

        Activity::class.resolve()
            .firstMethod { name = "onResume" }
            .hook {
                after {
                    val activity = instance as? Activity ?: return@after
                    attachById(activity)
                    activity.window?.decorView?.let(::scanTree)
                }
            }

        hookClass("com.tencent.qqnt.aio.shortcutbar.PanelIconLinearLayout")
        hookClass("com.tencent.mobileqq.aio.input.simpleui.AIOInputSimpleUIVBDelegate")
        hookClass("com.tencent.mobileqq.aio.input.simpleui.b")
    }

    private fun hookClass(className: String) {
        val type = runCatching { Class.forName(className, false, appClassLoader) }.getOrNull()
        if (type == null) {
            Log.warn("未找到输入栏类: $className")
            return
        }
        type.resolve()
            .method {
                name {
                    it.contains("View", ignoreCase = true) ||
                        it.contains("bind", ignoreCase = true) ||
                        it.contains("init", ignoreCase = true) ||
                        it.contains("FunBtn", ignoreCase = true) ||
                        it == "onFinishInflate" ||
                        it == "onAttachedToWindow" ||
                        it == "onLayout"
                }
            }
            .hookAll {
                after {
                    val owner = instance ?: return@after
                    if (owner is ViewGroup) scanTree(owner)
                    scanFields(owner)
                }
            }
        Log.info("已挂钩输入栏类: $className")
    }

    private fun attachById(activity: Activity) {
        val packageName = activity.packageName
        listOf("emo_btn", "input_emotion", "emotion_btn", "qq_aio_panel_emotion").forEach { name ->
            val id = activity.resources.getIdentifier(name, "id", packageName)
            if (id == 0) return@forEach
            activity.findViewById<View>(id)?.let(::forceAttach)
        }
    }

    private fun scanFields(owner: Any) {
        generateSequence(owner.javaClass) { it.superclass }.forEach { type ->
            type.declaredFields.forEach { field ->
                if (!View::class.java.isAssignableFrom(field.type)) return@forEach
                field.isAccessible = true
                val view = runCatching { field.get(owner) as? View }.getOrNull() ?: return@forEach
                attachIfChatEmoticonIcon(view)
                if (resourceName(view) == "emo_btn") forceAttach(view)
            }
        }
    }

    private fun scanTree(root: View) {
        attachIfChatEmoticonIcon(root)
        if (resourceName(root) == "emo_btn") forceAttach(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                scanTree(root.getChildAt(index))
            }
        }
    }

    private fun forceAttach(view: View) {
        view.setTag(ATTACHED_TAG, null)
        attachIfChatEmoticonIcon(view, force = true)
    }

    private fun attachIfChatEmoticonIcon(view: View, force: Boolean = false) {
        if (view.getTag(ATTACHED_TAG) == true) return
        if (!force && !isChatInputEmoticonIcon(view)) return
        view.setTag(ATTACHED_TAG, true)
        view.isLongClickable = true
        view.setOnLongClickListener {
            if (!enabled) {
                Log.warn("长按表情图标时开关是关闭的")
                return@setOnLongClickListener false
            }
            runCatching {
                SaveImagePanel.show(it.context)
            }.onFailure { error ->
                Log.error("打开图片面板失败", error)
            }
            true
        }
        Log.info(
            "已挂钩聊天框表情图标: class=${view.javaClass.name} " +
                "id=${resourceName(view)} desc=${view.contentDescription}",
        )
    }

    private fun isChatInputEmoticonIcon(view: View): Boolean {
        if (view !is ImageView && view !is ImageButton) return false
        val description = view.contentDescription?.toString()?.trim().orEmpty()
        if (description.contains("商城") || description.contains("搜索") || description.contains("回复")) {
            return false
        }
        if (description == "表情" || description.endsWith("表情")) return true
        val name = resourceName(view)
        return name == "emo_btn" ||
            name.contains("emo_btn") ||
            name.contains("emotion", ignoreCase = true) ||
            name.contains("emoticon", ignoreCase = true)
    }

    private fun resourceName(view: View): String {
        return runCatching {
            if (view.id == View.NO_ID) return@runCatching ""
            view.resources.getResourceEntryName(view.id)
        }.getOrNull().orEmpty()
    }
}
