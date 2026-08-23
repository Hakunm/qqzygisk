package com.qm.qqzygisk.hook.app.chat

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.extension.hookAll
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

internal object NtImageRkeyProvider {
    private const val RKEY_SERVICE_COMMAND = "OidbSvcTrpcTcp.0x9067_202"

    private val lock = Object()
    private val responseFields = ConcurrentHashMap<Class<*>, Field>()
    private val serviceCommandMethods = ConcurrentHashMap<Class<*>, Method>()
    private val wupBufferMethods = ConcurrentHashMap<Class<*>, Method>()

    @Volatile
    private var snapshot: RkeySnapshot? = null

    @Volatile
    private var lastRefreshAt = 0L

    @Volatile
    private var refreshInFlight = false

    fun installHook() {
        val handlerClass = Class.forName(
            "mqq.app.msghandle.MsgRespHandler",
            false,
            appClassLoader,
        )
        val dispatchMethods = handlerClass.resolve().method {
            name = "dispatchRespMsg"
        }
        check(dispatchMethods.isNotEmpty()) { "未找到 MsgRespHandler.dispatchRespMsg" }

        dispatchMethods.hookAll {
            before {
                val responseHolder = args.getOrNull(1) ?: return@before
                runCatching {
                    captureRkeys(responseHolder)
                }.onFailure {
                    Log.error("捕获 NT 图片 rkey 失败", it)
                }
            }
        }
        requestRefresh(force = snapshot == null)
    }

    fun get(originUrl: String): String? {
        ensureFresh(NtImageRkey.REFRESH_WAIT_MS)
        return snapshot?.let { NtImageRkey.select(originUrl, it) }
    }

    fun sign(url: String): String {
        if (!NtImageRkey.needsRkey(url)) return url
        val rkey = get(url) ?: return url
        return NtImageRkey.apply(url, rkey)
    }

