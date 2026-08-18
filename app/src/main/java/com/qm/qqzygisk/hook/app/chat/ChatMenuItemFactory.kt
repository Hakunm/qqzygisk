package com.qm.qqzygisk.hook.app.chat

import com.v7878.dex.DexConstants.ACC_CONSTRUCTOR
import com.v7878.dex.DexConstants.ACC_FINAL
import com.v7878.dex.DexConstants.ACC_PRIVATE
import com.v7878.dex.DexConstants.ACC_PUBLIC
import com.v7878.dex.DexIO
import com.v7878.dex.builder.ClassBuilder
import com.v7878.dex.builder.CodeBuilder.InvokeKind.DIRECT
import com.v7878.dex.builder.CodeBuilder.InvokeKind.INTERFACE
import com.v7878.dex.immutable.ClassDef
import com.v7878.dex.immutable.Dex
import com.v7878.dex.immutable.FieldId
import com.v7878.dex.immutable.MethodId
import com.v7878.dex.immutable.ProtoId
import com.v7878.dex.immutable.TypeId
import com.v7878.unsafe.DexFileUtils
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行时生成 QQ 聊天菜单项子类。
 *
 * QQ 长按菜单项是抽象基类，不能直接 new，也不能在模块 APK 里写死继承
 * （基类名和抽象方法名会随 QQ 版本变化）。这里按当前进程里扫到的
 * [baseClass] 动态写一份 DEX：覆盖标题、图标、id、点击，点击时执行传入的
 * [Runnable]。
 */
internal object ChatMenuItemFactory {
    private const val GENERATED_CLASS_PREFIX = "com.qm.qqzygisk.generated.ChatMenuItem_"

    /** 每个 QQ 菜单基类只生成一次，后续 [create] 复用同一个构造器。 */
    private val constructors = ConcurrentHashMap<Class<*>, Constructor<*>>()

    /** 判断菜单项是否由本工厂生成，避免 [ChatMenu] 重复插入。 */
    fun isGenerated(item: Any): Boolean =
        item.javaClass.name.startsWith(GENERATED_CLASS_PREFIX)

    /**
     * @param baseClass QQ 当前版本的菜单项抽象基类
     * @param message 当前长按的 AIOMsgItem
     * @param stringMethods 返回标题的无参 String 方法，全部覆盖成 [title]
     * @param iconMethod 返回图标 resId 的方法；没有图标时为 null
     * @param idMethod 返回菜单项 id 的方法
     * @param clickMethod 抽象点击方法，覆盖后转调 [callback]
     */
    fun create(
        baseClass: Class<*>,
        message: Any,
        title: String,
        icon: Int,
        id: Int,
        stringMethods: List<Method>,
        iconMethod: Method?,
        idMethod: Method,
        clickMethod: Method,
        callback: Runnable,
    ): Any {
        val constructor = constructors[baseClass] ?: synchronized(this) {
            constructors[baseClass] ?: buildConstructor(
                baseClass,
                stringMethods,
                iconMethod,
                idMethod,
                clickMethod,
            ).also { constructors[baseClass] = it }
        }
        return constructor.newInstance(message, title, icon, id, callback)
    }

