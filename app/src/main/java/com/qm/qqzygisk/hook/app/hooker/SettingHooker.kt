package com.qm.qqzygisk.hook.app.hooker

import android.content.Context
import android.view.View
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HostClassScanner
import com.qm.qqzygisk.hook.utils.HostTargetCache
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ShapeSelector
import com.qm.qqzygisk.hook.utils.startModuleSettings
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * 设置页入口注入。
 *
 * 宿主每版本都会位移混淆短名（`processor.i` / `main.b` 只对单个版本成立），因此这里
 * **不写任何混淆类名/方法名候选**，全部按“形状”在稳定包前缀下搜索：
 *
 * - 设置项类：`com.tencent.mobileqq.setting.processor` 下，具有
 *   `(Context,int,CharSequence,int,String)` 构造器，且恰有一个非静态 `(Function0)->void` 回调 setter 的类。
 * - 设置页 provider：`com.tencent.mobileqq.setting.main` 下，继承稳定基类
 *   `com.tencent.mobileqq.setting.processor.SettingConfigProvider`，且恰有一个 `(Context)->List` 方法的类。
 * - 分组类：运行时从 provider 返回的真实分组对象上取（唯一类 + `List` 开头的构造器），
 *   优先真实 `(List,CharSequence,CharSequence)` 构造器，缺失时退回 Kotlin 默认参数合成构造器
 *   `(List,CharSequence,CharSequence,int mask,DefaultConstructorMarker)`（五参；mask=0 表示显式传入标题）。
 *
 * 任何一步 0 命中或命中不唯一都会抛错并记日志，绝不“取第一个命中”。宿主返回值在失败时保持原样。
 */
object SettingHooker : BaseHooker() {
    override val key = "settings_entry"
    override val name = "设置界面增强"

    override val isShow: Boolean = false

    /** 稳定包前缀：类名会位移，包名不会。 */
    private const val PROCESSOR_PACKAGE = "com.tencent.mobileqq.setting.processor"
    private const val PROVIDER_PACKAGE = "com.tencent.mobileqq.setting.main"

    /** 稳定基类名（未被混淆），provider 必须继承它。 */
    private const val PROVIDER_BASE_CLASS = "com.tencent.mobileqq.setting.processor.SettingConfigProvider"

    /** kotlin stdlib 稳定名：设置项点击回调接口 / kotlin.Unit。 */
    private const val FUNCTION0_CLASS = "kotlin.jvm.functions.Function0"
    private const val UNIT_CLASS = "kotlin.Unit"

    private const val ITEM_TITLE = "QQ Zygisk"

    /** 宿主 QUI 图标资源名；取不到时该项只显示文字（不致命）。 */
    private const val ITEM_ICON = "qui_tuning"

    /** 新分组不显示标题（与宿主自身若干无标题分组一致）。 */
    private const val GROUP_TOP_TITLE = ""
    private const val GROUP_BOTTOM_TITLE = ""

    /** 设置项构造器形状（只算一次，避免逐候选分配）。 */
    private val itemConstructorShape: List<Class<*>> = listOf(
        Context::class.java,
        Int::class.javaPrimitiveType!!,
        CharSequence::class.java,
        Int::class.javaPrimitiveType!!,
        String::class.java,
    )

    /** 分组构造器真实参数形状（只算一次）。 */
    private val groupConstructorParameterTypes: List<Class<*>> = listOf(
        List::class.java,
        CharSequence::class.java,
        CharSequence::class.java,
    )

    /** 分组构造器形状：优先真实三参构造器，缺失时退回 Kotlin 默认参数合成构造器。 */
    private val groupConstructorShapes: List<ShapeSelector.Shape<Constructor<*>>> = listOf(
        ShapeSelector.constructorShape("真实三参构造器", groupConstructorParameterTypes),
        ShapeSelector.maskedConstructorShape("Kotlin 默认参数合成构造器", groupConstructorParameterTypes),
    )

    /** 已注入的模块分组：弱引用 + 身份比较（不依赖 List 的 equals/hashCode，也不强持有宿主对象）。 */
    private val injectedGroups = Collections.synchronizedList(mutableListOf<WeakReference<Any>>())

    private var itemConstructor: Constructor<*>? = null
    private var itemClickSetter: Method? = null

