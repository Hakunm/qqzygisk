package com.qm.qqzygisk.hook.extension

import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ModuleUtils
import com.v7878.vmtools.Hooks
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * 防止 ART AOT/JIT 内联绕过方法 Hook 的去优化工具。
 *
 * `Hooks.hook` 仅修改目标方法自身的入口与编译标记，不会使已经把目标方法内联（或经单态去虚化后内联）
 * 的调用方失效。设备经过夜间 `bg-dexopt-job`（`speed-profile`）或运行期 JIT 编译后，调用方会直接执行
 * 内联机器码，导致目标方法上的 Hook 静默失效。
 *
 * 对调用方执行去优化会清除其 `kAccPreCompiled`、置位 `kAccCompileDontBother` 并将入口重置为解释器桥，
 * 从而确保所有调用始终经过已 Hook 方法的真实入口。去重键包含 ClassLoader 与声明类身份，
 * 宿主切换 ClassLoader 后会对新副本重新执行去优化。
 */
internal object HookDeoptimizer {
    private val deoptimized = Collections.synchronizedSet(mutableSetOf<String>())

    /** 已安装 hook 的方法集合：严禁对其再次执行 deoptimize，防止把 hook trampoline 覆写回解释器入口。 */
    private val hooked = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var xpDeoptimizeResolved = false

    @Volatile
    private var xpDeoptimizeMethod: Method? = null

    /**
     * 生成包含 ClassLoader 与声明类身份的成员唯一键。
     *
     * 仅用 `toGenericString()` 会在宿主热更新或切换 ClassLoader 后将新旧两份同名类误判为同一方法。
     */
    fun memberKey(executable: Executable): String {
        val owner = executable.declaringClass
        return "${System.identityHashCode(owner.classLoader)}:${System.identityHashCode(owner)}:${executable.toGenericString()}"
    }

    /** 登记已安装 hook 的目标成员，避免后续调用方扫描误将该目标本身去优化从而冲掉 hook 入口。 */
    fun markHooked(executable: Executable) {
        hooked.add(memberKey(executable))
    }

    internal fun isHooked(executable: Executable): Boolean = memberKey(executable) in hooked

    /**
     * 对单个调用方方法/构造器执行去优化。
     *
     * 抽象方法、已安装 hook 的目标方法与已处理过的同一副本直接跳过；单个方法失败（如 ART intrinsic）只记日志不外抛。
     *
     * @return 本次是否实际执行了去优化
     */
    fun deoptimize(executable: Executable): Boolean {
        if (Modifier.isAbstract(executable.modifiers)) return false
        val key = memberKey(executable)
        if (key in hooked) return false
        if (!deoptimized.add(key)) return false
        return runCatching {
            if (ModuleUtils.isXpEnvironment) {
                invokeXposedDeoptimize(executable)
            } else {
                Hooks.deoptimize(executable)
                true
            }
        }.onFailure { error ->
            deoptimized.remove(key)
            Log.warn("去优化调用方失败: ${executable.declaringClass.name}.${executable.name}", error)
        }.getOrDefault(false)
    }

    /**
     * 对 [type] 声明的所有非抽象方法与构造器执行去优化（排除满足 [exclude] 的目标方法）。
     *
     * 适用于同类中直接调用目标方法的分发器与内部回调，无需等待 DEX 扫描即可立即生效。
     */
    fun deoptimizeDeclaredExecutables(
        type: Class<*>,
        exclude: (Executable) -> Boolean = { false },
    ): Int {
        var count = 0
        runCatching { type.declaredMethods }.getOrNull()?.forEach { method ->
            if (!exclude(method) && deoptimize(method)) count++
        }
        runCatching { type.declaredConstructors }.getOrNull()?.forEach { ctor ->
            if (!exclude(ctor) && deoptimize(ctor)) count++
        }
        return count
    }

    private fun invokeXposedDeoptimize(member: Member): Boolean {
        if (!xpDeoptimizeResolved) {
            synchronized(this) {
                if (!xpDeoptimizeResolved) {
                    xpDeoptimizeMethod = runCatching {
                        XposedBridge::class.java.getDeclaredMethod("deoptimizeMethod", Member::class.java)
                            .apply { isAccessible = true }
                    }.getOrNull()
                    xpDeoptimizeResolved = true
                }
            }
        }
        val method = xpDeoptimizeMethod ?: return false
        method.invoke(null, member)
        return true
    }
}
