package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.utils.Log
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adds a local gray tip after a peer revoke was swallowed.
 *
 * Mirrors QAuxiliary's NtGrayTipHelper: a brand new local message is inserted via
 * `IKernelMsgService.addLocalJsonGrayTipMsg`. The original MsgRecord is never
 * touched, because editing a record the AIO already bound crashes the renderer.
 */
internal object RevokeTip {
    private const val TIP_C2C = "对方撤回了一条消息（已保留）"
    private const val TIP_GROUP = "有成员撤回了一条消息（已保留）"

    /** NtGrayTipHelper.AIO_AV_C2C_NOTICE / AIO_AV_GROUP_NOTICE */
    private const val BUSI_C2C = 2021L
    private const val BUSI_GROUP = 2022L

    private const val SEEN_LIMIT = 256
    private const val RESULT_TIMEOUT_MS = 2_000L

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "qh-tip").apply { isDaemon = true }
    }
    private val seen = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(SEEN_LIMIT, 0.75f, false) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                    size > SEEN_LIMIT
            },
        ),
    )

    /**
     * Tries [peerCandidates] in order and keeps the first the kernel accepts. A group
     * code can only be guessed from the push, so the callback result is what tells a
     * real conversation from a lookalike varint.
     */
    fun show(chatType: Int, peerCandidates: List<String>, operatorUid: String, fingerprint: String) {
        val peers = peerCandidates.filter { isUsablePeer(chatType, it) }.distinct()
        if (peers.isEmpty()) {
            Log.warn("anti-revoke tip skipped: chat=$chatType no usable peer in $peerCandidates")
            return
        }
        if (!seen.add(fingerprint)) return
        worker.execute {
            val json = grayTipJson(chatType, operatorUid)
            for (peer in peers) {
                val outcome = runCatching { addGrayTip(chatType, peer, json) }
                    .onFailure { Log.warn("anti-revoke gray tip failed peer=$peer", it) }
                    .getOrDefault(Outcome.FAILED)
                if (outcome != Outcome.FAILED) {
                    Log.info("anti-revoke gray tip $outcome chat=$chatType peer=$peer")
                    return@execute
                }
            }
            Log.warn("anti-revoke gray tip found no valid peer in $peers")
        }
    }

    private enum class Outcome { ACCEPTED, UNCONFIRMED, FAILED }

    private fun isUsablePeer(chatType: Int, peerUid: String): Boolean = when (chatType) {
        NtMsgAccess.CHAT_TYPE_C2C -> NtMsgAccess.isUid(peerUid)
        NtMsgAccess.CHAT_TYPE_GROUP -> peerUid.length in 5..12 && peerUid.all(Char::isDigit)
        else -> false
    }

    private fun addGrayTip(chatType: Int, peerUid: String, json: String): Outcome {
        val service = NtMsgAccess.kernelMsgService() ?: error("IKernelMsgService missing")
        val contact = newContact(chatType, peerUid) ?: error("Contact missing")
        val element = newJsonGrayElement(chatType, json) ?: error("JsonGrayElement missing")
        val method = findAddGrayTip(service.javaClass, contact.javaClass, element.javaClass)
            ?: error("addLocalJsonGrayTipMsg missing")
        method.isAccessible = true
        val done = CountDownLatch(1)
        val code = AtomicInteger(0)
        val args = method.parameterTypes.map { param ->
            when {
                param.isInstance(contact) -> contact
                param.isInstance(element) -> element
                param == Boolean::class.javaPrimitiveType -> true
                param.isInterface -> callbackProxy(param) { result ->
                    code.set(result)
                    done.countDown()
                }
                else -> null
            }
        }.toTypedArray()
        method.invoke(service, *args)
        // No callback usually means the kernel swallowed it; treat that as done rather
        // than retrying, so a tip never lands in two conversations.
        if (!done.await(RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return Outcome.UNCONFIRMED
        return if (code.get() == 0) Outcome.ACCEPTED else Outcome.FAILED
    }

    /** addLocalJsonGrayTipMsg(Contact, JsonGrayElement, boolean, boolean, callback) */
    private fun findAddGrayTip(
        serviceType: Class<*>,
        contactType: Class<*>,
        elementType: Class<*>,
    ): Method? = serviceType.methods.firstOrNull { method ->
        method.name == "addLocalJsonGrayTipMsg" &&
            method.parameterTypes.any { it.isAssignableFrom(contactType) } &&
            method.parameterTypes.any { it.isAssignableFrom(elementType) }
    } ?: serviceType.methods.firstOrNull { it.name == "addLocalJsonGrayTipMsg" }

    private fun newContact(chatType: Int, peerUid: String): Any? {
        val type = NtMsgAccess.loadFirst(*NtMsgAccess.contactTypes) ?: return null
        val ctor = type.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType, String::class.java, String::class.java),
            )
        } ?: return null
        ctor.isAccessible = true
        return runCatching { ctor.newInstance(chatType, peerUid, "") }.getOrNull()
    }

    /** JsonGrayElement(long busiId, String json, String recentAbstract, boolean, String?) */
    private fun newJsonGrayElement(chatType: Int, json: String): Any? {
        val type = NtMsgAccess.loadFirst(*NtMsgAccess.jsonGrayElements) ?: return null
        val summary = if (chatType == NtMsgAccess.CHAT_TYPE_GROUP) TIP_GROUP else TIP_C2C
        val busiId = if (chatType == NtMsgAccess.CHAT_TYPE_GROUP) BUSI_GROUP else BUSI_C2C
        return type.declaredConstructors
            .sortedByDescending { it.parameterCount }
            .firstNotNullOfOrNull { ctor -> instantiate(ctor, busiId, json, summary) }
    }

    private fun instantiate(
        ctor: Constructor<*>,
        busiId: Long,
        json: String,
        summary: String,
    ): Any? {
        ctor.isAccessible = true
        var stringSlot = 0
        val args = ctor.parameterTypes.map { param ->
            when (param) {
                Long::class.javaPrimitiveType, java.lang.Long::class.java -> busiId
                Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
                String::class.java -> when (stringSlot++) {
                    0 -> json
                    1 -> summary
                    else -> null
                }
                else -> null
            }
        }.toTypedArray()
        return runCatching { ctor.newInstance(*args) }.getOrNull()
    }

    /**
     * Matches NtGrayTipHelper.NtGrayTipJsonBuilder. Group tips carry a UserItem for
     * the operator the way QAuxiliary builds them; C2C tips are plain text.
     */
    private fun grayTipJson(chatType: Int, operatorUid: String): String {
        val items = JSONArray()
        val operator = operatorUid.takeIf { chatType == NtMsgAccess.CHAT_TYPE_GROUP && NtMsgAccess.isUid(it) }
        val operatorItem = operator?.let { userItem(it) }
        if (operatorItem != null) {
            items.put(operatorItem)
            items.put(textItem("撤回了一条消息（已保留）"))
        } else {
            items.put(textItem(if (chatType == NtMsgAccess.CHAT_TYPE_GROUP) TIP_GROUP else TIP_C2C))
        }
        return JSONObject().put("align", "center").put("items", items).toString()
    }

    private fun textItem(text: String): JSONObject =
        JSONObject().put("txt", text).put("type", "nor")

    /** {"col":"3","jp":uid,"nm":nick,"tp":"0","type":"qq","uid":uid,"uin":uin} */
    private fun userItem(uid: String): JSONObject? {
        val uin = NtMsgAccess.uinFromUid(uid) ?: return null
        return JSONObject()
            .put("col", "3")
            .put("jp", uid)
            .put("nm", uin)
            .put("tp", "0")
            .put("type", "qq")
            .put("uid", uid)
            .put("uin", uin)
    }

    private fun callbackProxy(type: Class<*>, onResult: (Int) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader ?: appClassLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "RevokeTipCallback"
                else -> {
                    // IAddJsonGrayTipMsgCallback.onResult(int result, String uin)
                    onResult((args?.firstNotNullOfOrNull { it as? Number })?.toInt() ?: 0)
                    defaultValue(method.returnType)
                }
            }
        }

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
