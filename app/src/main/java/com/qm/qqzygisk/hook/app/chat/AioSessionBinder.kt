package com.qm.qqzygisk.hook.app.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HostClassScanner
import com.qm.qqzygisk.hook.utils.HostTargetCache
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ShapeSelector
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * 把 AIO 聊天根 View 绑定到创建它的 ChatPie 实例，并在长按/发送时从用户实际触摸的 View
 * 现场解析当前会话联系人。
 *
 * 设计要点：
 * - 不缓存“当前会话”。每次解析都从触摸的 View 向上找到绑定根，再实时读取该 ChatPie 的
 *   AIOParam 字段，因此切换聊天/复用 View 不会用到旧会话。
 * - 首次按类型/结构定位成员；后续同版本从 JSON 直接恢复类和成员，不重新筛选。
 *   定位包含 AIOParam 字段、创建根方法、AIOSession 字段及 AIOUtil 的会话转换器。
 *   首次匹配不唯一时显式失败；同版本恢复失败不重新定位。
 * - 只服务仍然 attached、shown、且所属窗口可见（windowVisibility==VISIBLE）、
 *   并且其所有者当前根字段仍指向该 View 的绑定根；预加载/隐藏/已解绑/已停止 Activity 的根一律不服务。
 * - 面板打开时记录会话身份，发送前重新解析并比对：聊天已切换则明确失败，绝不静默发给新会话。
 */
internal object AioSessionBinder {
    private const val CHAT_PIE_PACKAGE = "com.tencent.aio.base.chat"
    private const val AIO_PARAM_CLASS = "com.tencent.aio.data.AIOParam"
    private const val AIO_SESSION_CLASS = "com.tencent.aio.data.AIOSession"
    private const val AIO_UTIL_CLASS = "com.tencent.mobileqq.aio.utils.AIOUtil"
    private const val CONTACT_CLASS = "com.tencent.qqnt.kernelpublic.nativeinterface.Contact"

    /**
     * 宿主自己的 `androidx.fragment.app.Fragment`。
     *
     * 模块类加载器优先加载模块内自带的 androidx，模块里的 Fragment 与宿主 Fragment 是两个不同的
     * Class 身份，直接比较会永远不匹配；因此必须用宿主类加载器解析。
     */
    private const val FRAGMENT_CLASS = "androidx.fragment.app.Fragment"

    /** 已绑定根 View -> 创建它的 ChatPie。键为弱引用，避免持有已销毁聊天的视图。 */
    private val roots = Collections.synchronizedMap(WeakHashMap<View, WeakReference<Any>>())

    @Volatile
    private var binding: Binding? = null

    private class Binding(
        val chatPie: Class<*>,
        val createRoot: Method,
        val rootField: Field,
        val aioParamField: Field,
        val aioSessionField: Field,
        val converterOwner: Any,
        val converter: Method,
    )

    /** 发送前实时解析会话的结果。 */
    sealed interface LiveContact {
        /** 当前会话联系人；与面板打开时同一会话。 */
        class Ready internal constructor(val contact: Any) : LiveContact

        /** 绑定根不可见或没有可用会话。 */
        data object Unavailable : LiveContact

        /** 面板打开后聊天已切换（或根被复用给别的会话）。 */
        data object Changed : LiveContact
    }

    /**
     * 绑定到具体聊天 View 的会话句柄。
     *
     * 创建时记录当时的会话身份；每次取联系人都重新校验根可见性与身份是否仍然一致。
     */
    class BoundSession internal constructor(
        private val rootRef: WeakReference<View>,
        private val openedIdentity: SessionIdentity,
    ) {
        /**
         * 重新解析当前会话联系人。
         * 根不可见/已解绑/无会话时返回 [LiveContact.Unavailable]，
         * 会话已切换到别的聊天时返回 [LiveContact.Changed]。
         */
        fun liveContact(): LiveContact {
            val root = rootRef.get() ?: return LiveContact.Unavailable.also {
                Log.warn("QQ 会话不可用: 面板绑定的聊天根已释放")
            }
            val contact = AioSessionBinder.contactOf(root, diagnose = true) ?: return LiveContact.Unavailable
            if (AioSessionBinder.identityOf(contact) != openedIdentity) return LiveContact.Changed
            return LiveContact.Ready(contact)
        }
    }

