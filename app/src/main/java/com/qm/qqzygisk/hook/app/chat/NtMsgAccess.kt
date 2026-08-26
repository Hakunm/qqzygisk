package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Reflection helpers for QQ NT message objects.
 *
 * Read-only on purpose: nothing here mutates a MsgRecord or its elements.
 * Editing a bound record is what crashes the AIO renderer.
 */
internal object NtMsgAccess {
    const val CHAT_TYPE_C2C = 1
    const val CHAT_TYPE_GROUP = 2

    const val MSG_SERVICE = "com.tencent.qqnt.msg.api.IMsgService"
    const val Q_ROUTE = "com.tencent.mobileqq.qroute.QRoute"

    /** QAuxiliary: io.github.qauxv.bridge.ntapi.RelationNTUinAndUidApi */
    private const val UIN_UID_API = "com.tencent.relation.common.api.IRelationNTUinAndUidApi"
    private const val KERNEL_SERVICE = "com.tencent.qqnt.kernel.api.IKernelService"

    val kernelMsgServices = arrayOf(
        "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService",
        "com.tencent.qqnt.kernel.api.IKernelMsgService",
        "com.tencent.qqnt.kernelpublic.nativeinterface.IKernelMsgService",
        "com.tencent.qqnt.msg.api.IKernelMsgService",
    )
    val kernelMsgServiceProxies = arrayOf(
        "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService\$CppProxy",
        "com.tencent.qqnt.kernelpublic.nativeinterface.IKernelMsgService\$CppProxy",
    )
    val sessionProxies = arrayOf(
        "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession\$CppProxy",
        "com.tencent.qqnt.kernelpublic.nativeinterface.IQQNTWrapperSession\$CppProxy",
    )
    val messageFacades = arrayOf(
        "com.tencent.mobileqq.msg.api.impl.MessageFacadeImpl",
        "com.tencent.mobileqq.app.QQMessageFacade",
        "com.tencent.imcore.message.QQMessageFacade",
    )
    val contactTypes = arrayOf(
        "com.tencent.qqnt.kernel.nativeinterface.Contact",
        "com.tencent.qqnt.kernelpublic.nativeinterface.Contact",
    )
    val jsonGrayElements = arrayOf(
        "com.tencent.qqnt.kernel.nativeinterface.JsonGrayElement",
        "com.tencent.qqnt.kernelpublic.nativeinterface.JsonGrayElement",
    )

    private val methodCache = ConcurrentHashMap<String, Method?>()
    private val fieldCache = ConcurrentHashMap<String, Field?>()

    @Volatile
    private var cachedSelfUid: String? = null

