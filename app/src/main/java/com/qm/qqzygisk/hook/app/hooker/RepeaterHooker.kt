package com.qm.qqzygisk.hook.app.hooker

import android.content.Context
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
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 按 QAuxiliary [cc.hicore.hook.RepeaterPlus] 的 NT 路径做消息 +1。
 *
 * QQ 9.2.30+：挂钩 [AIOMsgFollowComponent] 的 `(int, *, java.util.List)`，
 * 从 `kotlin.Lazy`（或类型名带 Lazy）取出 ImageView，改图标并 `setVisibility(VISIBLE)`。
 * 更早的 NT：再挂钩返回 ImageView 的无参方法，先存下 View，再在三参方法里绑点击。
 *
 * 不注入自定义按钮，只用 QQ 自己的 follow ImageView。
 */
object RepeaterHooker : BaseHooker() {
    override val key = "repeat_plus"
    override val name = "消息+1"
    override val description = "在消息旁显示 +1 按钮，点击把该条消息再发一遍。红包等不支持。"
    override val defaultEnabled = false

    private const val FOLLOW_COMPONENT =
        "com.tencent.mobileqq.aio.msglist.holder.component.msgfollow.AIOMsgFollowComponent"

    private val javaListType: Class<*> = java.util.List::class.java
    private val hookedMethods = Collections.synchronizedSet(mutableSetOf<String>())
    private val lastImageView = Collections.synchronizedMap(WeakHashMap<Any, ImageView>())
    private val menuInstalled = AtomicBoolean(false)
    private val retriesScheduled = AtomicBoolean(false)
    private val missingLogged = AtomicBoolean(false)
    private val emptyDumpLogged = AtomicBoolean(false)

    @Volatile
    private var plusOneIcon: Drawable? = null

    private val enabled get() = HookSettings.isEnabled(key, defaultEnabled)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun initOnce() {
        ensureMenuItem()
        retry()
        scheduleRetries()
    }

    fun retry() {
        runCatching { hookFollowComponent() }
            .onFailure { Log.warn("repeat-plus follow hook failed", it) }
    }

    private fun scheduleRetries() {
        if (!retriesScheduled.compareAndSet(false, true)) return
        listOf(1_500L, 5_000L, 15_000L).forEach { delayMs ->
            mainHandler.postDelayed({ retry() }, delayMs)
        }
    }

    private fun ensureMenuItem() {
        if (!menuInstalled.compareAndSet(false, true)) return
        runCatching {
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
        }.onFailure {
            menuInstalled.set(false)
            Log.warn("repeat-plus menu failed", it)
        }
    }

    private fun hookFollowComponent() {
        if (hookedMethods.isNotEmpty()) return
        val type = NtMsgAccess.loadClass(FOLLOW_COMPONENT)
        if (type == null) {
            if (missingLogged.compareAndSet(false, true)) {
                Log.warn("repeat-plus missing $FOLLOW_COMPONENT")
            }
            return
        }
        var hooked = 0
        type.declaredMethods.forEach { method ->
            if (!isRepeaterPlusMethod(method)) return@forEach
            if (!hookOnce(method)) return@forEach
            hooked++
        }
        if (hooked == 0 && hookedMethods.isEmpty()) {
            if (emptyDumpLogged.compareAndSet(false, true)) {
                val dump = type.declaredMethods.joinToString("\n") { method ->
                    val params = method.parameterTypes.joinToString { it.name }
                    "${method.name}($params): ${method.returnType.name}"
                }
                Log.warn("repeat-plus no matching methods on ${type.name}:\n$dump")
            }
            return
        }
        if (hooked > 0) {
            Log.info("repeat-plus hooked ${type.simpleName} methods=$hooked total=${hookedMethods.size}")
        }
    }

