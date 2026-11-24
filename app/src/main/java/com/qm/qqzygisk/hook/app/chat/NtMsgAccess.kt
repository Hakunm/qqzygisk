package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.utils.Log
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

    /** com.tencent.qqnt.troopmemberlist.ITroopMemberListRepoApi */
    private const val TROOP_MEMBER_API = "com.tencent.qqnt.troopmemberlist.ITroopMemberListRepoApi"

    /** `from` tag for member lookups; the host requires it to be non-empty. */
    private const val TROOP_MEMBER_FROM = "anti_revoke_tip"
    private const val TROOP_MEMBER_TIMEOUT_MS = 500L

    val kernelMsgServices =
        arrayOf(
            "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService",
            "com.tencent.qqnt.kernel.api.IKernelMsgService",
            "com.tencent.qqnt.kernelpublic.nativeinterface.IKernelMsgService",
            "com.tencent.qqnt.msg.api.IKernelMsgService",
        )
    val kernelMsgServiceProxies =
        arrayOf(
            "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService\$CppProxy",
            "com.tencent.qqnt.kernelpublic.nativeinterface.IKernelMsgService\$CppProxy",
        )
    val sessionProxies =
        arrayOf(
            "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession\$CppProxy",
            "com.tencent.qqnt.kernelpublic.nativeinterface.IQQNTWrapperSession\$CppProxy",
        )
    val msgRespHandlers =
        arrayOf(
            "mqq.app.msghandle.MsgRespHandler",
        )
    val knownMsfDispatcherClasses =
        arrayOf(
            "mqq.app.msghandle.MSFRespHandleTask",
            "mqq.app.msghandle.MsfRespHandleUtil",
        )
    val messageFacades =
        arrayOf(
            "com.tencent.mobileqq.msg.api.impl.MessageFacadeImpl",
            "com.tencent.mobileqq.app.QQMessageFacade",
            "com.tencent.imcore.message.QQMessageFacade",
        )
    val contactTypes =
        arrayOf(
            "com.tencent.qqnt.kernel.nativeinterface.Contact",
            "com.tencent.qqnt.kernelpublic.nativeinterface.Contact",
        )
    val jsonGrayElements =
        arrayOf(
            "com.tencent.qqnt.kernel.nativeinterface.JsonGrayElement",
            "com.tencent.qqnt.kernelpublic.nativeinterface.JsonGrayElement",
        )

    private class MethodHolder(val method: Method?)
    private class FieldHolder(val field: Field?)

    /** 按 `Class` 身份缓存，避免切换 ClassLoader 后复用旧副本；使用非空 holder 避免 `ConcurrentHashMap` 写入 null 抛 NPE。 */
    private val noArgMethodCache = ConcurrentHashMap<String, MethodHolder>()
    private val singleArgMethodCache = ConcurrentHashMap<String, MethodHolder>()
    private val fieldCache = ConcurrentHashMap<String, FieldHolder>()

    /** 绑定当前账号 uin 的 uid 缓存，切号后 uin 变化自动失效，不再永久停留在旧账号。 */
    @Volatile
    private var cachedSelfIdentity: Pair<String, String>? = null

    @Volatile
    private var troopMemberWarned = false

    fun loadClass(name: String): Class<*>? = runCatching { Class.forName(name, false, appClassLoader) }.getOrNull()

    /** QAuxiliary QAppUtils.getServiceTime → NetConnInfoCenter.getServerTimeMillis */
    fun serviceTimeMillis(): Long {
        val type = loadClass("com.tencent.mobileqq.msf.core.NetConnInfoCenter")
        val method =
            type?.methods?.firstOrNull { candidate ->
                candidate.name == "getServerTimeMillis" && candidate.parameterCount == 0
            }
        if (method != null) {
            method.isAccessible = true
            asLong(runCatching { method.invoke(null) }.getOrNull())?.takeIf { it > 0L }?.let { return it }
        }
        return System.currentTimeMillis()
    }

    fun loadFirst(vararg names: String): Class<*>? = names.firstNotNullOfOrNull { loadClass(it) }

    fun qRouteApi(apiType: Class<*>): Any? {
        val qRoute = loadClass(Q_ROUTE) ?: return null
        val method =
            qRoute.declaredMethods.firstOrNull { candidate ->
                Modifier.isStatic(candidate.modifiers) &&
                    candidate.name == "api" &&
                    candidate.parameterTypes.contentEquals(arrayOf(Class::class.java))
            } ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(null, apiType) }.getOrNull()
    }

    fun currentRuntime(): Any? {
        val mobileQq = loadClass("mqq.app.MobileQQ") ?: return null
        val instance =
            runCatching {
                mobileQq.getDeclaredField("sMobileQQ").apply { isAccessible = true }.get(null)
            }.getOrNull() ?: mobileQq.declaredMethods
                .firstOrNull {
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

    fun createContact(
        chatType: Int,
        peerUid: String,
        guildId: String = "",
    ): Any? {
        val type = loadFirst(*contactTypes) ?: return null
        val ctor =
            type.declaredConstructors.firstOrNull { candidate ->
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
            val getRuntimeService =
                runtime.javaClass.methods.firstOrNull {
                    it.name == "getRuntimeService" && it.parameterCount in 1..2
                }
            if (kernelType != null && getRuntimeService != null) {
                getRuntimeService.isAccessible = true
                val kernel =
                    runCatching {
                        if (getRuntimeService.parameterCount == 2) {
                            getRuntimeService.invoke(runtime, kernelType, "")
                        } else {
                            getRuntimeService.invoke(runtime, kernelType)
                        }
                    }.getOrNull()
                val wrapper = kernel?.let { invokeNoArg(it, "getMsgService") }
                if (wrapper != null) {
                    invokeNoArg(wrapper, "getService")?.let { return it }
                    wrapper.javaClass.methods
                        .firstOrNull {
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
            }.orEmpty()
    }

    /** The `u_xxx` uid of the logged in account, needed to tell self revokes apart. */
    fun selfUid(): String {
        val uin = runCatching { selfUin() }.getOrDefault("")
        if (uin.isNotEmpty()) {
            cachedSelfIdentity?.takeIf { it.first == uin }?.let { return it.second }
        }
        val runtime = runCatching { currentRuntime() }.getOrNull()
        val direct =
            runtime?.let {
                listOf("getCurrentUid", "getCurrentAccountUid", "getSelfUid")
                    .firstNotNullOfOrNull { name -> asString(invokeNoArg(it, name)) }
            }
        if (isUid(direct)) {
            if (uin.isNotEmpty()) cachedSelfIdentity = uin to direct!!
            return direct!!
        }
        if (uin.isNotEmpty()) {
            val converted = runCatching { uidFromUin(uin) }.getOrNull()
            if (isUid(converted)) {
                cachedSelfIdentity = uin to converted!!
                return converted!!
            }
        }
        return ""
    }

    fun uidFromUin(uin: String): String? = convertUinUid("getUidFromUin", uin)

    fun uinFromUid(uid: String): String? = convertUinUid("getUinFromUid", uid)

    /**
     * The name the host itself would show for [memberUin] in the group [groupUin]:
     * remark, group card or nickname, in QQ's own priority order.
     *
     * Reads `ITroopMemberListRepoApi.getTroopMemberFromCache` first and falls back to
     * one `getTroopMemberInfoSync` with a bounded timeout on a cache miss, so this must
     * only be called off the main thread. Any reflection failure logs once and returns
     * null; the caller then keeps the real uin instead of inventing a name.
     */
    fun groupMemberDisplayName(
        groupUin: String,
        memberUin: String,
    ): String? {
        if (groupUin.isEmpty() || memberUin.isEmpty()) return null
        val apiType = loadClass(TROOP_MEMBER_API)
        val api = apiType?.let { qRouteApi(it) }
        if (apiType == null || api == null) {
            warnTroopMember("$TROOP_MEMBER_API missing")
            return null
        }
        val info =
            runCatching {
                lookupTroopMember(apiType, api, "getTroopMemberFromCache", groupUin, memberUin)
                    ?: lookupTroopMember(apiType, api, "getTroopMemberInfoSync", groupUin, memberUin)
            }.onFailure { warnTroopMember("troop member lookup failed group=$groupUin", it) }
                .getOrNull() ?: return null
        return runCatching {
            val nickInfo = info.javaClass.getField("nickInfo").get(info) ?: return@runCatching null
            (nickInfo.javaClass.getMethod("getShowName").invoke(nickInfo) as? String)
                ?.trim()?.takeIf { it.isNotEmpty() }
        }.onFailure { warnTroopMember("troop member display name failed group=$groupUin", it) }.getOrNull()
    }

    /**
     * getTroopMemberFromCache(String troopUin, String memberUin, LifecycleOwner?, String)
     * and getTroopMemberInfoSync with a trailing timeout. The owner is nullable and the
     * cache variant does no IO, so both are safe on the tip worker.
     */
    private fun lookupTroopMember(
        apiType: Class<*>,
        api: Any,
        name: String,
        groupUin: String,
        memberUin: String,
    ): Any? {
        val expected = if (name == "getTroopMemberInfoSync") 5 else 4
        val method =
            apiType.methods.firstOrNull { candidate ->
                val params = candidate.parameterTypes
                candidate.name == name &&
                    params.size == expected &&
                    params[0] == String::class.java &&
                    params[1] == String::class.java &&
                    params[3] == String::class.java &&
                    params[2].name == "androidx.lifecycle.LifecycleOwner" &&
                    candidate.returnType.name == "com.tencent.mobileqq.data.troop.TroopMemberInfo" &&
                    (expected == 4 || params[4] == Long::class.javaPrimitiveType || params[4] == Long::class.javaObjectType)
            } ?: run {
                warnTroopMember("$name troop member signature missing")
                return null
            }
        method.isAccessible = true
        return if (expected == 4) {
            method.invoke(api, groupUin, memberUin, null, TROOP_MEMBER_FROM)
        } else {
            method.invoke(api, groupUin, memberUin, null, TROOP_MEMBER_FROM, TROOP_MEMBER_TIMEOUT_MS)
        }
    }

    private fun warnTroopMember(
        message: String,
        error: Throwable? = null,
    ) {
        if (troopMemberWarned) return
        troopMemberWarned = true
        Log.warn(message, error)
    }

    private fun convertUinUid(
        name: String,
        value: String,
    ): String? {
        if (value.isEmpty()) return null
        val apiType = loadClass(UIN_UID_API) ?: return null
        val api = qRouteApi(apiType) ?: return null
        val method = runCatching { apiType.getMethod(name, String::class.java) }.getOrNull() ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(api, value) as? String }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() && it != "0" }
    }

    fun isUid(value: String?): Boolean = value != null && value.startsWith("u_") && value.length >= 8

    fun selfIds(): Set<String> {
        val ids = LinkedHashSet<String>()
        selfUin().takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        selfUid().takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        return ids
    }

    fun isSelfId(
        value: String,
        ids: Set<String> = selfIds(),
    ): Boolean = value.isNotEmpty() && ids.isNotEmpty() && value in ids

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
    fun hasElements(item: Any): Boolean = read(item, "getElements", "elements", "mElements") is List<*>

    fun invokeNoArg(
        target: Any,
        name: String,
    ): Any? {
        val method = findNoArgMethod(target.javaClass, name) ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    fun invokeSingleArg(
        target: Any,
        name: String,
        paramType: Class<*>,
        arg: Any?,
    ): Boolean {
        val method = findSingleArgMethod(target.javaClass, name, paramType) ?: return false
        return runCatching { method.invoke(target, arg) }.isSuccess
    }

    fun read(
        target: Any,
        vararg names: String,
    ): Any? {
        names.forEach { name ->
            invokeNoArg(target, name)?.let { return it }
            if (!name.startsWith("get") && !name.startsWith("is")) {
                readField(target, name)?.let { return it }
                invokeNoArg(target, "get" + name.replaceFirstChar { it.uppercase() })?.let { return it }
            }
        }
        return null
    }

    fun asLong(value: Any?): Long? =
        when (value) {
            null -> null
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> value.toString().toLongOrNull()
        }

    fun asInt(value: Any?): Int? = asLong(value)?.toInt()

    fun asString(value: Any?): String? =
        when (value) {
            null -> null
            is String -> value
            is Number -> value.toString()
            else -> null
        }

    private fun readField(
        target: Any,
        name: String,
    ): Any? {
        val field = findField(target.javaClass, name) ?: return null
        return runCatching { field.get(target) }.getOrNull()
    }

    private fun typeIdentityKey(type: Class<*>, suffix: String): String =
        "${System.identityHashCode(type)}:${type.name}#$suffix"

    internal fun findField(
        type: Class<*>,
        name: String,
    ): Field? {
        val key = typeIdentityKey(type, name)
        return fieldCache.computeIfAbsent(key) {
            val field =
                generateSequence(type) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .firstOrNull { it.name == name }
                    ?.apply { isAccessible = true }
            FieldHolder(field)
        }.field
    }

    internal fun findNoArgMethod(
        type: Class<*>,
        name: String,
    ): Method? {
        val key = typeIdentityKey(type, name)
        return noArgMethodCache.computeIfAbsent(key) {
            val method =
                generateSequence(type) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                    ?.apply { isAccessible = true }
            MethodHolder(method)
        }.method
    }

    private fun findSingleArgMethod(
        type: Class<*>,
        name: String,
        paramType: Class<*>,
    ): Method? {
        val key = typeIdentityKey(type, "$name(${paramType.name})")
        return singleArgMethodCache.computeIfAbsent(key) {
            val method =
                generateSequence(type) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .firstOrNull {
                        it.name == name &&
                            it.parameterTypes.size == 1 &&
                            it.parameterTypes[0].isAssignableFrom(paramType)
                    }
                    ?.apply { isAccessible = true }
            MethodHolder(method)
        }.method
    }
}