    private fun buildConstructor(
        baseClass: Class<*>,
        stringMethods: List<Method>,
        iconMethod: Method?,
        idMethod: Method,
        clickMethod: Method,
    ): Constructor<*> {
        // 现网菜单项都是 (AIOMsgItem) 单参构造，其它签名直接拒绝。
        val superConstructor = baseClass.declaredConstructors.singleOrNull {
            it.parameterCount == 1 &&
                it.parameterTypes[0].name == "com.tencent.mobileqq.aio.msg.AIOMsgItem"
        } ?: error("不支持的菜单项构造器: ${baseClass.name}")

        val messageType = TypeId.of(superConstructor.parameterTypes[0])
        val stringType = TypeId.of(String::class.java)
        val runnableType = TypeId.of(Runnable::class.java)
        val baseType = TypeId.of(baseClass)
        val generatedName = GENERATED_CLASS_PREFIX + Integer.toHexString(baseClass.name.hashCode())
        val generatedType = TypeId.ofName(generatedName)

        val titleField = FieldId.of(generatedType, "title", stringType)
        val iconField = FieldId.of(generatedType, "icon", TypeId.I)
        val idField = FieldId.of(generatedType, "id", TypeId.I)
        val callbackField = FieldId.of(generatedType, "callback", runnableType)
        val generatedConstructor = MethodId.constructor(
            generatedType,
            messageType,
            stringType,
            TypeId.I,
            TypeId.I,
            runnableType,
        )
        val runnableRun = MethodId.of(
            runnableType,
            "run",
            ProtoId.of(TypeId.V),
        )

        val classDef = ClassBuilder.build(generatedType) { classBuilder ->
            classBuilder
                .withSuperClass(baseType)
                .withFlags(ACC_PUBLIC or ACC_FINAL)
                .withField { fieldBuilder ->
                    fieldBuilder.of(titleField).withFlags(ACC_PRIVATE or ACC_FINAL)
                }
                .withField { fieldBuilder ->
                    fieldBuilder.of(iconField).withFlags(ACC_PRIVATE or ACC_FINAL)
                }
                .withField { fieldBuilder ->
                    fieldBuilder.of(idField).withFlags(ACC_PRIVATE or ACC_FINAL)
                }
                .withField { fieldBuilder ->
                    fieldBuilder.of(callbackField).withFlags(ACC_PRIVATE or ACC_FINAL)
                }
                .withMethod { methodBuilder ->
                    methodBuilder
                        .of(generatedConstructor)
                        .withFlags(ACC_PUBLIC or ACC_CONSTRUCTOR)
                        .withCode(0) { code ->
                            // <init>(message, title, icon, id, callback)
                            code
                                .invoke(
                                    DIRECT,
                                    MethodId.of(superConstructor),
                                    code.this_(),
                                    code.p(0),
                                )
                                .iput(code.p(1), code.this_(), titleField)
                                .iput(code.p(2), code.this_(), iconField)
                                .iput(code.p(3), code.this_(), idField)
                                .iput(code.p(4), code.this_(), callbackField)
                                .return_void()
                        }
                }

            // 标题相关 getter 在不同 QQ 版本里可能有多个名字，全部返回同一 title。
            stringMethods.forEach { method ->
                val methodId = MethodId.of(
                    generatedType,
                    method.name,
                    ProtoId.of(stringType),
                )
                classBuilder.withMethod { methodBuilder ->
                    methodBuilder
                        .of(methodId)
                        .withFlags(ACC_PUBLIC)
                        .withCode(1) { code ->
                            code
                                .iget(code.l(0), code.this_(), titleField)
                                .return_object(code.l(0))
                        }
                }
            }

            iconMethod?.let { method ->
                classBuilder.addIntGetter(generatedType, method.name, iconField)
            }
            classBuilder.addIntGetter(generatedType, idMethod.name, idField)

            val clickMethodId = MethodId.of(
                generatedType,
                clickMethod.name,
                ProtoId.of(TypeId.V),
            )
            classBuilder.withMethod { methodBuilder ->
                methodBuilder
                    .of(clickMethodId)
                    .withFlags(ACC_PUBLIC)
                    .withCode(1) { code ->
                        code
                            .iget(code.l(0), code.this_(), callbackField)
                            .invoke(INTERFACE, runnableRun, code.l(0))
                            .return_void()
                    }
            }
        }

        val dexFile = DexFileUtils.openDexFile(DexIO.write(Dex.of(classDef)))
        // 生成类要挂到 QQ 的 ClassLoader 上，并标成 trusted，才能被宿主菜单框架加载。
        DexFileUtils.setTrusted(dexFile)
        val generatedClass = DexFileUtils.loadClass(
            dexFile,
            generatedName,
            baseClass.classLoader,
        )
        return generatedClass.getDeclaredConstructor(
            superConstructor.parameterTypes[0],
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Runnable::class.java,
        )
    }

    private fun ClassBuilder.addIntGetter(
        generatedType: TypeId,
        methodName: String,
        field: FieldId,
    ) {
        val methodId = MethodId.of(
            generatedType,
            methodName,
            ProtoId.of(TypeId.I),
        )
        withMethod { methodBuilder ->
            methodBuilder
                .of(methodId)
                .withFlags(ACC_PUBLIC)
                .withCode(1) { code ->
                    code
                        .iget(code.l(0), code.this_(), field)
                        .return_(code.l(0))
                }
        }
    }
}
