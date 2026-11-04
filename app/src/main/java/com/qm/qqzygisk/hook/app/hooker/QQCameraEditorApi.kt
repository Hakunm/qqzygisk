package com.qm.qqzygisk.hook.app.hooker

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.utils.HostClassScanner
import com.qm.qqzygisk.hook.utils.HostTargetCache
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ShapeSelector
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * QQ 图片编辑器入口的反射契约。
 *
 * 系统相机拍照后要把照片交给 QQ 自己的编辑器，但编辑器入口的类名每个版本都会被
 * 混淆器位移（入口方法所在类 `zc.a` / `cd.a` / `ed.a`，入口类 `cd.a` / `fd.a` / `hd.a` 等），
 * 所以这里不再写死任何宿主短名，而是按“稳定类型 + 完整签名 + 唯一性”从宿主 DEX 清单里解析：
 *
 * - 入口方法：`static void (Activity, AEPhotoCaptureResult, 入口类, Bundle, int)`；
 * - 入口类 = 入口方法第 3 参的同一 `Class` 身份，需 `(int,int,int)` 构造器，
 *   以及唯一一个“照片配置” `void` 单参 setter；
 * - 照片配置类：有无参 getter 返回稳定的 `SessionWrap`，且有无参 getter 返回 `String`
 *   （视频配置不满足这个组合）；
 * - 照片配置的静态嵌套 builder：`(int)` 构造器 + 无参实例方法返回照片配置。
 *
 * 首次定位时任何一步 0 命中或多命中都抛错，绝不“取第一个命中”。成功目标按
 * versionCode + versionName 保存为 JSON，同版本直接恢复，失败不重新定位；
 * 当前进程的反射对象另按 [ClassLoader] 身份复用。
 */
internal object QQCameraEditorApi {

    private const val PHOTO_RESULT_CLASS =
        "com.tencent.aelight.camera.struct.camera.AEPhotoCaptureResult"
    private const val SESSION_WRAP_CLASS = "dov.com.qq.im.ae.current.SessionWrap"

    /** QQ 自己在 QIM 流程里固定传给入口构造器的第二个参数。 */
    private const val ENTRANCE_SECOND_ARG = 120

    /** 入口方法最后一个参数 `edit_video_way`：真正的编辑器请求码由入口方法内部固定。 */
    private const val EDIT_VIDEO_WAY = 0

    /** builder 的拍照数量参数，QIM 固定传 1。 */
    private const val PHOTO_COUNT = 1

    // 两个 `(int)->builder` 方法无法按类型区分，宿主的三个已测版本都稳定使用这两个短名，
    // 但下面仍按完整签名 `(int)->builder` 定位，不按“两个 int 方法任取其一”。
    private const val PHOTO_COUNT_SETTER = "i"
    private const val SUB_BUSINESS_SETTER = "k"

