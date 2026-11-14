package com.qm.qqzygisk.hook.app.hooker

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.app.host.WeChatHostBridge
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.registerModuleAppActivities
import com.qm.qqzygisk.hook.utils.startModuleSettings
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * 微信 setting_new 页面；在宿主排好序后插入，避免破坏前驱链。
 *
 * 保留已确证的分发协议，按 builder 签名与 itemBase 形态整体校验，不按短名猜：
 * - 8.0.79 (code 3182): ib4.i + cc4.g.d(ib4.i, List) -> la6.l
 * - 另一已支持分发变体: la4.i + fb4.g.d(la4.i, List)
 * - 8.0.80 (code 3220): na4.i + hb4.g.d(na4.i, List) -> pa6.l
 * 任一必需 getter 或完整形状缺失时明确放弃安装，绝不把同名无关类当成设置构建器。
 */
internal object WeChatSettingHooker {
    private const val ROOT_GROUP =
        "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupMain"
    private const val ITEM_TITLE = "QQ Zygisk"
    private const val ITEM_KEY = "QQZygisk_Settings"

    private class Protocol(
        val itemBase: String,
        val builder: String,
        val builderResult: String?,
        val click: String,
        val activityGetters: List<String>,
        val title: String,
        val key: String,
        val summary: String,
        val summaryResource: String,
        val icon: String,
        val ordering: String,
        val drawable: String? = null,
        val visible: String? = null,
    )

    private class Binding(
        val protocol: Protocol,
        val buildMethod: Method,
        val itemBase: Class<*>,
        val template: Class<*>,
    )

    private val protocols = listOf(
        Protocol(
            itemBase = "ib4.i",
            builder = "cc4.g",
            builderResult = "la6.l",
            click = "f8",
            activityGetters = listOf("getActivity", "getContext"),
            title = "R7", key = "P7", summary = "U7", summaryResource = "b8",
            icon = "N7", ordering = "Q7",
        ),
        Protocol(
            itemBase = "la4.i",
            builder = "fb4.g",
            builderResult = null,
            click = "g8",
            activityGetters = listOf("getMvvmActivity", "getActivity", "getContext"),
            title = "R7", key = "P7", summary = "U7", summaryResource = "b8",
            icon = "N7", ordering = "Q7",
        ),
        Protocol(
            itemBase = "na4.i",
            builder = "hb4.g",
            builderResult = "pa6.l",
            click = "g8",
            activityGetters = listOf("getContext", "getActivity"),
            title = "S7", key = "Q7", summary = "c8", summaryResource = "e8",
            icon = "O7", ordering = "R7", drawable = "Y7", visible = "f8",
        ),
    )

    private val templateCandidates = listOf(
        "com.tencent.mm.plugin.setting.ui.setting_new.settings.friend_privacy.SettingActivityItemNewFriendCommonSayHi",
        "com.tencent.mm.plugin.setting.ui.setting_new.settings.friend_privacy.SettingActivityItemCommonSayHi",
    )

    private val clickParams: Array<Class<*>> = arrayOf(
        Context::class.java, View::class.java, Int::class.javaPrimitiveType!!,
    )

    private val installedBuilders = mutableSetOf<Method>()
    private val installedItemMethods = mutableSetOf<Method>()
    private val activityItems = WeakHashMap<Activity, WeakReference<Any>>()
    private val itemOwners = WeakHashMap<Any, WeakReference<Activity>>()
    private var installing = false
    private var failureLogged = false
    private var bindingLoader: ClassLoader? = null
    private var cachedBinding: Binding? = null

    @Synchronized
    fun install(): Boolean {
        if (installing) return false
        installing = true
        return try {
            val binding = resolve()
            if (binding.buildMethod in installedBuilders) return true
            binding.buildMethod.hook {
                after {
                    val root = args.firstOrNull() ?: return@after
                    if (root.javaClass.name != ROOT_GROUP) return@after
                    val original = result ?: return@after
                    runCatching { inject(root, original, binding) }
                        .onSuccess { updated -> result = updated }
                        .onFailure { Log.error("微信设置入口注入失败", it) }
                }
            }
            installedBuilders.add(binding.buildMethod)
            Log.info(
                "微信设置入口 hook 已安装: ${binding.buildMethod.declaringClass.name}.d " +
                    "(${binding.itemBase.name} -> ${binding.template.name})",
            )
            true
        } catch (error: Throwable) {
            if (!failureLogged) {
                failureLogged = true
                Log.error("安装微信设置入口失败，等待宿主加载后重试", error)
            }
            false
        } finally {
            installing = false
        }
    }