    /** 会话身份（聊天类型 + 会话标识）。只用于比对，刻意不提供 toString，避免标识进入日志。 */
    internal class SessionIdentity(
        private val chatType: Int,
        private val peerUid: String,
        private val guildId: String,
    ) {
        override fun equals(other: Any?): Boolean =
            other is SessionIdentity &&
                other.chatType == chatType &&
                other.peerUid == peerUid &&
                other.guildId == guildId

        override fun hashCode(): Int = (chatType * 31 + peerUid.hashCode()) * 31 + guildId.hashCode()
    }

    /**
     * 在宿主类加载器可用、聊天尚未创建时安装根绑定 hook。可重复调用：
     * 安装失败（宿主类尚不可加载）不会置位，后续调用会重试。
     */
    fun install() {
        if (binding != null) return
        synchronized(this) {
            if (binding != null) return
            val resolved = resolve()
            resolved.createRoot.hook {
                after {
                    val root = result as? View ?: return@after
                    val owner = instance
                    roots[root] = WeakReference(owner)
                    Log.info("AIO 聊天根已绑定: owner=${owner.javaClass.simpleName} root=${root.javaClass.simpleName}")
                }
            }
            binding = resolved
            Log.info("AIO 会话绑定已安装: chatPie=${resolved.chatPie.name}")
        }
    }

    /** 从被触摸的 View 向上找最近的、仍然有效的绑定根，并记录当时的会话身份。 */
    fun sessionFor(view: View?): BoundSession? {
        if (binding == null || view == null) {
            Log.warn("QQ 打开面板时无法绑定会话: hookReady=${binding != null} viewReady=${view != null}")
            return null
        }
        if (!view.isLiveInWindow()) {
            Log.warn("QQ 打开面板时触摸视图不可用: attached=${view.isAttachedToWindow} shown=${view.isShown} window=${view.windowVisibility}")
            return null
        }
        var current: View? = view
        while (current != null) {
            if (roots.containsKey(current)) {
                openedSession(current)?.let { return it }
            }
            current = current.parent as? View
        }
        Log.warn("QQ 触摸位置没有可用的 AIO 根绑定: view=${view.javaClass.name}")
        return null
    }

    /** 从 Context 所属 Activity 的窗口中找唯一的可见绑定根；无法唯一确定时返回 null。 */
    fun sessionFor(context: Context?): BoundSession? {
        if (binding == null) return null
        val decor = context?.findActivity()?.window?.decorView ?: return null
        val candidates = synchronized(roots) { roots.keys.toList() }
            .filter { isWithin(it, decor) && contactOf(it) != null }
        if (candidates.size != 1) {
            if (candidates.isNotEmpty()) {
                Log.warn("当前窗口内匹配到 ${candidates.size} 个聊天会话，无法确定目标")
            }
            return null
        }
        return openedSession(candidates.single())
    }

    /** 打开面板时的会话句柄：联系人可解析、身份可记录才算有效。 */
    private fun openedSession(root: View): BoundSession? {
        val contact = contactOf(root, diagnose = true) ?: return null
        val identity = identityOf(contact) ?: run {
            Log.warn("QQ 打开面板时会话身份尚不可用")
            return null
        }
        return BoundSession(WeakReference(root), identity)
    }