    /** 宿主侧的 kotlin.Unit 实例（模块与宿主可能各自带一份 stdlib）。 */
    private val hostUnit: Any? by lazy {
        runCatching { Class.forName(UNIT_CLASS, false, appClassLoader).getField("INSTANCE").get(null) }
            .getOrNull()
    }

    override fun initOnce() {
        // 只解析、不初始化宿主类；真正的实例化发生在宿主已经自己创建过这些对象的回调里。
        val targets = HostTargetCache.resolve("qq-settings", appClassLoader) { cache ->
            val item by lazy {
                val function0 = runCatching { Class.forName(FUNCTION0_CLASS, false, appClassLoader) }.getOrNull()
                findItemProcessor(function0)
            }
            Triple(
                cache.constructor("itemConstructor") { item.second },
                cache.method("itemClickSetter") { item.third },
                cache.method("provider") { findProvider().second },
            )
        }
        itemConstructor = targets.first
        itemClickSetter = targets.second
        val provider = targets.third
        Log.info(
            "settings_entry 目标解析完成: item=${targets.first.declaringClass.name} " +
                "ctor=${ShapeSelector.signature(targets.first)} setter=${ShapeSelector.signature(targets.second)} " +
                "provider=${provider.declaringClass.name} method=${ShapeSelector.signature(provider)}",
        )

        provider.hook {
            after {
                val hostResult = result
                try {
                    // 只要这一行没出现，就说明宿主根本没调用分组方法（而不是注入失败）。
                    Log.info("settings_entry 宿主分组方法被调用: args=${args.size} result=${hostResult?.javaClass?.name}")
                    val context = args.getOrNull(0) as? Context
                        ?: error("宿主分组方法首个参数不是 Context: ${args.getOrNull(0)?.javaClass?.name}")
                    // 注入只读宿主列表，返回“原列表或副本”，这里显式回写结果。
                    result = injectEntry(hostResult, context)
                } catch (t: Throwable) {
                    // 失败时保持宿主原始返回值，绝不把半成品塞回宿主。
                    result = hostResult
                    Log.error("settings_entry 注入失败，保持宿主原始返回值", t)
                }
            }
        }
        Log.info("settings_entry 已挂载: ${ShapeSelector.signature(provider)}")
    }

    /**
     * 设置项类 = `processor` 包下唯一一个同时满足“五项构造器 + 唯一非静态 Function0 setter”的类。
     */
    private fun findItemProcessor(function0: Class<*>?): Triple<Class<*>, Constructor<*>, Method> {
        val names = HostClassScanner.classesInPackage(PROCESSOR_PACKAGE)
        val classes = names.mapNotNull { HostClassScanner.load(it) }
        val classPick = ShapeSelector.pickUnique(
            "设置项类(需 (Context,int,CharSequence,int,String) 构造器 + 唯一非静态 (Function0)->void setter)",
            classes,
            { it.name },
        ) { candidate ->
            !Modifier.isAbstract(candidate.modifiers) &&
                !candidate.isInterface &&
                candidate.declaredConstructors.any { ShapeSelector.matches(it, itemConstructorShape) } &&
                candidate.declaredMethods.any { isItemClickSetter(it, function0) }
        }
        val type = classPick.orFail("(已扫描 $PROCESSOR_PACKAGE 下 ${names.size} 个类名)")

        val constructor = ShapeSelector.pickUnique(
            "设置项构造器 ${itemConstructorShape.joinToString(",") { it.simpleName }}",
            type.declaredConstructors.toList(),
            ShapeSelector::signature,
        ) { ShapeSelector.matches(it, itemConstructorShape) }
            .orFail("(设置项类 ${type.name})")

        val setter = ShapeSelector.pickUnique(
            "设置项点击回调 setter (Function0)->void",
            type.declaredMethods.toList(),
            ShapeSelector::signature,
        ) { isItemClickSetter(it, function0) }
            .orFail("(设置项类 ${type.name})")

        return Triple(type, constructor, setter)
    }