    private fun resolve(): Binding {
        val loader = appClassLoader
        if (bindingLoader === loader) cachedBinding?.let { return it }
        val failures = ArrayList<String>(protocols.size)
        for (protocol in protocols) {
            try {
                val binding = resolve(protocol)
                cachedBinding = binding
                bindingLoader = loader
                return binding
            } catch (error: Throwable) {
                failures += "${protocol.builder}: ${error.message}"
            }
        }
        error("没有匹配的微信设置协议: ${failures.joinToString("; ")}")
    }

    private fun resolve(protocol: Protocol): Binding {
        val builder = Class.forName(protocol.builder, false, appClassLoader)
        val buildMethod = builder.declaredMethods.singleOrNull { method ->
            method.name == "d" &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0].name == protocol.itemBase &&
                List::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                (protocol.builderResult == null || method.returnType.name == protocol.builderResult)
        } ?: error("${protocol.builder}.d(${protocol.itemBase}, List) 形状不匹配")
        // 返回值必须是含两个非静态字段的二元组（la6.l / kotlin.Pair 形状），注入依赖它。
        val resultType = buildMethod.returnType
        if (resultType == Void.TYPE || resultType.isPrimitive || resultType.isArray ||
            resultType.declaredFields.count { !Modifier.isStatic(it.modifiers) } != 2 ||
            resultType.declaredConstructors.none { ctor ->
                ctor.parameterTypes.size == 2 && ctor.parameterTypes.none { it.isPrimitive }
            }
        ) {
            error("${protocol.builder}.d 返回值不是二元组: ${resultType.name}")
        }
        val itemBase = buildMethod.parameterTypes[0]
        validateItemBase(itemBase, protocol)
        val loader = itemBase.classLoader ?: appClassLoader
        val template = templateCandidates.asSequence()
            .mapNotNull { name ->
                runCatching { Class.forName(name, false, loader) }.getOrNull()
            }
            .firstOrNull { candidate -> isUsableTemplate(candidate, itemBase, protocol) }
            ?: error("没有形状完整的微信设置项模板")
        return Binding(protocol, buildMethod, itemBase, template)
    }

    /** 基类可以声明 abstract 项方法，但协议必需的方法必须齐全，Activity 由继承的 getter 提供。 */
    private fun validateItemBase(itemBase: Class<*>, protocol: Protocol) {
        val primaryGetter = protocol.activityGetters.firstOrNull()
        val activityGetter = primaryGetter?.let { findMethod(itemBase, it, emptyArray()) }
        if (activityGetter == null ||
            !Activity::class.java.isAssignableFrom(activityGetter.returnType)
        ) {
            error("${itemBase.name} 缺少 Activity getter $primaryGetter")
        }
        // 排序 getter 必须存在，但本入口在宿主排序之后插入，不改写它。
        listOfNotNull(protocol.title, protocol.key, protocol.summary, protocol.summaryResource,
            protocol.icon, protocol.ordering, protocol.drawable, protocol.visible).forEach { name ->
            findMethod(itemBase, name, emptyArray()) ?: error("${itemBase.name} 缺少 $name")
        }
        findMethod(itemBase, protocol.click, clickParams)
            ?: error("${itemBase.name} 缺少 ${protocol.click}(Context,View,int)")
        findMethod(itemBase, "onCreate", arrayOf(Bundle::class.java))
            ?: error("${itemBase.name} 缺少 onCreate(Bundle)")
    }

    /** 模板必须可实例化，且覆盖/继承到全部待 hook 的 getter 与点击方法。 */
    private fun isUsableTemplate(
        candidate: Class<*>,
        itemBase: Class<*>,
        protocol: Protocol,
    ): Boolean {
        if (Modifier.isAbstract(candidate.modifiers)) return false
        if (!itemBase.isAssignableFrom(candidate)) return false
        if (candidate.declaredConstructors.none { ctor ->
                ctor.parameterTypes.size == 1 &&
                    Activity::class.java.isAssignableFrom(ctor.parameterTypes[0])
            }
        ) {
            return false
        }
        val gettersUsable = listOfNotNull(protocol.title, protocol.key, protocol.summary,
            protocol.summaryResource, protocol.icon, protocol.drawable, protocol.visible).all { name ->
            val method = findMethod(candidate, name, emptyArray()) ?: return@all false
            !Modifier.isStatic(method.modifiers) &&
                !Modifier.isAbstract(method.modifiers) &&
                method.returnType != Void.TYPE
        }
        if (!gettersUsable) return false
        val click = findMethod(candidate, protocol.click, clickParams) ?: return false
        if (Modifier.isStatic(click.modifiers) || Modifier.isAbstract(click.modifiers)) return false
        return findMethod(candidate, "onCreate", arrayOf(Bundle::class.java)) != null
    }

    private fun inject(root: Any, pair: Any, binding: Binding): Any {
        val activity = activityOf(root, binding.protocol.activityGetters)
            ?: error("微信设置根项没有宿主 Activity")
        HookSettings.initialize(activity)
        // builder 返回二元组(root, orderedChildren)。保留宿主的顺序和所有原始项，
        // 不把新项塞进 builder 内部的 HashMap，也不依赖一次性的 Activity 标记。
        val childrenField = pair.javaClass.declaredFields.firstOrNull { field ->
            if (Modifier.isStatic(field.modifiers)) return@firstOrNull false
            field.isAccessible = true
            field.get(pair) is List<*>
        } ?: error("微信设置构建结果没有子项列表")
        val children = (childrenField.get(pair) as List<*>).filterNotNull()
        val item = activityItems[activity]?.get() ?: createItem(activity, binding).also {
            activityItems[activity] = WeakReference(it)
        }
        val updated = insertWeChatSettingEntry(children, item) { it.javaClass.simpleName }
        if (updated === children) return pair
        val ctor = pair.javaClass.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 2 && it.parameterTypes.none { type -> type.isPrimitive }
        } ?: error("微信设置构建结果没有 Pair 构造器")
        ctor.isAccessible = true
        Log.info("微信设置页已插入 QQ Zygisk 入口: ${activity.javaClass.name}, 原有 ${children.size} 项")
        return ctor.newInstance(root, updated)
    }

    private fun activityOf(item: Any, getters: List<String>): Activity? {
        for (name in getters) {
            val method = findMethod(item.javaClass, name, emptyArray()) ?: continue
            method.isAccessible = true
            val value = runCatching { method.invoke(item) }.getOrNull()
            if (value is Activity) return value
        }
        return null
    }

    private fun createItem(activity: Activity, binding: Binding): Any {
        val ctor = binding.template.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0].isInstance(activity)
        } ?: error("微信设置项模板 ${binding.template.name} 没有接收宿主 Activity 的构造器")
        ctor.isAccessible = true
        val item = ctor.newInstance(activity)
        itemOwners[item] = WeakReference(activity)
        hookItemMethods(binding)
        // 该实例不在宿主的 UIComponent 集合里，显式建立 diff 使用的初始快照。
        val onCreate = findMethod(binding.template, "onCreate", arrayOf(Bundle::class.java))
            ?: error("微信设置项模板缺少 onCreate(Bundle)")
        onCreate.isAccessible = true
        onCreate.invoke(item, null)
        return item
    }

    private fun hookItemMethods(binding: Binding) {
        fun override(name: String, value: Any?) {
            val method = findMethod(binding.template, name, emptyArray())
                ?: error("微信设置项缺少 $name")
            if (!installedItemMethods.add(method)) return
            method.hook {
                before {
                    if (itemOwners.containsKey(instance)) result = value
                }
            }
        }
        val protocol = binding.protocol
        override(protocol.title, ITEM_TITLE)
        override(protocol.key, ITEM_KEY)
        override(protocol.summary, "")
        override(protocol.summaryResource, null)
        override(protocol.icon, null)
        protocol.drawable?.let { override(it, null) }
        protocol.visible?.let { override(it, true) }
        // 本入口在排序后插入，不进入宿主的 parent/front 链，也不影响其它实例。
        val click = findMethod(binding.template, binding.protocol.click, clickParams)
            ?: error("微信设置项缺少点击方法 ${binding.protocol.click}")
        if (installedItemMethods.add(click)) {
            click.hook {
                before {
                    val activity = itemOwners[instance]?.get() ?: return@before
                    result = null
                    runCatching {
                        val proxy = WeChatHostBridge.proxyActivityClass ?: error("未找到微信代理 Activity")
                        activity.registerModuleAppActivities(proxy)
                        activity.startModuleSettings()
                    }.onFailure { Log.error("打开 QQ Zygisk 设置失败", it) }
                }
            }
        }
    }

    private fun findMethod(cls: Class<*>, name: String, params: Array<Class<*>>): Method? =
        generateSequence(cls) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterTypes.contentEquals(params) }
}
