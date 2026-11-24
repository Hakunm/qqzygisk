package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.parasitic.AppParasitics
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
    private const val WITHDREW_C2C = "对方撤回了"
    private const val WITHDREW_GROUP = "有成员撤回了"

    /** Group tips carry the operator's name, so only the verb is left as text. */
    private const val WITHDREW_SELF = "撤回了"
    private const val MSG_WORD = "一条消息"
    private const val KEPT_SUFFIX = "（已保留）"

    private fun suffix(status: RevokeMessageLookup.Status): String =
        when (status) {
            RevokeMessageLookup.Status.PRESENT -> KEPT_SUFFIX
            RevokeMessageLookup.Status.MISSING -> "（本机未找到原消息）"
            RevokeMessageLookup.Status.UNKNOWN -> "（未确认原消息）"
        }

    internal fun recentAbstract(chatType: Int, status: RevokeMessageLookup.Status): String =
        (if (chatType == NtMsgAccess.CHAT_TYPE_GROUP) WITHDREW_GROUP else WITHDREW_C2C) + MSG_WORD + suffix(status)

    /** CommonMsgNavActionHandler: local_jp 93 navigates by msgseq inside the open chat. */
    private const val NAV_LOCAL_JP = 93
    private const val NAV_TEXT = "这条消息"

    /** NtGrayTipHelper.AIO_AV_C2C_NOTICE / AIO_AV_GROUP_NOTICE */
    private const val BUSI_C2C = 2021L
    private const val BUSI_GROUP = 2022L

    private const val SEEN_LIMIT = 256
    private const val RESULT_TIMEOUT_MS = 2_000L
    private const val KERNEL_SERVICE_RETRY_ATTEMPTS = 3
    private const val KERNEL_SERVICE_RETRY_DELAY_MS = 400L

    private val worker =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "qh-tip").apply { isDaemon = true }
        }
    private val seen =
        Collections.synchronizedSet(
            Collections.newSetFromMap(
                object : LinkedHashMap<String, Boolean>(SEEN_LIMIT, 0.75f, false) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > SEEN_LIMIT
                },
            ),
        )

    /**
     * Tries [peerCandidates] in order and keeps the first the kernel accepts. A group
     * code can only be guessed from the push, so the callback result is what tells a
     * real conversation from a lookalike varint.
     *
     * [accountUin] is the logged in account read when the revoke push arrived: the
     * retained mark must never land under an account the worker switched to later.
     *
     * [messageSeqs] holds the seqs of the revoked messages in the push: each gets
     * its own deduplicated tip. Only confirmed ordinary messages receive links and
     * retained marks. An empty list produces an unconfirmed plain tip.
     *
     * Everything runs on the [worker]: the uid→uin mapping once, then the host's
     * per-group display name for each peer candidate before its tips are built. A
     * name that cannot be resolved falls back to the real uin, never a guess.
     */
    fun show(
        accountUin: String,
        chatType: Int,
        peerCandidates: List<String>,
        operatorUid: String,
        fingerprint: String,
        messageSeqs: List<Long>,
    ) {
        val peers = peerCandidates.filter { isUsablePeer(chatType, it) }.distinct()
        if (peers.isEmpty()) {
            Log.warn("anti-revoke tip skipped: chat=$chatType no usable peer in $peerCandidates")
            return
        }
        val seqs = messageSeqs.filter { it > 0 }.distinct()
        val tips: List<Pair<String, Long?>> =
            if (seqs.isEmpty()) {
                listOf(fingerprint to null)
            } else {
                seqs.map { seq -> "$fingerprint:$seq" to seq }
            }
        // The plain key is fingerprint-only, so dedupe stays exactly as it was before
        // links existed.
        val pending = tips.filter { (key, _) -> seen.add(key) }
        if (pending.isEmpty()) return
        worker.execute {
            val resolvedAccount = awaitReadyAccountAndService(accountUin)
            if (resolvedAccount == null) {
                evictSeen(pending)
                Log.warn("anti-revoke gray tip skipped: account or IKernelMsgService unavailable for peers=$peers")
                return@execute
            }
            // uid→uin is chat-independent and resolved once; display names differ per
            // group, so they are resolved inside each peer attempt, once for the whole
            // seq batch. A null uin keeps the generic "有成员撤回了" tip.
            val operatorIds =
                if (chatType == NtMsgAccess.CHAT_TYPE_GROUP && NtMsgAccess.isUid(operatorUid)) {
                    NtMsgAccess.uinFromUid(operatorUid)?.let { operatorUid to it }
                } else {
                    null
                }
            for (peer in peers) {
                val operator =
                    operatorIds?.let { (uid, uin) ->
                        Operator(uid, uin, NtMsgAccess.groupMemberDisplayName(peer, uin) ?: uin)
                    }
                val resolved = processCandidate(
                    pending,
                    { sameAccount(resolvedAccount) },
                    { seq -> RevokeMessageLookup.lookup(resolvedAccount, chatType, peer, seq) },
                    { key, seq, status ->
                        val json = grayTipJson(chatType, operator, seq, status)
                        runCatching { addGrayTip(chatType, peer, json, status) }
                            .onFailure { Log.warn("anti-revoke gray tip failed peer=$peer key=$key", it) }
                            .getOrDefault(Outcome.FAILED)
                    },
                    { seq -> record(resolvedAccount, chatType, peer, listOf(seq)) },
                )
                if (!sameAccount(resolvedAccount) || resolved) return@execute
            }
            evictSeen(pending)
            Log.warn("anti-revoke gray tip found no valid peer in $peers")
        }
    }

    private fun evictSeen(pending: List<Pair<String, Long?>>) {
        pending.forEach { (key, _) -> seen.remove(key) }
    }

    /**
     * 冷启动首包同步到达时账号 uin 或 NT 内核服务可能晚几百毫秒就绪，短暂退避等待避免丢失灰条提示。
     *
     * 若调用方已捕获明确的 [capturedAccount]，始终严格校验未切号；仅在首包到达时捕获为空串才在等待窗口内现场解析。
     */
    private fun awaitReadyAccountAndService(capturedAccount: String): String? {
        for (attempt in 0 until KERNEL_SERVICE_RETRY_ATTEMPTS) {
            val currentUin = runCatching { NtMsgAccess.selfUin() }.getOrDefault("")
            val account =
                if (capturedAccount.isNotEmpty()) {
                    if (currentUin != capturedAccount) return null
                    capturedAccount
                } else {
                    currentUin
                }
            if (account.isNotEmpty() && NtMsgAccess.kernelMsgService() != null) return account
            if (attempt + 1 < KERNEL_SERVICE_RETRY_ATTEMPTS) {
                runCatching { Thread.sleep(KERNEL_SERVICE_RETRY_DELAY_MS) }
            }
        }
        return null
    }

    /**
     * Persists a retained mark for exactly [peerUid] and the independently confirmed
     * message whose tip was accepted. A storage problem must not affect the tip.
     */
    private fun record(
        accountUin: String,
        chatType: Int,
        peerUid: String,
        messageSeqs: List<Long>,
    ) {
        if (messageSeqs.isEmpty()) return
        try {
            val context = AppParasitics.appContext
            if (context == null) {
                Log.warn("已撤回标记未记录: 宿主 Context 不可用 peer=$peerUid")
                return
            }
            RecalledMessages.remember(context, accountUin, chatType, peerUid, messageSeqs)
        } catch (t: Throwable) {
            Log.warn("记录已撤回标记失败 chat=$chatType peer=$peerUid", t)
        }
    }

    internal enum class Outcome { ACCEPTED, UNCONFIRMED, FAILED }

    private fun sameAccount(account: String): Boolean =
        account.isNotEmpty() && runCatching { NtMsgAccess.selfUin() == account }.getOrDefault(false)

    internal fun processCandidate(
        pending: List<Pair<String, Long?>>,
        sameAccount: () -> Boolean,
        lookup: (Long?) -> RevokeMessageLookup.Status,
        insert: (String, Long?, RevokeMessageLookup.Status) -> Outcome,
        mark: (Long) -> Unit,
    ): Boolean {
        var resolved = false
        for ((key, seq) in pending) {
            if (!sameAccount()) return resolved
            val status = lookup(seq)
            if (!sameAccount()) return resolved
            val outcome = insert(key, seq, status)
            if (!sameAccount()) return resolved
            if (outcome != Outcome.FAILED) {
                resolved = true
                if (outcome == Outcome.ACCEPTED && status == RevokeMessageLookup.Status.PRESENT && seq != null && seq > 0) {
                    mark(seq)
                }
            } else if (!resolved) {
                break
            }
        }
        return resolved
    }

    /**
     * The operator identity a group tip renders. [name] is resolved by the host for
     * the specific group, so a remark or card from another group can never leak in.
     */
    internal data class Operator(
        val uid: String,
        val uin: String,
        val name: String,
    )

    private fun isUsablePeer(
        chatType: Int,
        peerUid: String,
    ): Boolean =
        when (chatType) {
            NtMsgAccess.CHAT_TYPE_C2C -> NtMsgAccess.isUid(peerUid)
            NtMsgAccess.CHAT_TYPE_GROUP -> peerUid.length in 5..12 && peerUid.all(Char::isDigit)
            else -> false
        }

    private fun addGrayTip(
        chatType: Int,
        peerUid: String,
        json: String,
        status: RevokeMessageLookup.Status,
    ): Outcome {
        val service = NtMsgAccess.kernelMsgService() ?: error("IKernelMsgService missing")
        val contact = NtMsgAccess.createContact(chatType, peerUid) ?: error("Contact missing")
        val element = newJsonGrayElement(chatType, json, status) ?: error("JsonGrayElement missing")
        val method =
            findAddGrayTip(service.javaClass, contact.javaClass, element.javaClass)
                ?: error("addLocalJsonGrayTipMsg missing")
        method.isAccessible = true
        val done = CountDownLatch(1)
        val code = AtomicInteger(0)
        val args =
            method.parameterTypes
                .map { param ->
                    when {
                        param.isInstance(contact) -> {
                            contact
                        }

                        param.isInstance(element) -> {
                            element
                        }

                        param == Boolean::class.javaPrimitiveType -> {
                            true
                        }

                        param.isInterface -> {
                            callbackProxy(param) { result ->
                                code.set(result)
                                done.countDown()
                            }
                        }

                        else -> {
                            null
                        }
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
    ): Method? =
        serviceType.methods.firstOrNull { method ->
            method.name == "addLocalJsonGrayTipMsg" &&
                method.parameterTypes.any { it.isAssignableFrom(contactType) } &&
                method.parameterTypes.any { it.isAssignableFrom(elementType) }
        } ?: serviceType.methods.firstOrNull { it.name == "addLocalJsonGrayTipMsg" }

    /** JsonGrayElement(long busiId, String json, String recentAbstract, boolean, String?) */
    private fun newJsonGrayElement(
        chatType: Int,
        json: String,
        status: RevokeMessageLookup.Status,
    ): Any? {
        val type = NtMsgAccess.loadFirst(*NtMsgAccess.jsonGrayElements) ?: return null
        val summary = recentAbstract(chatType, status)
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
        val args: Array<Any?> =
            ctor.parameterTypes
                .map<Class<*>, Any?> { param ->
                    when (param) {
                        Long::class.javaPrimitiveType, java.lang.Long::class.java -> {
                            busiId
                        }

                        Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> {
                            false
                        }

                        String::class.java -> {
                            when (stringSlot++) {
                                0 -> json
                                1 -> summary
                                else -> null
                            }
                        }

                        else -> {
                            null
                        }
                    }
                }.toTypedArray()
        return runCatching { ctor.newInstance(*args) }.getOrNull()
    }

    /**
     * Matches NtGrayTipHelper.NtGrayTipJsonBuilder. Group tips carry a UserItem for
     * the operator the way QAuxiliary builds them; C2C tips are plain text.
     *
     * [operator] is the identity resolved on the tip worker: the item shows its
     * per-group [Operator.name] and keeps the uid/uin pair for the profile jump.
     *
     * A confirmed positive [messageSeq] becomes a `local_jp` 93 navigation item.
     * Other states have no message link. The operator UserItem remains a profile link.
     */
    internal fun grayTipJson(
        chatType: Int,
        operator: Operator?,
        messageSeq: Long?,
        status: RevokeMessageLookup.Status,
    ): String {
        val group = chatType == NtMsgAccess.CHAT_TYPE_GROUP
        val seq = messageSeq?.takeIf { it > 0 && status == RevokeMessageLookup.Status.PRESENT }
        val operatorItem = operator?.takeIf { group }?.let { userItem(it) }
        val prefix =
            when {
                operatorItem != null -> WITHDREW_SELF
                group -> WITHDREW_GROUP
                else -> WITHDREW_C2C
            }
        val items = JSONArray()
        if (operatorItem != null) items.put(operatorItem)
        if (seq == null) {
            items.put(textItem(prefix + MSG_WORD + suffix(if (status == RevokeMessageLookup.Status.PRESENT) RevokeMessageLookup.Status.UNKNOWN else status)))
        } else {
            items.put(textItem(prefix))
            items.put(seqItem(seq))
            items.put(textItem(suffix(status)))
        }
        return JSONObject().put("align", "center").put("items", items).toString()
    }

    /**
     * {"col":"3","local_jp":93,"param":{"msgseq":n},"txt":"这条消息","type":"url"}
     * read back by json/a and handed to CommonMsgNavActionHandler.a(JSONObject).
     */
    private fun seqItem(seq: Long): JSONObject =
        JSONObject()
            .put("type", "url")
            .put("txt", NAV_TEXT)
            .put("local_jp", NAV_LOCAL_JP)
            .put("param", JSONObject().put("msgseq", seq))
            .put("col", "3")

    private fun textItem(text: String): JSONObject = JSONObject().put("txt", text).put("type", "nor")

    /** {"col":"3","jp":uid,"nm":name,"tp":"0","type":"qq","uid":uid,"uin":uin} */
    private fun userItem(operator: Operator): JSONObject =
        JSONObject()
            .put("col", "3")
            .put("jp", operator.uid)
            .put("nm", operator.name)
            .put("tp", "0")
            .put("type", "qq")
            .put("uid", operator.uid)
            .put("uin", operator.uin)

    private fun callbackProxy(
        type: Class<*>,
        onResult: (Int) -> Unit,
    ): Any =
        Proxy.newProxyInstance(type.classLoader ?: appClassLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> {
                    proxy === args?.firstOrNull()
                }

                "hashCode" -> {
                    System.identityHashCode(proxy)
                }

                "toString" -> {
                    "RevokeTipCallback"
                }

                else -> {
                    // IAddJsonGrayTipMsgCallback.onResult(int result, String uin)
                    onResult((args?.firstOrNull { it is Number } as? Number)?.toInt() ?: 0)
                    defaultValue(method.returnType)
                }
            }
        }

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
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
