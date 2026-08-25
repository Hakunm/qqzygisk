package com.qm.qqzygisk.hook.utils

import android.util.Log
import com.qm.qqzygisk.BuildConfig
import com.v7878.zygisk.ZygoteLoader
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
object Log {
    private const val LSP = "qm.mod"
    private const val TAG = "QHook"

    private data class WLogData(
        val tag: String = TAG,
        val priority: String = "",
        var msg: String = "",
        var throwable: Throwable? = null
    ) {
        companion object {
            val packageName: String by lazy {
                ModuleUtils.packageName ?: BuildConfig.APPLICATION_ID
            }
        }
        override fun toString() = "[$tag][$priority][$packageName] $msg"
    }

    private fun log(data: WLogData) {
        when (data.priority) {
            "D" -> Log.d(LSP, data.toString(), data.throwable)
            "I" -> Log.i(LSP, data.toString(), data.throwable)
            "W" -> Log.w(LSP, data.toString(), data.throwable)
            "E" -> Log.e(LSP, data.toString(), data.throwable)
            else -> Log.wtf(LSP, data.toString(), data.throwable)
        }
    }

    fun debug(msg: Any? = null, e: Throwable? = null) {
        log(WLogData(priority = "D", msg = msg.toString(), throwable = e))
    }

    fun info(msg: Any? = null, e: Throwable? = null) {
        log(WLogData(priority = "I", msg = msg.toString(), throwable = e))
    }

    fun warn(msg: Any? = null, e: Throwable? = null) {
        log(WLogData(priority = "W", msg = msg.toString(), throwable = e))
    }

    fun error(msg: Any? = null, e: Throwable? = null) {
        log(WLogData(priority = "E", msg = msg.toString(), throwable = e))
    }
}