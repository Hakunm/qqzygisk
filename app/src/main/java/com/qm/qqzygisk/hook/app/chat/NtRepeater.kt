package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.HashMap

/**
 * QAuxiliary RepeaterPlus NT path: load the original record, then either
 * `forwardMsg` (pic / market face / struct / ark) or `sendMsg` with the same elements.
 */
internal object NtRepeater {
    /** QAuxiliary MsgConstants.MSG_TYPE_WALLET */
    private const val MSG_TYPE_WALLET = 10

    fun isRepeatable(msg: Any?): Boolean {
        if (msg == null) return false
        return runCatching {
            val record = msgRecord(msg) ?: return false
            val msgType = NtMsgAccess.asInt(NtMsgAccess.read(record, "getMsgType", "msgType"))
            msgType != MSG_TYPE_WALLET
        }.onFailure { Log.warn("repeat-plus isRepeatable failed", it) }
            .getOrDefault(false)
    }

    fun repeat(msg: Any?, onError: (String) -> Unit) {
        if (msg == null) {
            onError("该消息不支持复读")
            return
        }
        if (!isRepeatable(msg)) {
            onError("该消息不支持复读")
            return
        }
        runCatching { repeatByForwardNt(msg, onError) }
            .onFailure {
                Log.error("repeat-plus failed", it)
                onError("复读失败，请重试")
            }
    }

    private fun repeatByForwardNt(msg: Any, onError: (String) -> Unit) {
        val recordHint = msgRecord(msg)
        val descriptor = ChatImageSender.contactFromCurrentAioParam()
            ?: contactFrom(recordHint)
            ?: ChatImageSender.currentContactOrNull()
        if (descriptor == null) {
            onError("没有可用的聊天会话，请重新进入聊天页面")
            return
        }
        val msgId = msgIdOf(msg, recordHint)
        if (msgId == null || msgId == 0L) {
            onError("无法读取消息")
            return
        }
        val service = NtMsgAccess.kernelMsgService()
        if (service == null) {
            onError("消息服务不可用")
            return
        }
        val contact = NtMsgAccess.createContact(descriptor.chatType, descriptor.peerUid, descriptor.guildId)
        if (contact == null) {
            onError("没有可用的聊天会话，请重新进入聊天页面")
            return
        }
        val getMsgs = findMethod(service, "getMsgsByMsgId") { it.parameterCount == 3 }
        if (getMsgs == null) {
            onError("消息服务不可用")
            return
        }
        val ids = ArrayList<Long>().apply { add(msgId) }
        val dests = ArrayList<Any>().apply { add(contact) }
        val attr = HashMap<Any, Any>()
        val callbackType = getMsgs.parameterTypes.last { it.isInterface }
        getMsgs.isAccessible = true
        getMsgs.invoke(
            service,
            *argsForGet(getMsgs, contact, ids, callbackProxy(callbackType) { records ->
                if (records.isNullOrEmpty()) {
                    onError("消息获取失败，请重试")
                    return@callbackProxy
                }
                val record = records.first() ?: run {
                    onError("消息获取失败，请重试")
                    return@callbackProxy
                }
                dispatchRepeat(service, contact, dests, ids, attr, record, descriptor.chatType, onError)
            }),
        )
    }

    private fun dispatchRepeat(
        service: Any,
        contact: Any,
        dests: ArrayList<Any>,
        ids: ArrayList<Long>,
        attr: HashMap<Any, Any>,
        record: Any,
        chatType: Int,
        onError: (String) -> Unit,
    ) {
        if (shouldForward(record)) {
            val forward = findMethod(service, "forwardMsg") { it.parameterCount >= 4 }
            if (forward == null) {
                onError("复读失败，请重试")
                return
            }
            forward.isAccessible = true
            val callbackType = forward.parameterTypes.last { it.isInterface }
            forward.invoke(service, *argsForForward(forward, ids, contact, dests, attr, noopCallback(callbackType)))
            Log.info("repeat-plus forwarded msgIds=$ids")
            return
        }
        val elements = elementsOf(record)
        if (elements.isEmpty()) {
            onError("该消息不支持复读")
            return
        }
        val send = findMethod(service, "sendMsg") { method ->
            method.parameterCount >= 4 &&
                (method.parameterTypes[0] == Long::class.javaPrimitiveType ||
                    method.parameterTypes[0] == java.lang.Long::class.java)
        }
        if (send == null) {
            onError("复读失败，请重试")
            return
        }
        send.isAccessible = true
        val callbackType = send.parameterTypes.last { it.isInterface }
        send.invoke(
            service,
            *argsForSend(send, nextUniqueId(service, chatType), contact, elements, attr, noopCallback(callbackType)),
        )
        Log.info("repeat-plus sent elements=${elements.size}")
    }

    /** QAuxiliary：只看第一条 element 的 pic / marketFace / struct / ark。 */
    private fun shouldForward(record: Any): Boolean {
        val first = elementsOf(record).firstOrNull() ?: return false
        return hasMediaElement(first)
    }

    private fun hasMediaElement(target: Any): Boolean =
        listOf("getPicElement", "getMarketFaceElement", "getStructMsgElement", "getArkElement")
            .any { NtMsgAccess.invokeNoArg(target, it) != null }