    private fun contactOf(root: View, diagnose: Boolean = false): Any? {
        fun unavailable(reason: String): Any? {
            if (diagnose) Log.warn("QQ 会话不可用: $reason")
            return null
        }
        val current = binding ?: return unavailable("AIO hook 尚未安装")
        if (!root.isLiveInWindow()) return unavailable(
            "聊天根未在可见窗口: attached=${root.isAttachedToWindow} shown=${root.isShown} window=${root.windowVisibility}",
        )
        val owner = synchronized(roots) { roots[root]?.get() } ?: return unavailable("聊天根缺少存活的 ChatPie")
        if (readField(current.rootField, owner) !== root) return unavailable("ChatPie 已更换聊天根")
        val aioParam = readField(current.aioParamField, owner) ?: return unavailable("AIOParam 尚未就绪")
        val aioSession = readField(current.aioSessionField, aioParam) ?: return unavailable("AIOSession 尚未就绪")
        return runCatching { current.converter.invoke(current.converterOwner, aioSession) }
            .onFailure { Log.warn("解析当前会话联系人失败", it) }
            .getOrNull()
    }

    /** 会话身份：取自 Contact 的稳定 getter；空 UID 或非法聊天类型视为不可用（返回 null）。 */
    private fun identityOf(contact: Any): SessionIdentity? {
        val chatType = intGetter(contact, "getChatType") ?: return null
        if (chatType <= 0) return null
        val peerUid = stringGetter(contact, "getPeerUid")?.takeIf { it.isNotBlank() } ?: return null
        val guildId = stringGetter(contact, "getGuildId").orEmpty()
        return SessionIdentity(chatType, peerUid, guildId)
    }

    private fun intGetter(target: Any, name: String): Int? =
        (getter(target, name)?.let { runCatching { it.invoke(target) }.getOrNull() } as? Number)?.toInt()

    private fun stringGetter(target: Any, name: String): String? =
        getter(target, name)?.let { runCatching { it.invoke(target) }.getOrNull() } as? String