    /**
     * provider = `main` 包下唯一一个继承稳定基类、且恰有一个 `(Context)->List` 方法的具体类。
     */
    private fun findProvider(): Pair<Class<*>, Method> {
        val base = runCatching { Class.forName(PROVIDER_BASE_CLASS, false, appClassLoader) }.getOrNull()
            ?: error("未加载到稳定基类 $PROVIDER_BASE_CLASS，无法按继承关系定位设置页 provider")
        val names = HostClassScanner.classesInPackage(PROVIDER_PACKAGE).filterNot { it.contains('$') }
        val classes = names.mapNotNull { HostClassScanner.load(it) }
        val type = ShapeSelector.pickUnique(
            "设置页 provider 类(需继承 $PROVIDER_BASE_CLASS + 唯一 (Context)->List 方法)",
            classes,
            { it.name },
        ) { candidate ->
            candidate != base &&
                !Modifier.isAbstract(candidate.modifiers) &&
                !candidate.isInterface &&
                base.isAssignableFrom(candidate) &&
                candidate.declaredMethods.any { isGroupListMethod(it) }
        }.orFail("(已扫描 $PROVIDER_PACKAGE 下 ${names.size} 个类名)")

        val method = ShapeSelector.pickUnique(
            "分组列表方法 (Context)->List",
            type.declaredMethods.toList(),
            ShapeSelector::signature,
        ) { isGroupListMethod(it) }
            .orFail("(provider 类 ${type.name})")

        return type to method
    }

    /**
     * 只读探测宿主返回值，把独立模块分组插在宿主通用设置所在分组之前。
     *
     * **绝不修改宿主返回的列表**：provider 完全可能返回不可变 List，直接 `add` 会抛异常。
     * 若列表里已经包含本模块创建的分组（身份比较），原样返回宿主列表。
     */
    private fun injectEntry(hostResult: Any?, context: Context): List<*> {
        val groups = hostResult as? List<*>
            ?: error("宿主分组返回值不是 List: ${hostResult?.javaClass?.name}")

        if (containsInjectedGroup(groups)) {
            Log.warn("settings_entry 宿主列表已含本模块分组，保持原始列表不变")
            return groups
        }

        val constructor = HostTargetCache.resolve("qq-settings-group", appClassLoader) { targets ->
            targets.constructor("groupConstructor") {
                // 首次从宿主真实返回的分组中定位；同版本直接恢复 JSON 中的构造器。
                val groupClasses = groups.mapNotNull { it?.javaClass }.distinct()
                val groupClass = ShapeSelector.pickUnique(
                    "宿主设置分组类(需带 (List,CharSequence,CharSequence) 构造器)",
                    groupClasses,
                    { it.name },
                ) { candidate ->
                    candidate.declaredConstructors.any { ctor -> groupConstructorShapes.any { it.matches(ctor) } }
                }.orFail("(宿主返回 ${groups.size} 个分组)")
                ShapeSelector.pickPreferred(
                    "分组构造器 (List,CharSequence,CharSequence)",
                    groupClass.declaredConstructors.toList(),
                    groupConstructorShapes,
                    ShapeSelector::signature,
                ).orFail("(分组类 ${groupClass.name})")
            }
        }
        val groupClass = constructor.declaringClass
        val masked = constructor.parameterCount == 5

        val iconRes = context.resources.getIdentifier(ITEM_ICON, "drawable", context.packageName)
        val entry = createEntryItem(context, iconRes)
        val newGroup = if (masked) {
            constructor.newInstance(mutableListOf(entry), GROUP_TOP_TITLE, GROUP_BOTTOM_TITLE, 0, null)
        } else {
            constructor.newInstance(mutableListOf(entry), GROUP_TOP_TITLE, GROUP_BOTTOM_TITLE)
        }

        val insertionIndex = findInsertionIndex(groups, groupClass, entry.javaClass, iconRes)
        val copied = ArrayList<Any?>(groups.size + 1)
        for (index in 0..groups.size) {
            if (index == insertionIndex) copied.add(newGroup)
            if (index < groups.size) copied.add(groups[index])
        }
        rememberInjectedGroup(newGroup)
        Log.info(
            "settings_entry 已注入: group=${groupClass.name} ctor=${ShapeSelector.signature(constructor)} " +
                "masked=$masked item=${entry.javaClass.name} index=$insertionIndex groups=${groups.size}->${copied.size}",
        )
        return copied
    }