    /**
     * 一次成功解析得到的全部反射目标。
     *
     * [buildPhotoResult] / [buildEntrance] / [jumpToEditor] 是唯一的构造参数入口，
     * 调用方只传业务值，不再自己按名字反射。
     */
    internal class Contract(
        val loader: ClassLoader,
        val photoResultClass: Class<*>,
        val entranceClass: Class<*>,
        val photoConfigClass: Class<*>,
        val builderClass: Class<*>,
        private val photoResultConstructor: Constructor<*>,
        private val entranceConstructor: Constructor<*>,
        private val entranceConfigSetter: Method,
        private val builderConstructor: Constructor<*>,
        private val builderConfigFactory: Method,
        private val builderSessionSetter: Method,
        private val builderClassNameSetter: Method,
        private val builderPhotoCountSetter: Method,
        private val builderSubBusinessSetter: Method,
        private val jumpMethod: Method,
    ) {
        val jumpSignature: String get() = ShapeSelector.signature(jumpMethod)

        /** 生成 QQ 拍照结果；[path] 为已落盘图片的绝对路径，[orientation] 为 EXIF 旋转角度。 */
        fun buildPhotoResult(path: String, orientation: Int): Any =
            photoResultConstructor.newInstance(
                0,
                0,
                path,
                null,
                System.currentTimeMillis(),
                orientation,
            )

        /**
         * 生成编辑器入口对象。
         *
         * [sessionInfo] 必须是 QQ 自己 `ARG_SESSION_INFO` 里的 `SessionWrap` 实例（可空）；
         * [aioClassName] 用调用方 Activity 的类名兜底；[qqSubBusinessId] 来自
         * `qq_sub_business_id`，缺省 0。
         */
        fun buildEntrance(
            editVideoType: Int,
            takePhotoBusiness: Int,
            preferId: Int,
            aioClassName: String,
            sessionInfo: Parcelable?,
            qqSubBusinessId: Int,
        ): Any {
            val entrance = entranceConstructor.newInstance(
                editVideoType,
                ENTRANCE_SECOND_ARG,
                takePhotoBusiness,
            )
            val builder = builderConstructor.newInstance(preferId)
            builderSessionSetter.invoke(builder, sessionInfo)
            builderClassNameSetter.invoke(builder, aioClassName)
            builderPhotoCountSetter.invoke(builder, PHOTO_COUNT)
            builderSubBusinessSetter.invoke(builder, qqSubBusinessId)
            entranceConfigSetter.invoke(entrance, builderConfigFactory.invoke(builder))
            return entrance
        }

        /** 打开 QQ 编辑器；请求码与编辑流程由宿主方法内部决定。 */
        fun jumpToEditor(activity: Activity, photoResult: Any, entrance: Any) {
            jumpMethod.invoke(null, activity, photoResult, entrance, null, EDIT_VIDEO_WAY)
        }

        internal fun remember(targets: HostTargetCache.Targets) {
            targets.type("photoResultClass") { photoResultClass }
            targets.type("entranceClass") { entranceClass }
            targets.type("photoConfigClass") { photoConfigClass }
            targets.type("builderClass") { builderClass }
            targets.constructor("photoResultConstructor") { photoResultConstructor }
            targets.constructor("entranceConstructor") { entranceConstructor }
            targets.method("entranceConfigSetter") { entranceConfigSetter }
            targets.constructor("builderConstructor") { builderConstructor }
            targets.method("builderConfigFactory") { builderConfigFactory }
            targets.method("builderSessionSetter") { builderSessionSetter }
            targets.method("builderClassNameSetter") { builderClassNameSetter }
            targets.method("builderPhotoCountSetter") { builderPhotoCountSetter }
            targets.method("builderSubBusinessSetter") { builderSubBusinessSetter }
            targets.method("jumpMethod") { jumpMethod }
        }

        /** 全部命中目标的完整签名，用于日志与版本验证。 */
        fun describe(): String = buildString {
            append("result=").append(ShapeSelector.signature(photoResultConstructor))
            append(" entrance=").append(entranceClass.name)
            append(" entranceCtor=").append(ShapeSelector.signature(entranceConstructor))
            append(" config=").append(photoConfigClass.name)
            append(" configSetter=").append(ShapeSelector.signature(entranceConfigSetter))
            append(" builder=").append(builderClass.name)
            append(" builderCtor=").append(ShapeSelector.signature(builderConstructor))
            append(" factory=").append(ShapeSelector.signature(builderConfigFactory))
            append(" session=").append(ShapeSelector.signature(builderSessionSetter))
            append(" className=").append(ShapeSelector.signature(builderClassNameSetter))
            append(" photoCount=").append(ShapeSelector.signature(builderPhotoCountSetter))
            append(" subBusiness=").append(ShapeSelector.signature(builderSubBusinessSetter))
            append(" jumpOwner=").append(jumpMethod.declaringClass.name)
            append(" jump=").append(ShapeSelector.signature(jumpMethod))
        }
    }

    @Volatile
    private var cached: Contract? = null

    /**
     * 解析宿主编辑器契约。
     *
     * 只用 `HostData.appClassLoader`（验证时先 `HostData.init(loader)` 再调用即可），
     * 首次成功定位持久化 JSON；同版本直接恢复，当前进程再按 ClassLoader 身份复用。
     */
    internal fun resolve(): Contract {
        val loader = appClassLoader
        cached?.takeIf { it.loader === loader }?.let { return it }
        synchronized(this) {
            cached?.takeIf { it.loader === loader }?.let { return it }
            val startedAt = SystemClock.uptimeMillis()
            val contract = HostTargetCache.resolve("qq-camera-editor", loader) { targets ->
                if (targets.cached) restore(loader, targets)
                else detect(loader).also { it.remember(targets) }
            }
            cached = contract
            Log.info(
                "QQ图片编辑器契约解析完成: ${SystemClock.uptimeMillis() - startedAt}ms " +
                    contract.describe(),
            )
            return contract
        }
    }

