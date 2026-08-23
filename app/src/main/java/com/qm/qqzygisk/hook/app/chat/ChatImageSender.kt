package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.utils.Log
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.Collections
import java.util.IdentityHashMap

/** Sends local files to the QQNT conversation represented by the latest AIOParam. */
object ChatImageSender {
    private const val AIO_PARAM_CLASS = "com.tencent.aio.data.AIOParam"
    private const val AIO_SESSION_CLASS = "com.tencent.aio.data.AIOSession"
    private const val AIO_CONTACT_CLASS = "com.tencent.aio.data.AIOContact"
    private const val Q_ROUTE_CLASS = "com.tencent.mobileqq.qroute.QRoute"
    private const val MSG_UTIL_API_CLASS = "com.tencent.qqnt.msg.api.IMsgUtilApi"
    private const val MSG_SERVICE_CLASS = "com.tencent.qqnt.msg.api.IMsgService"
    enum class SendType(val subType: Int, val summary: String, val label: String) {
        IMAGE(0, "[图片]", "图片"),
        EMOTICON(1, "[动画表情]", "表情"),
        ;

        companion object {
            fun fromName(raw: String): SendType =
                entries.firstOrNull { it.name == raw } ?: IMAGE
        }
    }

    @Volatile
    private var currentAioParam: WeakReference<Any>? = null

    @Volatile
    private var lastContact: ContactDescriptor? = null

    data class ContactDescriptor(
        val chatType: Int,
        val peerUid: String,
        val guildId: String,
    )

    fun updateAioParam(value: Any?) {
        if (value?.javaClass?.name != AIO_PARAM_CLASS) return
        currentAioParam = WeakReference(value)
        runCatching { lastContact = extractContact(value) }
            .onFailure { Log.warn("缓存聊天会话失败", it) }
    }

    /** 发送本地文件。默认按普通图片，type 可选表情。 */
    fun sendImage(file: File, type: SendType = SendType.IMAGE): Result<Unit> = runCatching {
        check(file.isFile && file.canRead()) { "图片文件不可用" }
        val descriptor = resolveContact()

        val msgUtilType = loadClass(MSG_UTIL_API_CLASS)
        val msgUtil = qRouteApi(msgUtilType)
        val element = createPicElement(
            msgUtilType,
            msgUtil,
            file,
            isGuild = descriptor.chatType == 4,
            subType = type.subType,
        )
        markPicKind(element, type)

        val msgServiceType = loadClass(MSG_SERVICE_CLASS)
        val msgService = qRouteApi(msgServiceType)
        val sendMethod = findSendMethod(msgServiceType, msgService)
        val contact = createContact(sendMethod.parameterTypes[0], descriptor)
        val callback = createCallback(sendMethod.parameterTypes[2], file)

        sendMethod.isAccessible = true
        sendMethod.invoke(msgService, contact, arrayListOf(element), callback)
        Log.info("已提交聊天${type.label}发送: ${file.absolutePath}")
    }

    /** Finds an AIOParam held by a QQ AIO component and makes it the active session. */
    fun updateAioParamFrom(owner: Any): Any? {
        val aioParamType = runCatching { loadClass(AIO_PARAM_CLASS) }.getOrNull() ?: return null
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var current = listOf(owner)
        repeat(3) {
            val next = ArrayList<Any>()
            current.forEach { candidate ->
                if (!visited.add(candidate)) return@forEach
                if (aioParamType.isInstance(candidate)) {
                    updateAioParam(candidate)
                    return candidate
                }
                allFields(candidate.javaClass).forEach { field ->
                    if (Modifier.isStatic(field.modifiers)) return@forEach
                    val fieldType = field.type
                    if (!aioParamType.isAssignableFrom(fieldType) && !isAioContainer(fieldType)) {
                        return@forEach
                    }
                    val value = readField(field, candidate) ?: return@forEach
                    if (aioParamType.isInstance(value)) {
                        updateAioParam(value)
                        return value
                    }
                    next.add(value)
                }
            }
            current = next
        }
        return null
    }

    private fun resolveContact(): ContactDescriptor {
        val live = currentAioParam?.get()
        if (live != null) {
            runCatching { return extractContact(live).also { lastContact = it } }
                .onFailure { Log.warn("读取当前会话失败，将使用上次会话", it) }
        }
        return lastContact ?: error("没有可用的聊天会话，请重新进入聊天页面")
    }

    private fun extractContact(aioParam: Any): ContactDescriptor {
        val aioSession = readFieldByType(aioParam, loadClass(AIO_SESSION_CLASS))
            ?: error("当前 AIOParam 中未找到 AIOSession")
        val aioContact = readFieldByType(aioSession, loadClass(AIO_CONTACT_CLASS))
            ?: error("当前 AIOSession 中未找到 AIOContact")

        val chatTypeFromGetter = invokeNoArg(aioContact, "getChatType") as? Number
        val namedChatField = listOf("d", "e")
            .mapNotNull { name -> findField(aioContact.javaClass, name) }
            .firstOrNull { it.type == Int::class.javaPrimitiveType }
        val chatType = chatTypeFromGetter?.toInt()
            ?: (namedChatField?.let { readField(it, aioContact) } as? Number)?.toInt()
            ?: allFields(aioContact.javaClass)
                .firstOrNull { it.type == Int::class.javaPrimitiveType }
                ?.let { readField(it, aioContact) as? Number }
                ?.toInt()
            ?: error("无法读取当前会话类型")

        val currentLayout = namedChatField?.name == "d"
        val peerUid = (invokeNoArg(aioContact, "getPeerUid") as? String)
            ?: readNamedString(aioContact, if (currentLayout) listOf("e", "f") else listOf("f", "e"))
            ?: error("无法读取当前会话 UID")
        val guildId = (invokeNoArg(aioContact, "getGuildId") as? String)
            ?: readNamedString(aioContact, if (currentLayout) listOf("f", "g") else listOf("g"))
            .orEmpty()

        check(peerUid.isNotBlank()) { "当前会话 UID 为空" }
        return ContactDescriptor(chatType, peerUid, guildId)
    }