    fun ensureFresh(waitMs: Long = 0L) {
        if (!NtImageRkey.shouldRefresh(snapshot, System.currentTimeMillis())) return
        requestRefresh()
        if (waitMs <= 0L) return
        val deadline = System.currentTimeMillis() + waitMs
        synchronized(lock) {
            while (refreshInFlight && NtImageRkey.shouldRefresh(snapshot, System.currentTimeMillis())) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                lock.wait(remaining)
            }
            if (refreshInFlight && NtImageRkey.shouldRefresh(snapshot, System.currentTimeMillis())) {
                refreshInFlight = false
                lock.notifyAll()
            }
        }
    }

    private fun requestRefresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (refreshInFlight) return
            if (!force && !NtImageRkey.shouldRefresh(snapshot, now)) return
            val cooldown = if (snapshot?.hasUsable(now) == true) {
                NtImageRkey.REFRESH_COOLDOWN_MS
            } else {
                NtImageRkey.EMPTY_COOLDOWN_MS
            }
            if (!force && now - lastRefreshAt < cooldown) return
            refreshInFlight = true
            lastRefreshAt = now
        }
        thread(name = "qqzygisk-rkey", isDaemon = true) {
            try {
                sendRkeyRequest()
            } catch (error: Throwable) {
                Log.error("请求 NT 图片 rkey 失败", error)
                synchronized(lock) {
                    refreshInFlight = false
                    lock.notifyAll()
                }
            }
        }
    }

    private fun captureRkeys(responseHolder: Any) {
        val fromServiceMsg = findFromServiceMsg(responseHolder) ?: return
        val serviceCommand = serviceCommandMethods[fromServiceMsg.javaClass]
            ?: fromServiceMsg.javaClass.getMethod("getServiceCmd").also {
                serviceCommandMethods[fromServiceMsg.javaClass] = it
            }
        if (serviceCommand.invoke(fromServiceMsg) != RKEY_SERVICE_COMMAND) return

        val getWupBuffer = wupBufferMethods[fromServiceMsg.javaClass]
            ?: fromServiceMsg.javaClass.getMethod("getWupBuffer").also {
                wupBufferMethods[fromServiceMsg.javaClass] = it
            }
        val buffer = getWupBuffer.invoke(fromServiceMsg) as? ByteArray ?: return
        val parsed = NtImageRkey.parse(NtImageRkey.unpackWup(buffer), System.currentTimeMillis())
        synchronized(lock) {
            snapshot = parsed
            refreshInFlight = false
            lock.notifyAll()
        }
        Log.debug(
            "已更新 NT 图片 rkey types=${parsed.byType.keys} expireIn=${parsed.expiresAtMillis - System.currentTimeMillis()}ms",
        )
    }

    private fun sendRkeyRequest() {
        val runtime = peekAppRuntime() ?: error("AppRuntime 不可用")
        val uin = invokeNoArgString(runtime, "getAccount")
            ?: invokeNoArgString(runtime, "getCurrentAccountUin")
            ?: error("当前账号不可用")
        val toServiceMsgClass = Class.forName(
            "com.tencent.qphone.base.remote.ToServiceMsg",
            false,
            appClassLoader,
        )
        val message = toServiceMsgClass
            .getConstructor(String::class.java, String::class.java, String::class.java)
            .newInstance("mobileqq.service", uin, RKEY_SERVICE_COMMAND)
        toServiceMsgClass.getMethod("putWupBuffer", ByteArray::class.java)
            .invoke(message, NtImageRkey.prefixWup(NtImageRkey.REQUEST_OIDB))
        runCatching {
            toServiceMsgClass.getMethod("setNeedCallback", Boolean::class.javaPrimitiveType)
                .invoke(message, true)
        }
        runCatching {
            toServiceMsgClass.getMethod("setTimeout", Long::class.javaPrimitiveType)
                .invoke(message, 8_000L)
        }
        runCatching {
            val extra = runCatching {
                toServiceMsgClass.getMethod("getExtraData").invoke(message)
            }.getOrNull() ?: runCatching {
                toServiceMsgClass.getField("extraData").get(message)
            }.getOrNull()
            extra?.javaClass
                ?.getMethod("putBoolean", String::class.java, Boolean::class.javaPrimitiveType)
                ?.invoke(extra, "req_pb_protocol_flag", true)
        }
        val send = generateSequence(runtime.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == "sendToService" && it.parameterTypes.size == 1 }
            ?: error("sendToService 不可用")
        send.isAccessible = true
        send.invoke(runtime, message)
        Log.debug("已请求刷新 NT 图片 rkey")
    }

    private fun peekAppRuntime(): Any? {
        val mobileQQClass = Class.forName("mqq.app.MobileQQ", false, appClassLoader)
        val mobileQQ = runCatching { mobileQQClass.getField("sMobileQQ").get(null) }.getOrNull()
            ?: runCatching { mobileQQClass.getMethod("getMobileQQ").invoke(null) }.getOrNull()
            ?: return null
        return runCatching {
            mobileQQ.javaClass.methods
                .first { it.name == "peekAppRuntime" && it.parameterTypes.isEmpty() }
                .invoke(mobileQQ)
        }.getOrNull() ?: runCatching {
            val wait = mobileQQ.javaClass.methods.first { it.name == "waitAppRuntime" }
            wait.isAccessible = true
            when (wait.parameterCount) {
                0 -> wait.invoke(mobileQQ)
                else -> wait.invoke(mobileQQ, *Array(wait.parameterCount) { null })
            }
        }.getOrNull()
    }

    private fun invokeNoArgString(target: Any, name: String): String? {
        val method = generateSequence(target.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(target) as? String }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun findFromServiceMsg(responseHolder: Any): Any? {
        val holderClass = responseHolder.javaClass
        val field = responseFields[holderClass] ?: generateSequence(holderClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull {
                it.name == "fromServiceMsg" ||
                    it.type.name == "com.tencent.qphone.base.remote.FromServiceMsg"
            }
            ?.apply { isAccessible = true }
            ?.also { responseFields[holderClass] = it }
            ?: return null
        return field.get(responseHolder)
    }
}