    private fun restore(loader: ClassLoader, targets: HostTargetCache.Targets): Contract = Contract(
        loader = loader,
        photoResultClass = targets.type("photoResultClass"),
        entranceClass = targets.type("entranceClass"),
        photoConfigClass = targets.type("photoConfigClass"),
        builderClass = targets.type("builderClass"),
        photoResultConstructor = targets.constructor("photoResultConstructor"),
        entranceConstructor = targets.constructor("entranceConstructor"),
        entranceConfigSetter = targets.method("entranceConfigSetter"),
        builderConstructor = targets.constructor("builderConstructor"),
        builderConfigFactory = targets.method("builderConfigFactory"),
        builderSessionSetter = targets.method("builderSessionSetter"),
        builderClassNameSetter = targets.method("builderClassNameSetter"),
        builderPhotoCountSetter = targets.method("builderPhotoCountSetter"),
        builderSubBusinessSetter = targets.method("builderSubBusinessSetter"),
        jumpMethod = targets.method("jumpMethod"),
    )

    private fun detect(loader: ClassLoader): Contract {
        val intType = Int::class.javaPrimitiveType!!
        val longType = Long::class.javaPrimitiveType!!
        val photoResultClass = loadStable(loader, PHOTO_RESULT_CLASS)
        val sessionWrapClass = loadStable(loader, SESSION_WRAP_CLASS)

        val photoResultConstructor = pickUnique(
            "拍照结果构造器 $PHOTO_RESULT_CLASS(int,int,String,Bitmap,long,int)",
            photoResultClass.declaredConstructors.toList(),
            ShapeSelector::signature,
        ) {
            ShapeSelector.matches(
                it,
                listOf(
                    intType,
                    intType,
                    String::class.java,
                    Bitmap::class.java,
                    longType,
                    intType,
                ),
            )
        }.apply { isAccessible = true }

        val jumpMethod = findJumpMethod(loader, photoResultClass, sessionWrapClass)
        val entranceClass = jumpMethod.parameterTypes[2]

        val entranceConstructor = pickUnique(
            "图片编辑器入口构造器 ${entranceClass.name}(int,int,int)",
            entranceClass.declaredConstructors.toList(),
            ShapeSelector::signature,
        ) { ShapeSelector.matches(it, listOf(intType, intType, intType)) }
            .apply { isAccessible = true }

        val entranceConfigSetter = pickUnique(
            "图片编辑器入口照片配置 setter ${entranceClass.name}.(照片配置)->void",
            runCatching { entranceClass.declaredMethods.toList() }.getOrElse {
                error("无法读取入口类方法 ${entranceClass.name}: ${it.message}")
            },
            ShapeSelector::signature,
        ) { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 1 &&
                isPhotoConfigShaped(method.parameterTypes[0], sessionWrapClass, photoResultClass)
        }.apply { isAccessible = true }
        val photoConfigClass = entranceConfigSetter.parameterTypes[0]

        val builderClass = pickUnique(
            "照片配置 builder 类 ${photoConfigClass.name} 的静态嵌套类" +
                "(需 (int) 构造器 + 无参实例方法返回照片配置)",
            runCatching { photoConfigClass.declaredClasses.toList() }.getOrElse {
                error("无法读取照片配置嵌套类 ${photoConfigClass.name}: ${it.message}")
            },
            { it.name },
        ) { nested ->
            Modifier.isStatic(nested.modifiers) &&
                !nested.isInterface &&
                runCatching {
                    nested.declaredConstructors.any { ShapeSelector.matches(it, listOf(intType)) } &&
                        nested.declaredMethods.any { method ->
                            !Modifier.isStatic(method.modifiers) &&
                                method.parameterTypes.isEmpty() &&
                                method.returnType == photoConfigClass
                        }
                }.getOrDefault(false)
        }

        val builderMethods = runCatching { builderClass.declaredMethods.toList() }.getOrElse {
            error("无法读取 builder 方法 ${builderClass.name}: ${it.message}")
        }

        val builderConstructor = pickUnique(
            "builder 构造器 ${builderClass.name}(int)",
            builderClass.declaredConstructors.toList(),
            ShapeSelector::signature,
        ) { ShapeSelector.matches(it, listOf(intType)) }
            .apply { isAccessible = true }

        val builderConfigFactory = pickUnique(
            "builder 照片配置工厂 ${builderClass.name}.()->${photoConfigClass.name}",
            builderMethods,
            ShapeSelector::signature,
        ) { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == photoConfigClass
        }.apply { isAccessible = true }

        val builderSessionSetter = pickUnique(
            "builder session setter ${builderClass.name}.($SESSION_WRAP_CLASS)->${builderClass.name}",
            builderMethods,
            ShapeSelector::signature,
        ) { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == builderClass &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == sessionWrapClass
        }.apply { isAccessible = true }

        val builderClassNameSetter = pickUnique(
            "builder className setter ${builderClass.name}.(String)->${builderClass.name}",
            builderMethods,
            ShapeSelector::signature,
        ) { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == builderClass &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == String::class.java
        }.apply { isAccessible = true }

        val intSetters = builderMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == builderClass &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == intType
        }
        val intSetterSignatures = intSetters.joinToString(" | ", limit = 6) {
            ShapeSelector.signature(it)
        }