    private fun createPicElement(
        apiType: Class<*>,
        api: Any,
        file: File,
        isGuild: Boolean,
        subType: Int,
    ): Any {
        val preferredName = if (isGuild) "createPicElementForGuild" else "createPicElement"
        val methods = (apiType.methods.asSequence() + api.javaClass.methods.asSequence())
            .distinctBy(Method::toGenericString)
        val method = methods.firstOrNull { it.isPicElementFactory(preferredName) }
            ?: (if (isGuild) {
                methods.firstOrNull { it.isPicElementFactory("createPicElement") }
            } else {
                null
            })
            ?: error("QQ 图片消息构造接口不可用")
        method.isAccessible = true
        return method.invoke(api, file.absolutePath, true, subType)
            ?: error("QQ 图片消息构造失败")
    }

    private fun Method.isPicElementFactory(expectedName: String): Boolean {
        val types = parameterTypes
        return name == expectedName && types.size == 3 &&
            types[0] == String::class.java &&
            types[1] == Boolean::class.javaPrimitiveType &&
            types[2] == Int::class.javaPrimitiveType
    }

    private fun findSendMethod(apiType: Class<*>, api: Any): Method {
        return (apiType.methods.asSequence() + api.javaClass.methods.asSequence())
            .distinctBy(Method::toGenericString)
            .firstOrNull { method ->
                val types = method.parameterTypes
                method.name == "sendMsg" && types.size == 3 &&
                    types[0].simpleName == "Contact" &&
                    List::class.java.isAssignableFrom(types[1]) &&
                    types[2].isInterface
            }
            ?: error("QQ 图片发送接口不可用")
    }

    private fun createContact(type: Class<*>, descriptor: ContactDescriptor): Any {
        val constructor = type.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType!!, String::class.java, String::class.java),
            )
        } ?: error("QQ Contact 构造器不可用: ${type.name}")
        constructor.isAccessible = true
        return constructor.newInstance(
            descriptor.chatType,
            descriptor.peerUid,
            descriptor.guildId,
        )
    }

    private fun createCallback(type: Class<*>, file: File): Any {
        return Proxy.newProxyInstance(type.classLoader ?: appClassLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "ImageSendCallback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> {
                    val resultCode = args?.firstOrNull() as? Number
                    if (resultCode != null && resultCode.toInt() != 0) {
                        val detail = args.getOrNull(1)?.toString().orEmpty()
                        Log.error("聊天发送失败: code=$resultCode message=$detail file=${file.absolutePath}")
                    }
                    defaultValue(method.returnType)
                }
            }
        }
    }

    private fun markPicKind(element: Any, type: SendType) {
        val pic = invokeNoArg(element, "getPicElement")
        if (pic == null) {
            Log.warn("图片元素缺少 PicElement，已按 createPicElement(subType=${type.subType}) 提交")
            return
        }
        runCatching {
            invokeOneArg(pic, "setPicSubType", Int::class.javaPrimitiveType!!, type.subType)
        }.onFailure { Log.warn("设置 picSubType 失败", it) }
        runCatching {
            invokeOneArg(pic, "setSummary", String::class.java, type.summary)
        }.onFailure { Log.warn("设置消息外显文案失败", it) }
    }

    private fun qRouteApi(apiType: Class<*>): Any {
        val qRoute = loadClass(Q_ROUTE_CLASS)
        val method = qRoute.declaredMethods.firstOrNull { candidate ->
            Modifier.isStatic(candidate.modifiers) && candidate.name == "api" &&
                candidate.parameterTypes.contentEquals(arrayOf(Class::class.java))
        } ?: error("QRoute.api(Class) 不可用")
        method.isAccessible = true
        return method.invoke(null, apiType) ?: error("QRoute 未返回 ${apiType.name} 实例")
    }

    private fun readFieldByType(target: Any, type: Class<*>): Any? =
        allFields(target.javaClass)
            .firstOrNull { type.isAssignableFrom(it.type) }
            ?.let { readField(it, target) }

    private fun readNamedString(target: Any, names: List<String>): String? =
        names.asSequence()
            .mapNotNull { findField(target.javaClass, it) }
            .filter { it.type == String::class.java }
            .firstNotNullOfOrNull { readField(it, target) as? String }

    private fun invokeNoArg(target: Any, name: String): Any? {
        val method = generateSequence(target.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun invokeOneArg(target: Any, name: String, argType: Class<*>, arg: Any?) {
        val method = generateSequence(target.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterTypes.contentEquals(arrayOf(argType)) }
            ?: error("未找到 ${target.javaClass.name}.$name(${argType.simpleName})")
        method.isAccessible = true
        method.invoke(target, arg)
    }

    private fun findField(type: Class<*>, name: String): Field? =
        allFields(type).firstOrNull { it.name == name }

    private fun readField(field: Field, target: Any): Any? = runCatching {
        field.isAccessible = true
        field.get(target)
    }.getOrNull()

    private fun allFields(type: Class<*>): Sequence<Field> =
        generateSequence(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }

    private fun isAioContainer(type: Class<*>): Boolean =
        type.name.startsWith("com.tencent.aio.") ||
            type.name.startsWith("com.tencent.mobileqq.aio.")

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

    private fun loadClass(name: String): Class<*> = Class.forName(name, false, appClassLoader)
}
