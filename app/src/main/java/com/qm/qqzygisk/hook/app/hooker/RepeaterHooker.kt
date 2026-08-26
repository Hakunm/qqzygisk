package com.qm.qqzygisk.hook.app.hooker

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.chat.ChatImageSender
import com.qm.qqzygisk.hook.app.chat.ChatMenu
import com.qm.qqzygisk.hook.app.chat.ChatMenuPosition
import com.qm.qqzygisk.hook.app.chat.ChatMenuType
import com.qm.qqzygisk.hook.app.chat.NtMsgAccess
import com.qm.qqzygisk.hook.app.chat.NtRepeater
import com.qm.qqzygisk.hook.extension.MemberHookCreator
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * QAuxiliary 的消息 +1。挂钩 NT 的 AIOMsgFollowComponent 旁路按钮，
 * 并在长按菜单补一项。设置里单独开关，默认关闭。
 */
object RepeaterHooker : BaseHooker() {
    override val key = "repeat_plus"
    override val name = "消息+1"
    override val description = "在消息旁显示 +1 按钮，点击把该条消息再发一遍。红包等不支持。"
    override val defaultEnabled = false

    private const val FOLLOW_COMPONENT =
        "com.tencent.mobileqq.aio.msglist.holder.component.msgfollow.AIOMsgFollowComponent"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedMethods = Collections.synchronizedSet(mutableSetOf<String>())
    private val imageViews = Collections.synchronizedMap(WeakHashMap<Any, ImageView>())

    @Volatile
    private var plusOneIcon: Drawable? = null

    private val enabled get() = HookSettings.isEnabled(key, defaultEnabled)

    override fun initOnce() {
        retry()
        ChatMenu.addMenuItem(
            title = "+1",
            type = ChatMenuType.Any,
            icon = R.drawable.ic_repeat_plus,
            visible = { enabled },
            position = ChatMenuPosition.Front,
        ) { context, msg ->
            if (isMultiForward(context)) return@addMenuItem
            ChatImageSender.captureFromContext(context)
            repeatMessage(msg, context)
        }
    }

    fun retry() {
        runCatching { hookFollowComponent() }
            .onFailure { Log.warn("repeat-plus follow hook failed", it) }
    }

    private fun hookFollowComponent() {
        val type = NtMsgAccess.loadClass(FOLLOW_COMPONENT)
        if (type == null) {
            Log.warn("repeat-plus missing $FOLLOW_COMPONENT")
            return
        }
        type.declaredMethods.forEach { method ->
            if (!shouldHook(method)) return@forEach
            hookOnce(method) {
                after { bindFollowButton(method, this.instance, this.args, this.result) }
            }
        }
        Log.info("repeat-plus hooked ${type.simpleName}")
    }

    private fun shouldHook(method: Method): Boolean {
        if (Modifier.isAbstract(method.modifiers) || Modifier.isStatic(method.modifiers) || method.isSynthetic) {
            return false
        }
        val types = method.parameterTypes
        val zeroArgImage = types.isEmpty() && ImageView::class.java.isAssignableFrom(method.returnType)
        val bindArgs = types.size == 3 &&
            (types[0] == Int::class.javaPrimitiveType || types[0] == java.lang.Integer::class.java) &&
            List::class.java.isAssignableFrom(types[2])
        return zeroArgImage || bindArgs
    }

    private fun bindFollowButton(method: Method, instance: Any, args: Array<Any?>, result: Any?) {
        if (args.isEmpty() && result is ImageView) {
            imageViews[instance] = result
            return
        }
        if (!enabled) return
        if (args.size < 2) return
        val msg = args[1] ?: return
        val imageView = findFollowImageView(instance) ?: imageViews[instance] ?: return
        if (isMultiForward(imageView.context)) {
            imageView.visibility = View.GONE
            return
        }
        imageViews[instance] = imageView
        imageView.setImageDrawable(plusOneDrawable(imageView.context))
        imageView.contentDescription = "+1"
        imageView.setOnClickListener { view ->
            if (!enabled) return@setOnClickListener
            ChatImageSender.captureFromContext(view.context)
            ChatImageSender.captureFrom(instance)
            ChatImageSender.updateAioParamFrom(instance)
            repeatMessage(msg, view.context)
        }
        imageView.visibility = View.VISIBLE
        Log.debug("repeat-plus bound ${method.name}")
    }

    private fun findFollowImageView(instance: Any): ImageView? {
        generateSequence(instance.javaClass) { it.superclass }.forEach { cls ->
            if (cls == Any::class.java) return@forEach
            for (field in cls.declaredFields) {
                field.isAccessible = true
                val value = runCatching { field.get(instance) }.getOrNull() ?: continue
                if (value is ImageView) return value
                val typeName = field.type.name
                if (typeName == "kotlin.Lazy" || typeName.contains("Lazy")) {
                    val lazyValue = NtMsgAccess.invokeNoArg(value, "getValue")
                    if (lazyValue is ImageView) return lazyValue
                }
            }
        }
        return null
    }

    private fun repeatMessage(msg: Any, context: Context) {
        NtRepeater.repeat(msg) { error -> toast(context, error) }
    }

    private fun plusOneDrawable(context: Context): Drawable {
        plusOneIcon?.let { return it }
        val density = context.resources.displayMetrics.density
        val size = (28 * density).toInt().coerceAtLeast(40)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF12B7F5.toInt()
            textSize = size * 0.52f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isFakeBoldText = true
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText("+1", size / 2f, y, paint)
        return BitmapDrawable(context.resources, bitmap).also { plusOneIcon = it }
    }

    private fun isMultiForward(context: Context): Boolean {
        var current: Context? = context
        while (current != null) {
            if (current.javaClass.name.contains("MultiForwardActivity")) return true
            current = (current as? ContextWrapper)?.baseContext
        }
        return false
    }

    private fun toast(context: Context, text: String) {
        mainHandler.post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hookOnce(method: Method, action: MemberHookCreator.() -> Unit) {
        val id = method.toGenericString()
        if (!hookedMethods.add(id)) return
        method.isAccessible = true
        method.hook(action)
    }
}