        // 两个 `(int)->builder` 方法按类型完全同形，只能按已实证的短名区分；
        // 短名变化时直接失败并列出全部候选，绝不任取其一。
        fun intSetter(name: String, label: String): Method {
            val pick = ShapeSelector.pickUnique(
                "$label ${builderClass.name}.$name(int)->${builderClass.name}",
                intSetters,
                ShapeSelector::signature,
            ) { it.name == name }
            return when (pick) {
                is ShapeSelector.Pick.Found -> pick.value.apply { isAccessible = true }
                is ShapeSelector.Pick.Rejected -> error(
                    "${pick.reason}" +
                        "(builder 的 (int)->builder 方法: $intSetterSignatures)",
                )
            }
        }

        if (intSetters.size != 2) {
            Log.warn(
                "builder (int)->builder 方法数量为 ${intSetters.size}，按短名 " +
                    "$PHOTO_COUNT_SETTER/$SUB_BUSINESS_SETTER 定位: $intSetterSignatures",
            )
        }

        val builderPhotoCountSetter = intSetter(PHOTO_COUNT_SETTER, "builder 拍照数量 setter")
        val builderSubBusinessSetter = intSetter(SUB_BUSINESS_SETTER, "builder 子业务 setter")
        Log.debug(
            "builder int setters: 拍照数量=${ShapeSelector.signature(builderPhotoCountSetter)} " +
                "子业务=${ShapeSelector.signature(builderSubBusinessSetter)} " +
                "全部候选=$intSetterSignatures",
        )

