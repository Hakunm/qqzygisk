package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Queries the kernel once, without changing or retaining any message record. */
internal object RevokeMessageLookup {
    enum class Status { PRESENT, MISSING, UNKNOWN }

    private val queryMethods = ConcurrentHashMap<Class<*>, Method>()
    private val recordReaders = ConcurrentHashMap<Class<*>, RecalledRecordReader>()

    fun lookup(account: String, chatType: Int, peer: String, seq: Long?): Status {
        if (account.isEmpty() || peer.isEmpty() || seq == null || seq <= 0) return Status.UNKNOWN
        return runCatching {
            val service = NtMsgAccess.kernelMsgService() ?: return Status.UNKNOWN
            val contact = NtMsgAccess.createContact(chatType, peer) ?: return Status.UNKNOWN
            lookup(service, contact, RecalledMessageKey(account, chatType, peer, seq))
        }.getOrDefault(Status.UNKNOWN)
    }

    internal fun lookup(service: Any, contact: Any, key: RecalledMessageKey, timeoutMs: Long = 2_000L): Status =
        runCatching {
            val method = queryMethods[service.javaClass] ?: service.javaClass.methods.firstOrNull {
                val p = it.parameterTypes
                it.name == "getSingleMsg" && p.size == 3 && p[0].isInstance(contact) &&
                    p[1] == Long::class.javaPrimitiveType && p[2].isInterface
            }?.apply { isAccessible = true }?.also { queryMethods[service.javaClass] = it }
                ?: return Status.UNKNOWN
            val done = CountDownLatch(1)
            val result = AtomicReference(Status.UNKNOWN)
            val callbackType = method.parameterTypes[2]
            val callback = Proxy.newProxyInstance(callbackType.classLoader ?: appClassLoader, arrayOf(callbackType)) { proxy, called, args ->
                when (called.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "RevokeMessageLookupCallback"
                    "onResult" -> {
                        val code = (args?.getOrNull(0) as? Number)?.toInt()
                        val records = args?.getOrNull(2) as? List<*>
                        var status = if (code == 0 && records != null) Status.MISSING else Status.UNKNOWN
                        if (code == 0 && records != null) {
                            for (record in records) {
                                if (record == null) continue
                                val reader = recordReaders.computeIfAbsent(record.javaClass) { RecalledRecordReader(it) }
                                if (reader.unavailable != null) {
                                    status = Status.UNKNOWN
                                } else if (reader.keyOf(record, key.accountUin) == key) {
                                    status = Status.PRESENT
                                    break
                                }
                            }
                        }
                        result.set(status)
                        done.countDown()
                        null
                    }
                    else -> null
                }
            }
            method.invoke(service, contact, key.msgSeq, callback)
            if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) result.get() else Status.UNKNOWN
        }.getOrDefault(Status.UNKNOWN)
}
