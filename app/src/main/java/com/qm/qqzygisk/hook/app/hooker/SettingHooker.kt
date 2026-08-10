package com.qm.qqzygisk.hook.app.hooker

import android.content.Context
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.startModuleSettings
import java.lang.reflect.Proxy

object SettingHooker : BaseHooker()  {
    override val key = "settings_entry"
    override val name = "设置界面增强"

    override val isShow: Boolean = false

    override fun initOnce() {
        // 9.1.65.24690(9516) com.tencent.mobileqq.setting.processor.j
        val kSimpleItemProcessor = "com.tencent.mobileqq.setting.processor.i".toAppClass()
        val ctorSimpleItemProcessor = kSimpleItemProcessor
            .resolve()
            .firstConstructor {
                parameters(Context::class, Int::class, CharSequence::class, Int::class, String::class)
                superclass()
            }

        // 9.1.65.24690(9516) com.tencent.mobileqq.setting.main.NewSettingConfigProvider
        "com.tencent.mobileqq.setting.main.b".toAppClass()
            .resolve()
            .firstMethod {
                parameters(Context::class)
                returnType(List::class)
            }.hook {
                after {
                    @Suppress("UNCHECKED_CAST")
                    val result = this.result as MutableList<Any>
                    val ctx = this.args[0] as Context
//                    val kItemProcessorGroup = result[0].javaClass
//                    val ctor = kItemProcessorGroup
//                        .resolve()
//                        .firstConstructor {
//                            parameters(List::class, CharSequence::class, CharSequence::class, Int::class, "kotlin.jvm.internal.DefaultConstructorMarker")
//                        }
                    val resId = ctx.resources.getIdentifier("qui_tuning", "drawable", ctx.packageName);
                    val entryItem = ctorSimpleItemProcessor.create(ctx, 6, "QQ Zygisk", resId, null)
                    val setOnClickListener = entryItem.asResolver().firstMethod {
                        name = "B"
                        parameters {
                            it[0].name == "kotlin.jvm.functions.Function0"
                        }
                    }
                    val func0: Any? = Proxy.newProxyInstance(
                        appClassLoader,
                        arrayOf<Class<*>?>(setOnClickListener.self.parameters[0].type)
                    ) { proxy, method, args ->
                        if (method.name.equals("invoke")) {
                            ctx.startModuleSettings()
                            return@newProxyInstance null
                        }
                        method.invoke(this, args)
                    }

                    setOnClickListener.invoke(func0)

                    val resultA = result[1].asResolver().firstField { name = "a" }
                    val group = resultA.get<MutableList<Any>>()
                    group?.add(entryItem)
                    resultA.set(group)
//                    val group = ctor.create(mutableListOf(entryItem), "", "", 6, null)
//                    result.add(2, group)
                }
            }
    }
}
