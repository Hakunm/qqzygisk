package com.qm.qqzygisk.hook.app.detect

import android.content.Context
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.Log
import org.lsposed.lsparanoid.Obfuscate
import java.lang.reflect.Modifier

/**
 * Always-on Turing Java interception. Not a settings item.
 *
 * Uses the 9.3.50 entry points from QQ hook detection (F-001/F-002/F-003):
 * `l3.b` mask, `return.a` bit setter, `TuringRiskService.reqRiskDetectV2`.
 */
@Obfuscate
object AntiDetect {
    private const val L3_CLASS = "com.tencent.turingfd.sdk.xq.l3"
    private const val RETURN_CLASS = "com.tencent.turingfd.sdk.xq.return"
    private const val RISK_SERVICE = "com.tencent.turingfd.sdk.xq.TuringRiskService"
    private const val FIELD_HIT = "251"
    private const val FIELD_MASK = "288"

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        runCatching { hookMaskBits() }
            .onFailure { Log.warn("Turing mask hook skipped", it) }
        runCatching { hookBitSetter() }
            .onFailure { Log.warn("Turing bit-setter hook skipped", it) }
        runCatching { hookRiskRequest() }
            .onFailure { Log.warn("Turing risk request hook skipped", it) }
    }

    private fun hookMaskBits() {
        val type = loadClass(L3_CLASS) ?: return
        val method = type.declaredMethods.firstOrNull { candidate ->
            Modifier.isStatic(candidate.modifiers) &&
                candidate.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
                (candidate.returnType == Int::class.javaPrimitiveType ||
                    candidate.returnType == Integer::class.java)
        } ?: return
        method.isAccessible = true
        method.hook {
            after { result = 0 }
        }
        Log.info("Turing mask entry hooked")
    }

    private fun hookBitSetter() {
        val type = loadClass(RETURN_CLASS) ?: return
        val method = type.declaredMethods.firstOrNull { candidate ->
            Modifier.isStatic(candidate.modifiers) &&
                candidate.parameterTypes.size == 3 &&
                candidate.parameterTypes[0] == Int::class.javaPrimitiveType &&
                candidate.parameterTypes[1] == Int::class.javaPrimitiveType &&
                candidate.parameterTypes[2] == Boolean::class.javaPrimitiveType
        } ?: return
        method.isAccessible = true
        method.hook {
            before {
                if (args.size >= 3) args[2] = false
            }
        }
        Log.info("Turing bit setter hooked")
    }

    private fun hookRiskRequest() {
        val type = loadClass(RISK_SERVICE) ?: return
        type.declaredMethods
            .filter { it.name == "reqRiskDetectV2" && Modifier.isStatic(it.modifiers) }
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    before { sanitizeRiskArgs(args) }
                }
            }
        Log.info("Turing risk request hooked")
    }

    private fun sanitizeRiskArgs(args: Array<Any?>) {
        args.forEach { arg ->
            if (arg is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val map = arg as MutableMap<Any?, Any?>
                if (map.containsKey(FIELD_HIT) || map.containsKey(FIELD_MASK) ||
                    map.containsKey(251) || map.containsKey(288)
                ) {
                    map[FIELD_HIT] = "0"
                    map[FIELD_MASK] = "0"
                    map[251] = "0"
                    map[288] = "0"
                }
            }
        }
    }

    private fun loadClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, appClassLoader) }.getOrNull()
}