        return Contract(
            loader = loader,
            photoResultClass = photoResultClass,
            entranceClass = entranceClass,
            photoConfigClass = photoConfigClass,
            builderClass = builderClass,
            photoResultConstructor = photoResultConstructor,
            entranceConstructor = entranceConstructor,
            entranceConfigSetter = entranceConfigSetter,
            builderConstructor = builderConstructor,
            builderConfigFactory = builderConfigFactory,
            builderSessionSetter = builderSessionSetter,
            builderClassNameSetter = builderClassNameSetter,
            builderPhotoCountSetter = builderPhotoCountSetter,
            builderSubBusinessSetter = builderSubBusinessSetter,
            jumpMethod = jumpMethod,
        )
    }

    /**
     * 在宿主顶层混淆类里搜入口方法。
     *
     * 候选只取“单段包名 + 顶层类”（如 `zc.a`），与三个已测版本入口方法所在类的形态一致；
     * QQ 的混淆类都落在这种短包里，避免为此加载整份几十万类清单里的命名类。
     */
    private fun findJumpMethod(
        loader: ClassLoader,
        photoResultClass: Class<*>,
        sessionWrapClass: Class<*>,
    ): Method {
        val activityClass = Activity::class.java
        val bundleClass = Bundle::class.java
        val intType = Int::class.javaPrimitiveType!!
        val matches = ArrayList<Method>(2)
        var scanned = 0
        var loaded = 0

        for (name in candidateClassNames()) {
            scanned++
            val type = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
            if (type.isInterface) continue
            loaded++
            val methods = runCatching { type.declaredMethods }.getOrNull() ?: continue
            for (method in methods) {
                if (!Modifier.isStatic(method.modifiers)) continue
                if (method.returnType != Void.TYPE) continue
                val parameters = method.parameterTypes
                if (parameters.size != 5) continue
                if (parameters[0] != activityClass || parameters[1] != photoResultClass) continue
                if (parameters[3] != bundleClass || parameters[4] != intType) continue
                val entrance = parameters[2]
                if (entrance.isPrimitive || entrance.isArray || entrance == String::class.java) {
                    continue
                }
                method.isAccessible = true
                matches += method
            }
        }

        Log.debug(
            "图片编辑器入口方法扫描: 候选类=$scanned 可加载=$loaded 签名命中=${matches.size}",
        )
        if (matches.isEmpty()) {
            error(
                "未找到 QQ 图片编辑器入口方法 " +
                    "static void(Activity,$PHOTO_RESULT_CLASS,入口类,Bundle,int): " +
                    "已扫描 $scanned 个顶层混淆类，可加载 $loaded 个",
            )
        }

        // 同一签名偶然命中多个时，用入口类的结构（三 int 构造器 + 唯一照片配置 setter）裁决；
        // 仍然不唯一就失败，绝不取第一个。
        val shaped = matches.filter { method ->
            isEntranceShaped(method.parameterTypes[2], intType, sessionWrapClass, photoResultClass)
        }
        return when (shaped.size) {
            1 -> shaped[0]
            0 -> error(
                "图片编辑器入口方法签名命中 ${matches.size} 个，但第 3 参都不符合入口类结构: " +
                    matches.joinToString(" | ", limit = 4) { ShapeSelector.signature(it) },
            )

            else -> error(
                "图片编辑器入口方法不唯一: " +
                    shaped.joinToString(" | ", limit = 4) { ShapeSelector.signature(it) },
            )
        }
    }

    /**
     * 候选类名：单段包名（`a.b` 形态）且非嵌套类。
     *
     * 类名清单复用 [HostClassScanner] 的进程内缓存（与运行时 ClassLoader 同源），
     * 不另建 DEX 枚举器。
     */
    private fun candidateClassNames(): Sequence<String> =
        HostClassScanner.classNames().asSequence().filter { name ->
            name.indexOf('.') == name.lastIndexOf('.') && name.indexOf('$') < 0
        }

    /** 入口类结构：唯一 `(int,int,int)` 构造器 + 唯一“照片配置参数”的 void 单参 setter。 */
    private fun isEntranceShaped(
        type: Class<*>,
        intType: Class<*>,
        sessionWrapClass: Class<*>,
        photoResultClass: Class<*>,
    ): Boolean {
        if (type.isPrimitive || type.isArray || type.isInterface) return false
        val constructors = runCatching { type.declaredConstructors }.getOrNull() ?: return false
        if (constructors.count { ShapeSelector.matches(it, listOf(intType, intType, intType)) } != 1) {
            return false
        }
        val methods = runCatching { type.declaredMethods }.getOrNull() ?: return false
        return methods.count { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 1 &&
                isPhotoConfigShaped(method.parameterTypes[0], sessionWrapClass, photoResultClass)
        } == 1
    }

    /**
     * 照片配置类结构：存在无参 getter 返回稳定类 `SessionWrap`，同时存在无参 getter 返回 `String`。
     * 视频配置类不满足这个组合。
     */
    private fun isPhotoConfigShaped(
        type: Class<*>,
        sessionWrapClass: Class<*>,
        photoResultClass: Class<*>,
    ): Boolean {
        if (type.isPrimitive || type.isArray || type.isInterface) return false
        if (type == photoResultClass || type == String::class.java || type == Activity::class.java) {
            return false
        }
        return hasNoArgReturning(type, sessionWrapClass) && hasNoArgStringGetter(type)
    }

    private fun hasNoArgReturning(type: Class<*>, returnType: Class<*>): Boolean =
        declaredMethodsInHierarchy(type).any { method ->
            method.parameterTypes.isEmpty() && method.returnType == returnType
        }

    /**
     * 无参 `String` getter：排除 `toString()`。
     *
     * 任何类都会继承 `Object.toString()`，按返回类型直接比对会把
     * “有没有 String getter”变成恒真，从而消掉照片配置/视频配置的区分度。
     */
    private fun hasNoArgStringGetter(type: Class<*>): Boolean =
        declaredMethodsInHierarchy(type).any { method ->
            method.parameterTypes.isEmpty() &&
                method.returnType == String::class.java &&
                method.name != "toString"
        }

    /** 本类及父类的声明方法；不含 `Object`，避免 `toString()` 之类的通用方法干扰形状判断。 */
    private fun declaredMethodsInHierarchy(type: Class<*>): List<Method> {
        val methods = ArrayList<Method>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            runCatching { current.declaredMethods }.getOrNull()?.let { methods.addAll(it) }
            current = current.superclass
        }
        return methods
    }

    private fun loadStable(loader: ClassLoader, name: String): Class<*> = runCatching {
        Class.forName(name, false, loader)
    }.getOrElse { error("未加载到稳定类 $name（宿主版本结构不支持）: ${it.message}") }

    /** 取出唯一命中；未命中或歧义时抛错，异常信息即诊断信息。 */
    private fun <T> ShapeSelector.Pick<T>.orFail(): T = when (this) {
        is ShapeSelector.Pick.Found -> value
        is ShapeSelector.Pick.Rejected -> error(reason)
    }

    private fun <T> pickUnique(
        label: String,
        candidates: List<T>,
        describe: (T) -> String,
        predicate: (T) -> Boolean,
    ): T = ShapeSelector.pickUnique(label, candidates, describe, predicate).orFail()
}
