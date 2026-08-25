package com.qm.qqzygisk.hook.extension

import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.resolver.base.MemberResolver
import com.qm.qqzygisk.hook.utils.ModuleUtils
import com.v7878.unsafe.invoke.EmulatedStackFrame
import com.v7878.vmtools.HookTransformer
import com.v7878.vmtools.Hooks
import com.v7878.vmtools.Hooks.EntryPointType
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.invoke.MethodHandle
import java.lang.reflect.Executable
import java.lang.reflect.Modifier


infix fun MemberResolver<*, *>.hook(action: MemberHookCreator.() -> Unit) {
    MemberHookCreator(this).apply(action).build()
}

infix fun List<MemberResolver<*, *>>.hookAll(action: MemberHookCreator.() -> Unit) {
    this.forEach { it ->
        MemberHookCreator(it).apply(action).build()
    }
}

fun Executable.hook(action: MemberHookCreator.() -> Unit) {
    MemberHookCreator(this).apply(action).build()
}

class MemberHookCreator {
    /** [before] 回调 */
    private var beforeHookCallback: (MethodCall.() -> Unit)? = null

    /** [after] 回调 */
    private var afterHookCallback: (MethodCall.() -> Unit)? = null

    private val target: Executable
    private val isStatic: Boolean

    internal constructor(hookClass: MemberResolver<*, *>) {
        target = hookClass.self as Executable
        isStatic = hookClass.self.isStatic
    }

    internal constructor(executable: Executable) {
        target = executable
        isStatic = Modifier.isStatic(executable.modifiers)
    }

    fun before(initiate: MethodCall.() -> Unit) {
        beforeHookCallback = initiate
    }

    fun after(initiate: MethodCall.() -> Unit) {
        afterHookCallback = initiate
    }

    fun build() {
        if (ModuleUtils.isXpEnvironment) {
            xpBuild()
            return
        }
        val myHook = HookTransformer { original: MethodHandle, stack: EmulatedStackFrame ->
            val type = original.type()
            val paramCount = type.parameterCount()
            val initialArgs = arrayOfNulls<Any>(paramCount)
            val accessor = stack.accessor()
            for (i in 0 until paramCount) {
                initialArgs[i] = accessor.getValue(i)
            }

            val methodCall = MethodCall(initialArgs, isStatic)
            beforeHookCallback?.invoke(methodCall)

            val finalReturnValue: Any?
            val returnType = original.type().returnType()

            if (methodCall.shouldSkipOriginalMethodCall) {
                finalReturnValue = methodCall.result

                if (returnType != Void.TYPE && returnType != Void::class.javaPrimitiveType) {
                    accessor.setValue(EmulatedStackFrame.RETURN_VALUE_IDX, finalReturnValue)
                }
            } else {
                val originalResult = if (isStatic) {
                    original.invokeWithArguments(*methodCall.args)
                } else {
                    original.invokeWithArguments(methodCall.instance, *methodCall.args)
                }
                methodCall.result = originalResult
                afterHookCallback?.invoke(methodCall)

                finalReturnValue = methodCall.result

                if (returnType != Void.TYPE && returnType != Void::class.javaPrimitiveType) {
                    accessor.setValue(EmulatedStackFrame.RETURN_VALUE_IDX, finalReturnValue)
                }
            }
//            finalReturnValue
        }

        Hooks.hook(
            target,
            EntryPointType.DIRECT,
            myHook,
            EntryPointType.DIRECT
        )
    }

    private fun xpBuild() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                super.beforeHookedMethod(param)
                val args = if (isStatic) param.args else arrayOf(param.thisObject, *param.args)
                val methodCall = MethodCall(args, isStatic)
                beforeHookCallback?.invoke(methodCall)
                param.args = methodCall.args
                if (methodCall.shouldSkipOriginalMethodCall) {
                    param.result = methodCall.result
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                super.afterHookedMethod(param)
                val args = if (isStatic) param.args else arrayOf(param.thisObject, *param.args)
                val methodCall = MethodCall(args, isStatic)
                methodCall.result = param.result
                afterHookCallback?.invoke(methodCall)
                param.result = methodCall.result
            }
        }
        XposedBridge.hookMethod(
            target,
            hook
        )
    }
}