    fun loadClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, appClassLoader) }.getOrNull()

    fun loadFirst(vararg names: String): Class<*>? =
        names.firstNotNullOfOrNull { loadClass(it) }

    fun qRouteApi(apiType: Class<*>): Any? {
        val qRoute = loadClass(Q_ROUTE) ?: return null
        val method = qRoute.declaredMethods.firstOrNull { candidate ->
            Modifier.isStatic(candidate.modifiers) &&
                candidate.name == "api" &&
                candidate.parameterTypes.contentEquals(arrayOf(Class::class.java))
        } ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(null, apiType) }.getOrNull()
    }

    fun currentRuntime(): Any? {
        val mobileQq = loadClass("mqq.app.MobileQQ") ?: return null
        val instance = runCatching {
            mobileQq.getDeclaredField("sMobileQQ").apply { isAccessible = true }.get(null)
        }.getOrNull() ?: mobileQq.declaredMethods.firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.isEmpty() &&
                (it.name == "getMobileQQ" || it.name == "peekContext")
        }?.let { method ->
            method.isAccessible = true
            runCatching { method.invoke(null) }.getOrNull()
        } ?: return null
        readField(instance, "mAppRuntime")?.let { return it }
        return invokeNoArg(instance, "peekAppRuntime") ?: invokeNoArg(instance, "waitAppRuntime")
    }

    fun createContact(chatType: Int, peerUid: String, guildId: String = ""): Any? {
        val type = loadFirst(*contactTypes) ?: return null
        val ctor = type.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType, String::class.java, String::class.java),
            )
        } ?: return null
        ctor.isAccessible = true
        return runCatching { ctor.newInstance(chatType, peerUid, guildId) }.getOrNull()
    }

    /** IKernelMsgService instance, following QAuxiliary's MsgServiceHelper chain. */
    fun kernelMsgService(): Any? {
        val runtime = currentRuntime()
        if (runtime != null) {
            val kernelType = loadClass(KERNEL_SERVICE)
            val getRuntimeService = runtime.javaClass.methods.firstOrNull {
                it.name == "getRuntimeService" && it.parameterCount in 1..2
            }
            if (kernelType != null && getRuntimeService != null) {
                getRuntimeService.isAccessible = true
                val kernel = runCatching {
                    if (getRuntimeService.parameterCount == 2) {
                        getRuntimeService.invoke(runtime, kernelType, "")
                    } else {
                        getRuntimeService.invoke(runtime, kernelType)
                    }
                }.getOrNull()
                val wrapper = kernel?.let { invokeNoArg(it, "getMsgService") }
                if (wrapper != null) {
                    invokeNoArg(wrapper, "getService")?.let { return it }
                    wrapper.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && it.returnType.name.endsWith("IKernelMsgService")
                    }?.let { method ->
                        method.isAccessible = true
                        runCatching { method.invoke(wrapper) }.getOrNull()?.let { return it }
                    }
                    return wrapper
                }
            }
        }
        kernelMsgServices.forEach { name ->
            val type = loadClass(name) ?: return@forEach
            qRouteApi(type)?.let { return it }
        }
        return loadClass(MSG_SERVICE)?.let { qRouteApi(it) }
    }

    fun selfUin(): String {
        val runtime = currentRuntime() ?: return ""
        return listOf("getAccount", "getCurrentAccountUin", "getCurrentUin", "getLongAccountUin")
            .firstNotNullOfOrNull { name ->
                asString(invokeNoArg(runtime, name))?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it != "-1" }
            }
            .orEmpty()
    }

    /** The `u_xxx` uid of the logged in account, needed to tell self revokes apart. */
    fun selfUid(): String {
        cachedSelfUid?.let { return it }
        val runtime = currentRuntime()
        val direct = runtime?.let {
            listOf("getCurrentUid", "getCurrentAccountUid", "getSelfUid")
                .firstNotNullOfOrNull { name -> asString(invokeNoArg(it, name)) }
        }
        if (isUid(direct)) {
            return direct!!.also { cachedSelfUid = it }
        }
        val uin = selfUin()
        if (uin.isNotEmpty()) {
            val converted = uidFromUin(uin)
            if (isUid(converted)) {
                return converted!!.also { cachedSelfUid = it }
            }
        }
        return ""
    }

    fun uidFromUin(uin: String): String? = convertUinUid("getUidFromUin", uin)

    fun uinFromUid(uid: String): String? = convertUinUid("getUinFromUid", uid)

    private fun convertUinUid(name: String, value: String): String? {
        if (value.isEmpty()) return null
        val apiType = loadClass(UIN_UID_API) ?: return null
        val api = qRouteApi(apiType) ?: return null
        val method = runCatching { apiType.getMethod(name, String::class.java) }.getOrNull() ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(api, value) as? String }.getOrNull()
            ?.takeIf { it.isNotEmpty() && it != "0" }
    }

    fun isUid(value: String?): Boolean =
        value != null && value.startsWith("u_") && value.length >= 8

    fun selfIds(): Set<String> {
        val ids = LinkedHashSet<String>()
        selfUin().takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        selfUid().takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        return ids
    }

    fun isSelfId(value: String, ids: Set<String> = selfIds()): Boolean =
        value.isNotEmpty() && ids.isNotEmpty() && value in ids

    /** Maps legacy `istroop` onto NT chat types when `chatType` is absent. */
    fun normalizedChatType(item: Any): Int {
        val type = asInt(read(item, "getChatType", "chatType")) ?: -1
        if (type == CHAT_TYPE_C2C || type == CHAT_TYPE_GROUP) return type
        return when (asInt(read(item, "getIstroop", "istroop"))) {
            0 -> CHAT_TYPE_C2C
            1 -> CHAT_TYPE_GROUP
            else -> type
        }
    }

    fun revokeNotifyOperator(item: Any): String =
        asString(
            read(
                item,
                "getFromUin",
                "fromUin",
                "getFromUid",
                "fromUid",
                "getOperatorUid",
                "operatorUid",
            ),
        ).orEmpty()

    /** True when the object carries a message element list, i.e. it is a record, not a revoke notice. */
    fun hasElements(item: Any): Boolean =
        read(item, "getElements", "elements", "mElements") is List<*>

    fun invokeNoArg(target: Any, name: String): Any? {
        val method = findMethod(target.javaClass, name) { it.parameterTypes.isEmpty() } ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(target) }.getOrNull()
    }

    fun read(target: Any, vararg names: String): Any? {
        names.forEach { name ->
            invokeNoArg(target, name)?.let { return it }
            if (!name.startsWith("get") && !name.startsWith("is")) {
                readField(target, name)?.let { return it }
                invokeNoArg(target, "get" + name.replaceFirstChar { it.uppercase() })?.let { return it }
            }
        }
        return null
    }

    fun asLong(value: Any?): Long? = when (value) {
        null -> null
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> value.toString().toLongOrNull()
    }

    fun asInt(value: Any?): Int? = asLong(value)?.toInt()

    fun asString(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Number -> value.toString()
        else -> null
    }

    private fun readField(target: Any, name: String): Any? {
        val field = findField(target.javaClass, name) ?: return null
        return runCatching { field.get(target) }.getOrNull()
    }

    private fun findField(type: Class<*>, name: String): Field? {
        val key = "${type.name}#$name"
        if (fieldCache.containsKey(key)) return fieldCache[key]
        val field = generateSequence(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.name == name }
            ?.apply { isAccessible = true }
        fieldCache[key] = field
        return field
    }

    private fun findMethod(
        type: Class<*>,
        name: String,
        predicate: (Method) -> Boolean,
    ): Method? {
        val key = "${type.name}#$name#${predicate.hashCode()}"
        if (methodCache.containsKey(key)) return methodCache[key]
        val method = generateSequence(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && predicate(it) }
        methodCache[key] = method
        return method
    }
}