    /**
     * QAuxiliary RepeaterPlus：
     * - 9.2.30+：`pts.length == 3 && pts[0].equals(int.class) && pts[2].equals(List.class)`
     * - 8.9.63+：再加 `parameterTypes.length == 0 && returnType.equals(ImageView.class)`
     *
     * 不要求第二参是 AIOMsgItem，也不过滤 bridge / synthetic。
     */
    private fun isRepeaterPlusMethod(method: Method): Boolean {
        val types = method.parameterTypes
        val threeArg = types.size == 3 &&
            types[0] == Int::class.javaPrimitiveType &&
            types[2] == javaListType
        val zeroArgImage = types.isEmpty() && method.returnType == ImageView::class.java
        return threeArg || zeroArgImage
    }

    private fun hookOnce(method: Method): Boolean {
        val id = method.toGenericString()
        if (!hookedMethods.add(id)) return false
        return runCatching {
            method.isAccessible = true
            method.hook {
                after { onFollowMethod(instance, args, result) }
            }
            Log.info("repeat-plus hook ${method.toGenericString()}")
            true
        }.onFailure {
            hookedMethods.remove(id)
            Log.warn("repeat-plus hook ${method.name} failed", it)
        }.getOrDefault(false)
    }

    private fun onFollowMethod(instance: Any, args: Array<Any?>, result: Any?) {
        if (args.isEmpty() && result is ImageView) {
            lastImageView[instance] = result
            if (enabled) {
                result.setImageDrawable(plusOneDrawable(result.context))
            }
            return
        }
        if (!enabled) return
        if (args.size != 3 || args[0] !is Int || args[2] !is java.util.List<*>) return

        val imageView = findFollowImageView(instance) ?: lastImageView[instance] ?: return
        if (isMultiForward(imageView.context)) {
            imageView.visibility = View.GONE
            return
        }
        lastImageView[instance] = imageView
        imageView.setImageDrawable(plusOneDrawable(imageView.context))
        val msg = args[1]
        imageView.setOnClickListener { view ->
            if (!enabled) return@setOnClickListener
            ChatImageSender.captureFromContext(view.context)
            ChatImageSender.captureFrom(instance)
            ChatImageSender.updateAioParamFrom(instance)
            repeatMessage(msg, view.context)
        }
        imageView.visibility = View.VISIBLE
    }

    /**
     * QAuxiliary XField：第一个类型为 `kotlin.Lazy` 或名字带 Lazy 的字段，再 `getValue()`。
     * 这里继续找直到取出 ImageView，避免第一个 Lazy 不是 follow 按钮。
     */
    private fun findFollowImageView(instance: Any): ImageView? {
        generateSequence(instance.javaClass) { it.superclass }.forEach { cls ->
            if (cls == Any::class.java) return@forEach
            for (field in cls.declaredFields) {
                if (!isLazyField(field)) continue
                field.isAccessible = true
                val lazy = runCatching { field.get(instance) }.getOrNull() ?: continue
                val value = lazyGetValue(lazy)
                if (value is ImageView) return value
            }
        }
        return null
    }

    private fun isLazyField(field: Field): Boolean {
        val typeName = field.type.name
        return typeName == "kotlin.Lazy" || typeName.contains("Lazy")
    }

    private fun lazyGetValue(lazy: Any): Any? {
        val methods = buildList {
            generateSequence(lazy.javaClass) { it.superclass }.forEach { cls ->
                addAll(cls.declaredMethods)
                cls.interfaces.forEach { addAll(it.declaredMethods) }
            }
        }
        val getValue = methods.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
            ?: methods.firstOrNull { it.name == "get" && it.parameterCount == 0 }
            ?: return NtMsgAccess.invokeNoArg(lazy, "getValue")
        getValue.isAccessible = true
        return runCatching { getValue.invoke(lazy) }.getOrNull()
    }

    private fun repeatMessage(msg: Any?, context: Context) {
        if (!NtRepeater.isRepeatable(msg)) {
            toast(context, "该消息不支持复读")
            return
        }
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

    private fun isMultiForward(context: Context): Boolean =
        context.javaClass.name.contains("MultiForwardActivity")

    private fun toast(context: Context, text: String) {
        mainHandler.post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
}
