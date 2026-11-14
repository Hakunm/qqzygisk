package com.qm.qqzygisk.hook.app.hooker

import android.content.SharedPreferences
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.chat.ChatTextSimplifier
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** 只处理 QQ 的消息正文控件；不写入消息模型。 */
object ChineseSimplifyHooker : BaseHooker() {
    override val key = "simplify_chat_text"
    override val name = "聊天消息繁体转简体"
    override val description = "仅转换聊天正文的本机显示，不改原消息或发送内容；链接、@ 和表情保持原样。"
    override val defaultEnabled = false

    private class State {
        var original: CharSequence? = null
        var displayed: CharSequence? = null
        var actualText: CharSequence? = null
        var bufferType: TextView.BufferType? = null
        var originalDrawable: Any? = null
        var hasDrawable = false
        var drawableInput: CharSequence? = null
        var drawableDisplay: CharSequence? = null
    }
    private class FontSource(info: Any) {
        val info = WeakReference(info)
    }

    private val views = WeakHashMap<View, Unit>()
    private val fonts = WeakHashMap<Any, FontSource>()
    private val refreshing = ThreadLocal<Boolean>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var infoClass: Class<*>
    private lateinit var infoConstructor: Constructor<*>
    private lateinit var factory: Method
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
        if (changedKey == key) handler.post { refreshViews() }
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            view.post { refreshView(view) }
        }
        override fun onViewDetachedFromWindow(view: View) = Unit
    }

    override fun initOnce() {
        val normal = "com.tencent.qqnt.aio.widget.AIOMsgTextView".toAppClass()
        val faster = runCatching { "com.tencent.qqnt.aio.widget.AIOMsgFasterTextView".toAppClass() }.getOrNull()
        val fontType = "com.tencent.mobileqq.vas.font.a".toAppClass()
        infoClass = "com.tencent.mobileqq.vas.font.IFontManager\$c".toAppClass()
        val manager = "com.tencent.mobileqq.vas.font.impl.FontManagerImpl".toAppClass()
        factory = manager.getDeclaredMethod("getFontDrawable", infoClass).apply { isAccessible = true }
        infoConstructor = infoClass.declaredConstructors.first {
            val p = it.parameterTypes
            p.size >= 7 && p[0] == CharSequence::class.java && p[1] == Int::class.javaPrimitiveType &&
                p[2] == Int::class.javaPrimitiveType && p[3] == Boolean::class.javaPrimitiveType &&
                p[4] == String::class.java && p[5] == Long::class.javaPrimitiveType &&
                p[6] == Long::class.javaPrimitiveType && (p.size == 7 || p.size == 9)
        }.apply { isAccessible = true }
        val widgets = listOfNotNull(normal, faster).map { type ->
            Triple(
                type.getDeclaredMethod("setText", CharSequence::class.java, TextView.BufferType::class.java),
                if (type === normal) type.getDeclaredMethod("setTextDrawable", fontType, CharSequence::class.java)
                else type.getDeclaredMethod("setTextDrawable", fontType),
                type.getMethod("getText"),
            )
        }
        val content = "com.tencent.mobileqq.aio.msg.TextMsgContent".toAppClass()
        val message = "com.tencent.mobileqq.aio.msg.ao".toAppClass()
        val bind = content.getDeclaredMethod("e", message)
        val contentView = content.getDeclaredMethod("i").apply { isAccessible = true }
        bind.hook {
            before {
                try {
                    val view = contentView.invoke(instance) as? View ?: return@before
                    if (view.javaClass === normal || view.javaClass === faster) stateFor(view)
                } catch (_: Throwable) {
                    Log.warn("聊天正文控件绑定失败")
                }
            }
        }
        factory.hook {
            after {
                val drawable = result
                val info = args.firstOrNull()
                if (drawable is Drawable && info != null) {
                    synchronized(fonts) { fonts[drawable] = FontSource(info) }
                }
            }
        }
        widgets.forEach { (setText, setDrawable, getText) ->
            setText.hook {
                before {
                    if (refreshing.get() == true) return@before
                    try {
                        val view = instance as? View ?: return@before
                        val state = view.getTag(R.id.chat_text_simplifier_state) as? State ?: return@before
                        val input = args[0] as? CharSequence
                        if (input == null) {
                            state.original = null
                            state.displayed = null
                            state.actualText = null
                            state.originalDrawable = null
                            state.hasDrawable = false
                            state.drawableInput = null
                            state.drawableDisplay = null
                        } else {
                            val original = when {
                                input === state.drawableDisplay && state.drawableInput != null -> state.drawableInput!!
                                (input === state.displayed || input === state.actualText) && state.original != null -> state.original!!
                                else -> input
                            }
                            state.original = original
                            state.displayed = input
                            state.actualText = null
                            state.bufferType = args[1] as? TextView.BufferType
                            val display = displayText(original)
                            state.displayed = display
                            args[0] = display
                        }
                    } catch (_: Throwable) {
                        Log.warn("聊天消息繁简转换失败，保留宿主文本")
                    }
                }
                after {
                    if (refreshing.get() != true) {
                        try {
                            val view = instance as? View ?: return@after
                            (view.getTag(R.id.chat_text_simplifier_state) as? State)?.actualText =
                                getText.invoke(view) as? CharSequence
                        } catch (_: Throwable) {
                            Log.warn("聊天消息显示状态读取失败")
                        }
                    }
                }
            }
            setDrawable.hook {
                before {
                    if (refreshing.get() == true) return@before
                    try {
                        val view = instance as? View ?: return@before
                        val state = view.getTag(R.id.chat_text_simplifier_state) as? State ?: return@before
                        val originalDrawable = args[0]
                        state.originalDrawable = originalDrawable
                        state.hasDrawable = true
                        val input = if (args.size > 1) args[1] as? CharSequence else null
                        state.drawableInput = input
                        state.drawableDisplay = input
                        val replacement = displayDrawable(originalDrawable)
                        val display = input?.let(::displayText)
                        state.drawableDisplay = display
                        args[0] = replacement
                        if (args.size > 1) args[1] = display
                    } catch (_: Throwable) {
                        Log.warn("聊天字体转换失败，保留宿主字体")
                    }
                }
            }
        }
        HookSettings.registerChangeListener(listener)
    }

    private fun enabled() = HookSettings.isEnabled(key, defaultEnabled)

    private fun displayText(text: CharSequence): CharSequence =
        if (enabled()) ChatTextSimplifier.convert(text) else text

    private fun stateFor(view: View): State {
        val existing = view.getTag(R.id.chat_text_simplifier_state) as? State
        if (existing != null) return existing
        return State().also {
            view.setTag(R.id.chat_text_simplifier_state, it)
            synchronized(views) { views[view] = Unit }
            view.addOnAttachStateChangeListener(attachListener)
        }
    }

    private fun displayDrawable(drawable: Any?): Any? {
        if (drawable == null || !enabled()) return drawable
        val info = synchronized(fonts) { fonts[drawable]?.info?.get() }
            ?: findFontInfo(drawable) ?: error("font descriptor unavailable")
        val originalText = infoClass.getDeclaredMethod("k").invoke(info) as CharSequence
        val display = ChatTextSimplifier.convert(originalText)
        if (display === originalText) return drawable
        fun value(method: String): Any? = infoClass.getDeclaredMethod(method).invoke(info)
        val args = mutableListOf<Any?>(display, value("c"), value("d"), value("t"), value("q"), value("a"), value("l"))
        if (infoConstructor.parameterCount == 9) {
            args.add(0)
            args.add(null)
        }
        val copy = infoConstructor.newInstance(*args.toTypedArray())
        for (field in infoClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers) || Modifier.isFinal(field.modifiers) || field.name == "a") continue
            field.isAccessible = true
            val original = field.get(info)
            val cloned = when (original) {
                is Paint -> Paint(original)
                is IntArray -> original.clone()
                else -> original
            }
            field.set(copy, cloned)
        }
        val constructor = drawable.javaClass.getDeclaredConstructor(infoClass).apply { isAccessible = true }
        return constructor.newInstance(copy)
    }

    private fun findFontInfo(drawable: Any): Any? {
        fun direct(owner: Any): Any? {
            val field = owner.javaClass.declaredFields.firstOrNull { it.type == infoClass } ?: return null
            field.isAccessible = true
            return field.get(owner)
        }
        direct(drawable)?.let { return it }
        // FontAnimDrawable retains the descriptor in its target-drawable builder.
        val builder = runCatching { drawable.javaClass.getMethod("getTargetDrawableBuild").invoke(drawable) }.getOrNull()
        return builder?.let(::direct)
    }

    private fun refreshViews() {
        val snapshot = synchronized(views) { views.keys.toList() }
        snapshot.forEach { if (it.isAttachedToWindow) refreshView(it) }
    }

    private fun refreshView(view: View) {
        if (!view.isAttachedToWindow) return
        val state = view.getTag(R.id.chat_text_simplifier_state) as? State ?: return
        val original = state.original ?: return
        try {
            val type = view.javaClass
            val setText = type.getDeclaredMethod("setText", CharSequence::class.java, TextView.BufferType::class.java)
            val fontType = "com.tencent.mobileqq.vas.font.a".toAppClass()
            val setDrawable = if (type.name == "com.tencent.qqnt.aio.widget.AIOMsgTextView") {
                type.getDeclaredMethod("setTextDrawable", fontType, CharSequence::class.java)
            } else {
                type.getDeclaredMethod("setTextDrawable", fontType)
            }
            val text = displayText(original)
            val drawable = if (state.hasDrawable) displayDrawable(state.originalDrawable) else null
            refreshing.set(true)
            try {
                setText.invoke(view, text, state.bufferType)
                state.displayed = text
                state.actualText = type.getMethod("getText").invoke(view) as? CharSequence
                if (state.hasDrawable) {
                    if (setDrawable.parameterCount == 2) setDrawable.invoke(view, drawable, null)
                    else setDrawable.invoke(view, drawable)
                }
            } finally {
                refreshing.remove()
            }
        } catch (_: Throwable) {
            Log.warn("聊天消息显示刷新失败")
        }
    }
}