    private fun elementsOf(record: Any): ArrayList<Any> {
        val raw = NtMsgAccess.read(record, "getElements", "elements")
        val values = when (raw) {
            is ArrayList<*> -> raw
            is Collection<*> -> ArrayList(raw)
            else -> emptyList<Any?>()
        }
        val out = ArrayList<Any>(values.size)
        values.forEach { value -> if (value != null) out.add(value) }
        return out
    }

    private fun contactFrom(record: Any?): ChatImageSender.ContactDescriptor? {
        if (record == null) return null
        val chatType = NtMsgAccess.asInt(NtMsgAccess.read(record, "getChatType", "chatType")) ?: return null
        val peerUid = NtMsgAccess.asString(
            NtMsgAccess.read(record, "getPeerUid", "peerUid", "getPeerUin", "peerUin"),
        )?.takeIf { it.isNotBlank() } ?: return null
        val guildId = NtMsgAccess.asString(NtMsgAccess.read(record, "getGuildId", "guildId")).orEmpty()
        return ChatImageSender.ContactDescriptor(chatType, peerUid, guildId)
    }

    private fun msgRecord(msg: Any): Any? {
        if (msg.javaClass.name.contains("MsgRecord")) return msg
        return NtMsgAccess.invokeNoArg(msg, "getMsgRecord")
    }

    private fun msgIdOf(msg: Any, record: Any?): Long? =
        NtMsgAccess.asLong(NtMsgAccess.read(msg, "getMsgId", "msgId"))?.takeIf { it != 0L }
            ?: record?.let { NtMsgAccess.asLong(NtMsgAccess.read(it, "getMsgId", "msgId")) }

    private fun nextUniqueId(service: Any, chatType: Int): Long {
        val time = NtMsgAccess.serviceTimeMillis()
        findMethod(service, "generateMsgUniqueId") { true }?.let { method ->
            method.isAccessible = true
            val types = method.parameterTypes
            val result = runCatching {
                when {
                    types.size == 2 && isInt(types[0]) && isLong(types[1]) ->
                        method.invoke(service, chatType, time)
                    types.size == 2 && isInt(types[0]) && types[1] == String::class.java ->
                        method.invoke(service, chatType, time.toString())
                    else -> null
                }
            }.getOrNull()
            NtMsgAccess.asLong(result)?.let { return it }
        }
        findMethod(service, "getMsgUniqueId") { true }?.let { method ->
            method.isAccessible = true
            val types = method.parameterTypes
            val result = runCatching {
                when {
                    types.size == 1 && isLong(types[0]) -> method.invoke(service, time)
                    types.isEmpty() -> method.invoke(service)
                    else -> null
                }
            }.getOrNull()
            NtMsgAccess.asLong(result)?.let { return it }
        }
        return time
    }

    private fun argsForGet(method: Method, contact: Any, ids: ArrayList<Long>, callback: Any): Array<Any?> =
        method.parameterTypes.map { param ->
            when {
                param.isInstance(contact) || param.name.endsWith("Contact") -> contact
                List::class.java.isAssignableFrom(param) -> ids
                param.isInterface -> callback
                else -> null
            }
        }.toTypedArray()

    private fun argsForForward(
        method: Method,
        ids: ArrayList<Long>,
        contact: Any,
        dests: ArrayList<Any>,
        attr: HashMap<Any, Any>,
        callback: Any,
    ): Array<Any?> {
        var listSlot = 0
        return method.parameterTypes.map { param ->
            when {
                param.isInterface -> callback
                Map::class.java.isAssignableFrom(param) -> attr
                param.isInstance(contact) || (param.name.endsWith("Contact") && !List::class.java.isAssignableFrom(param)) ->
                    contact
                List::class.java.isAssignableFrom(param) -> if (listSlot++ == 0) ids else dests
                else -> null
            }
        }.toTypedArray()
    }

    private fun argsForSend(
        method: Method,
        uniqueId: Long,
        contact: Any,
        elements: ArrayList<Any>,
        attr: HashMap<Any, Any>,
        callback: Any,
    ): Array<Any?> =
        method.parameterTypes.map { param ->
            when {
                isLong(param) -> uniqueId
                param.isInstance(contact) || param.name.endsWith("Contact") -> contact
                List::class.java.isAssignableFrom(param) -> elements
                Map::class.java.isAssignableFrom(param) -> attr
                param.isInterface -> callback
                else -> null
            }
        }.toTypedArray()

    private fun callbackProxy(type: Class<*>, onList: (List<*>?) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader ?: appClassLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NtRepeaterGetCallback"
                else -> {
                    val list = args?.firstOrNull { it is List<*> } as? List<*>
                    if (list != null || args?.any { it is List<*> } == true) {
                        onList(list)
                    }
                    defaultValue(method.returnType)
                }
            }
        }

    private fun noopCallback(type: Class<*>): Any =
        Proxy.newProxyInstance(type.classLoader ?: appClassLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NtRepeaterOpCallback"
                else -> {
                    val code = args?.firstOrNull { it is Number } as? Number
                    if (code != null && code.toInt() != 0) {
                        Log.warn("repeat-plus kernel result=$code ${args.getOrNull(1)}")
                    }
                    defaultValue(method.returnType)
                }
            }
        }

    private fun findMethod(service: Any, name: String, predicate: (Method) -> Boolean): Method? =
        (service.javaClass.methods + service.javaClass.declaredMethods)
            .distinctBy { it.toGenericString() }
            .firstOrNull { it.name == name && predicate(it) }

    private fun isInt(type: Class<*>): Boolean =
        type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java

    private fun isLong(type: Class<*>): Boolean =
        type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }
}