    private fun getter(target: Any, name: String): Method? =
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }

    /** 视图可用：已挂到窗口、自身与祖先可见，且所属窗口可见（Activity 已停止时不服务）。 */
    private fun View.isLiveInWindow(): Boolean =
        isAttachedToWindow && isShown && windowVisibility == View.VISIBLE

    private fun resolve(): Binding = HostTargetCache.resolve("qq-aio-session", appClassLoader) { targets ->
        val aioParamType by lazy { loadClass(AIO_PARAM_CLASS) }
        val fragmentBase by lazy { loadClass(FRAGMENT_CLASS) }
        val chatPie = targets.type("chatPie") { resolveChatPie(aioParamType, fragmentBase) }
        val createRoot = targets.method("createRoot") {
            chatPie.declaredMethods.first { isCreateRoot(it, fragmentBase) }
        }
        val rootField = targets.field("rootField") { uniqueField(chatPie, View::class.java, "聊天根 View 字段") }
        val aioParamField = targets.field("aioParamField") { uniqueField(chatPie, aioParamType, "AIOParam 字段") }
        val aioSessionField = targets.field("aioSessionField") {
            uniqueField(aioParamType, loadClass(AIO_SESSION_CLASS), "AIOSession 字段")
        }
        val converter by lazy { resolveSessionConverter() }
        val converterOwner = targets.field("converterOwner") { converter.first }
        val converterMethod = targets.method("converter") { converter.second }
        Binding(
            chatPie = chatPie,
            createRoot = createRoot,
            rootField = rootField,
            aioParamField = aioParamField,
            aioSessionField = aioSessionField,
            converterOwner = converterOwner.get(null) ?: error("$AIO_UTIL_CLASS 单例为空"),
            converter = converterMethod,
        )
    }

    /**
     * 聊天根类：包 [CHAT_PIE_PACKAGE] 内同时满足
     * 「唯一 AIOParam 实例字段」+「唯一 (Fragment,LayoutInflater,ViewGroup,boolean)->View 实例方法」的类。
     * 0 个或多个命中都显式失败。
     */
    private fun resolveChatPie(
        aioParamType: Class<*>,
        fragmentBase: Class<*>,
    ): Class<*> {
        val loaded = HostClassScanner.classesInPackage(CHAT_PIE_PACKAGE).mapNotNull { HostClassScanner.load(it) }
        return ShapeSelector.pickUnique(
            "AIO 聊天根类",
            loaded,
            describe = { it.name },
            predicate = { type -> matchesChatPie(type, aioParamType, fragmentBase) },
        ).found()
    }

    /**
     * 聊天根类形状：恰好一个 AIOParam 实例字段 + 恰好一个创建根方法。
     * 成员改名不影响判定；0 个或多个都不算命中。
     */
    internal fun matchesChatPie(
        type: Class<*>,
        aioParamType: Class<*>,
        fragmentBase: Class<*>,
    ): Boolean =
        instanceFields(type, aioParamType).size == 1 && createRootMethods(type, fragmentBase).size == 1

    /**
     * 创建聊天根视图的方法：返回 View，参数为 (Fragment, LayoutInflater, ViewGroup, boolean)。
     * 首参按 [fragmentBase]（宿主 Fragment）可赋值判定，避免依赖具体 ChatFragment 类名与模块自带副本；
     * 其余参数为框架类，精确比对。
     */
    internal fun isCreateRoot(
        method: Method,
        fragmentBase: Class<*>,
    ): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            method.returnType == View::class.java &&
            types.size == 4 &&
            fragmentBase.isAssignableFrom(types[0]) &&
            types[1] == LayoutInflater::class.java &&
            types[2] == ViewGroup::class.java &&
            types[3] == Boolean::class.javaPrimitiveType
    }

    private fun instanceFields(
        type: Class<*>,
        fieldType: Class<*>,
    ): List<Field> = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type == fieldType }

    private fun createRootMethods(
        type: Class<*>,
        fragmentBase: Class<*>,
    ): List<Method> = type.declaredMethods.filter { isCreateRoot(it, fragmentBase) }

    /**
     * AIOUtil 的 (AIOSession)->Contact 转换器。
     * 单例通过「类型为自身的静态字段」结构化取得，转换方法按签名唯一选取。
     */
    private fun resolveSessionConverter(): Pair<Field, Method> {
        val utilType = loadClass(AIO_UTIL_CLASS)
        val sessionType = loadClass(AIO_SESSION_CLASS)
        val contactType = loadClass(CONTACT_CLASS)
        val singletons = utilType.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == utilType }
        check(singletons.size == 1) { "$AIO_UTIL_CLASS 单例字段定位失败: ${singletons.size} 个" }
        val ownerField = singletons.single().apply { isAccessible = true }
        val converter = ShapeSelector.pickUnique(
            "$AIO_UTIL_CLASS 会话转联系人方法",
            utilType.declaredMethods.filter { !Modifier.isStatic(it.modifiers) },
            describe = ShapeSelector::signature,
            predicate = { method -> ShapeSelector.matches(method, listOf(sessionType), contactType) },
        ).found()
        converter.isAccessible = true
        return ownerField to converter
    }

    /** [ShapeSelector.Pick] 的唯一命中值；0 个或多个命中时抛出 [ShapeSelector.Pick.Rejected] 的诊断。 */
    private fun <T> ShapeSelector.Pick<T>.found(): T =
        when (this) {
            is ShapeSelector.Pick.Rejected -> error(reason)
            is ShapeSelector.Pick.Found<*> -> {
                @Suppress("UNCHECKED_CAST")
                val hit = value as T
                hit
            }
        }

    private fun uniqueField(
        type: Class<*>,
        fieldType: Class<*>,
        what: String,
    ): Field {
        val fields = instanceFields(type, fieldType)
        check(fields.size == 1) { "${type.name} 的 $what 定位失败: ${fields.size} 个" }
        return fields.single().apply { isAccessible = true }
    }

    private fun readField(
        field: Field,
        target: Any,
    ): Any? = runCatching { field.get(target) }.getOrNull()

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current != null) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    private fun isWithin(
        view: View,
        ancestor: View,
    ): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun loadClass(name: String): Class<*> = Class.forName(name, false, appClassLoader)
}