    /** 用宿主通用设置图标作语义锚点；没有锚点时置顶，不退回末尾或固定分组下标。 */
    internal fun findInsertionIndex(
        groups: List<*>,
        groupType: Class<*>,
        itemType: Class<*>,
        iconRes: Int,
    ): Int {
        if (iconRes == 0) return 0
        val listFields = groupType.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type)
        }
        check(listFields.size == 1) { "设置分组的列表字段无法唯一确定: ${groupType.name}" }
        val itemsField = listFields.single().apply { isAccessible = true }
        val iconFields = generateSequence(itemType) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .onEach { it.isAccessible = true }
            .toList()
        var found = -1
        groups.forEachIndexed { index, group ->
            if (!groupType.isInstance(group)) return@forEachIndexed
            val items = itemsField.get(group) as? List<*> ?: return@forEachIndexed
            val hasAnchor = items.any { item ->
                itemType.isInstance(item) && iconFields.any { it.getInt(item) == iconRes }
            }
            if (hasAnchor) {
                check(found == -1) { "多个设置分组包含通用设置图标，无法确定入口位置" }
                found = index
            }
        }
        return if (found >= 0) found else 0
    }

    /** 宿主列表里是否已包含本模块创建的分组（弱引用 + 身份比较）。 */
    private fun containsInjectedGroup(groups: List<*>): Boolean = synchronized(injectedGroups) {
        pruneInjectedGroups()
        groups.any { candidate -> candidate != null && injectedGroups.any { it.get() === candidate } }
    }

    private fun rememberInjectedGroup(group: Any) {
        synchronized(injectedGroups) {
            pruneInjectedGroups()
            injectedGroups.add(WeakReference(group))
        }
    }

    /** 清理已被回收的弱引用；调用方需持有 [injectedGroups] 锁。 */
    private fun pruneInjectedGroups() {
        injectedGroups.removeAll { it.get() == null }
    }

    /** 用宿主自己的设置项类创建一个入口项，并挂上点击回调。 */
    private fun createEntryItem(context: Context, iconRes: Int): Any {
        val constructor = itemConstructor ?: error("设置项构造器未就绪（initOnce 未成功）")
        val setter = itemClickSetter ?: error("设置项点击回调 setter 未就绪（initOnce 未成功）")

        // 唯一 id：不能用固定小整数，宿主已有项用的就是 6/7/11/12/13/17 这类值。
        val id = View.generateViewId()
        val entry = constructor.newInstance(context, id, ITEM_TITLE, iconRes, null)
            ?: error("设置项构造器返回 null: ${ShapeSelector.signature(constructor)}")

        val samInterface = setter.parameterTypes[0]
        setter.invoke(entry, Proxy.newProxyInstance(appClassLoader, arrayOf(samInterface), clickHandler(context)))
        Log.info(
            "settings_entry 新建入口项: id=$id icon=$iconRes item=${entry.javaClass.name} " +
                "setter=${ShapeSelector.signature(setter)}",
        )
        return entry
    }

    /**
     * 点击回调代理：SAM 方法（宿主 `Function0.invoke`）触发打开模块设置，其余按 Object 语义实现。
     */
    private fun clickHandler(context: Context): InvocationHandler =
        InvocationHandler { proxy, method, methodArgs ->
            if (method.declaringClass == Any::class.java) {
                when (method.name) {
                    "equals" -> proxy === methodArgs?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "QQZygiskSettingsEntry"
                    else -> null
                }
            } else {
                try {
                    context.startModuleSettings()
                    Log.info("settings_entry 点击进入模块设置")
                } catch (t: Throwable) {
                    Log.error("settings_entry 打开模块设置失败", e = t)
                }
                if (method.returnType == Void.TYPE) null else hostUnit
            }
        }

    private fun isItemClickSetter(method: Method, function0: Class<*>?): Boolean =
        !Modifier.isStatic(method.modifiers) &&
            !method.isSynthetic &&
            !method.isBridge &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.size == 1 &&
            isFunction0(method.parameterTypes[0], function0)

    private fun isGroupListMethod(method: Method): Boolean =
        !Modifier.isStatic(method.modifiers) &&
            !method.isSynthetic &&
            !method.isBridge &&
            ShapeSelector.matches(method, listOf(Context::class.java), returnType = List::class.java)

    /** 按名字比对：模块与宿主可能各自带一份 kotlin stdlib，Class 身份不一定相同。 */
    private fun isFunction0(type: Class<*>, function0: Class<*>?): Boolean =
        type.name == FUNCTION0_CLASS || type == function0

    /** 取出唯一命中；未命中或歧义时抛错，异常信息即诊断信息。 */
    private fun <T> ShapeSelector.Pick<T>.orFail(context: String = ""): T = when (this) {
        is ShapeSelector.Pick.Found -> value
        is ShapeSelector.Pick.Rejected -> error(if (context.isEmpty()) reason else "$reason $context")
    }
}
