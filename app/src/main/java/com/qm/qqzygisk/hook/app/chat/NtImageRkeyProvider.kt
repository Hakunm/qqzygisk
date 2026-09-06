package com.qm.qqzygisk.hook.app.chat

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal object NtImageRkeyProvider {
    @Volatile
    private var snapshot: RkeySnapshot? = null

    private val wupBufferMethods = ConcurrentHashMap<Class<*>, Method>()

    @Volatile
    private var hooked = false

    fun installHook() {
        if (hooked) return
        hooked = true
        if (currentProcessName().contains(':')) return

        val fromType = Class.forName(
            "com.tencent.qphone.base.remote.FromServiceMsg",
            false,
            appClassLoader,
        )
        fromType.resolve().firstMethod { name = "getServiceCmd" }.hook {
            after {
                val command = result as? String ?: return@after
                val fromServiceMsg = instance ?: return@after
                val buffer = wupBuffer(fromServiceMsg)
                if (!NtImageRkey.isRkeyCommand(command)) {
                    if (!command.startsWith("OidbSvcTrpcTcp") || !NtImageRkey.containsRkey(buffer)) {
                        return@after
                    }
                }
                runCatching { applyFromServiceMsg(command, buffer) }.onFailure {
                    Log.error("捕获 NT 图片 rkey 失败", it)
                }
            }
        }
        Log.debug("已挂钩 FromServiceMsg.getServiceCmd process=${currentProcessName()}")
    }

    fun snapshot(): RkeySnapshot? = snapshot

    fun get(originUrl: String): String? =
        snapshot?.let { NtImageRkey.select(originUrl, it) }

    fun sign(url: String): String {
        if (!NtImageRkey.needsRkey(url)) return url
        val rkey = get(url) ?: return url
        return NtImageRkey.apply(url, rkey)
    }

    private fun applyFromServiceMsg(command: String, buffer: ByteArray?) {
        Log.debug(
            "收到 rkey 回包 process=${currentProcessName()} cmd=$command bytes=${buffer?.size ?: -1}",
        )
        if (buffer == null) return
        val unpacked = NtImageRkey.unpackWup(buffer)
        val parsed = NtImageRkey.parseOrScan(unpacked, System.currentTimeMillis())
        snapshot = parsed
        Log.debug(
            "已更新 NT 图片 rkey process=${currentProcessName()} " +
                "types=${parsed.byType.keys} private=${parsed.byType[NtImageRkey.TYPE_PRIVATE] != null} " +
                "group=${parsed.byType[NtImageRkey.TYPE_GROUP] != null}",
        )
    }

    private fun wupBuffer(fromServiceMsg: Any): ByteArray? {
        val getWupBuffer =
            wupBufferMethods[fromServiceMsg.javaClass]
                ?: fromServiceMsg.javaClass.getMethod("getWupBuffer").also {
                    wupBufferMethods[fromServiceMsg.javaClass] = it
                }
        return getWupBuffer.invoke(fromServiceMsg) as? ByteArray
    }

    private fun currentProcessName(): String =
        runCatching { android.app.Application.getProcessName() }.getOrNull().orEmpty()
}
