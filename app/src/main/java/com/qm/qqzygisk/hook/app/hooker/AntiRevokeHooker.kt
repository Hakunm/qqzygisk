package com.qm.qqzygisk.hook.app.hooker

import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.chat.NtMsgAccess
import com.qm.qqzygisk.hook.app.chat.RevokePushParser
import com.qm.qqzygisk.hook.app.chat.RevokeTip
import com.qm.qqzygisk.hook.extension.MemberHookCreator
import com.qm.qqzygisk.hook.extension.MethodCall
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Method
import java.util.Collections

/**
 * Keeps messages that the other side revoked, for both C2C and group chats.
 *
 * Two entry points, both read-only towards existing messages:
 *  - `IQQNTWrapperSession$CppProxy.onMsfPush`: drops a single online recall push
 *    and removes peer group recall entries from InfoSyncPush without dropping
 *    ordinary messages, own recalls or synchronization state in the same batch.
 *  - `MessageFacadeImpl.handleRevokedNotifyAndNotify`: the legacy notify path.
 *
 * Nothing rewrites a MsgRecord or its elements. Earlier builds did that and every
 * one of them crashed when a chat containing a revoked message was opened, so the
 * "已撤回" hint is delivered as a separate local gray tip instead.
 *
 * Self revokes are let through. Online pushes also retain the short local-recall
 * guard; offline entries use their explicit operator, not an unrelated recent action.
 */
object AntiRevokeHooker : BaseHooker() {
    override val key = "anti_revoke"
    override val name = "消息防撤回"
    override val description = "保留本机已收到的对方撤回消息；未收到正文时明确提示。支持私聊和群聊，自己撤回不受影响。"
    override val defaultEnabled = false

    private const val LOCAL_RECALL_WINDOW_MS = 15_000L

    private val hookedMethods = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var lastLocalRecallAt = 0L

    override fun initOnce() {
        retry()
    }

    fun retry() {
        runCatching { hookMsfPush() }
            .onFailure { Log.warn("onMsfPush anti-revoke hook failed", it) }
        runCatching { hookLocalRecall() }
            .onFailure { Log.warn("local recall hook failed", it) }
        runCatching { hookRevokeFacades() }
            .onFailure { Log.warn("facade anti-revoke hook failed", it) }
    }

    private fun enabled(): Boolean = HookSettings.isEnabled(key, defaultEnabled)

    private fun hookMsfPush() {
        NtMsgAccess.sessionProxies.forEach { name ->
            val type = NtMsgAccess.loadClass(name) ?: return@forEach
            type.declaredMethods
                .filter { it.name == "onMsfPush" && it.parameterCount in 2..3 }
                .forEach { method ->
                    hookOnce(method) {
                        before { skipPeerRecallPush(this) }
                    }
                    Log.info("anti-revoke hooked ${type.simpleName}.onMsfPush")
                }
        }
    }

    private fun skipPeerRecallPush(call: MethodCall) {
        if (!enabled()) return
        val cmd = call.args.getOrNull(0) as? String ?: return
        if (cmd != RevokePushParser.CMD_MSG_PUSH && cmd != RevokePushParser.CMD_INFO_SYNC_PUSH) return
        val proto = call.args.getOrNull(1) as? ByteArray ?: return
        val selfUid = NtMsgAccess.selfUid()
        if (cmd == RevokePushParser.CMD_INFO_SYNC_PUSH) {
            val filtered = RevokePushParser.filterInfoSync(proto, selfUid) ?: return
            // Keep the sync envelope and cursors flowing through the kernel. Only
            // the confirmed peer recall messages inside this batch are removed.
            call.args[1] = filtered.payload
            val accountUin = runCatching { NtMsgAccess.selfUin() }.getOrDefault("")
            filtered.recalls.forEach { push ->
                RevokeTip.show(accountUin, push.chatType, push.peerCandidates, push.operatorUid, push.fingerprint, push.messageSeqs)
            }
            Log.info("anti-revoke filtered ${filtered.recalls.size} offline group recall(s), sync preserved")
            return
        }
        val push = RevokePushParser.inspect(cmd, proto, selfUid) ?: return
        if (selfUid.isNotEmpty() && push.operatorUid == selfUid) return
        if (recentLocalRecall()) {
            Log.info("anti-revoke passing own recall ${push.kind}")
            return
        }
        // Read the account now, while the push is being handled: the tip worker may run
        // after an account switch, and a retained mark must belong to the account that
        // actually received this revoke.
        val accountUin = runCatching { NtMsgAccess.selfUin() }.getOrDefault("")
        RevokeTip.show(accountUin, push.chatType, push.peerCandidates, push.operatorUid, push.fingerprint, push.messageSeqs)
        call.result = null
        Log.info("anti-revoke blocked ${push.kind} peers=${push.peerCandidates}")
    }

    /** Records local revokes so the echoing push is not mistaken for a peer revoke. */
    private fun hookLocalRecall() {
        NtMsgAccess.kernelMsgServiceProxies.forEach { name ->
            val type = NtMsgAccess.loadClass(name) ?: return@forEach
            type.declaredMethods
                .filter { it.name == "recallMsg" }
                .forEach { method ->
                    hookOnce(method) {
                        before { lastLocalRecallAt = System.currentTimeMillis() }
                    }
                    Log.info("anti-revoke hooked ${type.simpleName}.recallMsg")
                }
        }
    }

    private fun recentLocalRecall(): Boolean = System.currentTimeMillis() - lastLocalRecallAt < LOCAL_RECALL_WINDOW_MS

    private fun hookRevokeFacades() {
        NtMsgAccess.messageFacades.forEach { name ->
            val type = NtMsgAccess.loadClass(name) ?: return@forEach
            val candidates = type.declaredMethods.filter { isRevokeNotifySignature(it) }
            val targets =
                when {
                    candidates.any { it.name == "handleRevokedNotifyAndNotify" } -> {
                        candidates.filter { it.name == "handleRevokedNotifyAndNotify" }
                    }

                    candidates.size == 1 -> {
                        candidates
                    }

                    else -> {
                        candidates.filter {
                            it.name.contains("Revok", ignoreCase = true) ||
                                it.name.contains("Recall", ignoreCase = true)
                        }
                    }
                }
            targets.forEach { method ->
                hookOnce(method) {
                    before { filterRevokeNotify(this) }
                }
                Log.info("anti-revoke hooked ${type.simpleName}.${method.name}")
            }
        }
    }

    private fun isRevokeNotifySignature(method: Method): Boolean =
        method.returnType == Void.TYPE &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0] == ArrayList::class.java &&
            method.parameterTypes[1] == Boolean::class.javaPrimitiveType

    private fun filterRevokeNotify(call: MethodCall) {
        if (!enabled()) return
        if (recentLocalRecall()) return
        val list = call.args.getOrNull(0) as? MutableList<*> ?: return
        val self = NtMsgAccess.selfIds()
        // Captured while the notify is handled, not when the tip worker runs.
        val accountUin = runCatching { NtMsgAccess.selfUin() }.getOrDefault("")
        val iterator = list.iterator()
        var removed = 0
        while (iterator.hasNext()) {
            val item = iterator.next() ?: continue
            if (!isPeerRevokeInfo(item, self)) continue
            tipFromFacade(item, accountUin)
            iterator.remove()
            removed++
        }
        if (removed > 0) {
            Log.info("anti-revoke swallowed $removed facade revoke(s)")
        }
        if (list.isEmpty()) {
            call.result = null
        }
    }

    private fun isPeerRevokeInfo(
        item: Any,
        self: Set<String>,
    ): Boolean {
        if (!looksLikeRevokeInfo(item)) return false
        val operator = NtMsgAccess.revokeNotifyOperator(item)
        if (operator.isEmpty() || self.isEmpty()) return false
        if (NtMsgAccess.isSelfId(operator, self)) return false
        val chatType = NtMsgAccess.normalizedChatType(item)
        return chatType == NtMsgAccess.CHAT_TYPE_C2C ||
            chatType == NtMsgAccess.CHAT_TYPE_GROUP ||
            chatType < 0
    }

    private fun looksLikeRevokeInfo(item: Any): Boolean {
        if (NtMsgAccess.hasElements(item)) return false
        if (item.javaClass.name.contains("RevokeMsgInfo", ignoreCase = true)) return true
        val seq = NtMsgAccess.read(item, "shmsgseq", "getShmsgseq", "msgUid", "getMsgUid")
        val troop = NtMsgAccess.read(item, "istroop", "getIstroop")
        return NtMsgAccess.revokeNotifyOperator(item).isNotEmpty() && (seq != null || troop != null)
    }

    private fun tipFromFacade(
        item: Any,
        accountUin: String,
    ) {
        val chatType = NtMsgAccess.normalizedChatType(item)
        val raw =
            NtMsgAccess
                .asString(
                    NtMsgAccess.read(item, "getFriendUin", "friendUin", "getPeerUid", "peerUid", "getPeerUin", "peerUin"),
                ).orEmpty()
        if (raw.isEmpty()) return
        // The legacy notify carries uins; a C2C gray tip needs the NT uid.
        val peer =
            if (chatType == NtMsgAccess.CHAT_TYPE_C2C && raw.all(Char::isDigit)) {
                NtMsgAccess.uidFromUin(raw).orEmpty()
            } else {
                raw
            }
        if (peer.isEmpty()) return
        val seq =
            NtMsgAccess.asLong(
                NtMsgAccess.read(item, "getShmsgseq", "shmsgseq", "getMsgSeq", "msgSeq"),
            ) ?: 0L
        val operator = NtMsgAccess.revokeNotifyOperator(item)
        val operatorUid =
            if (NtMsgAccess.isUid(operator)) {
                operator
            } else {
                NtMsgAccess.uidFromUin(operator).orEmpty()
            }
        RevokeTip.show(accountUin, chatType, listOf(peer), operatorUid, "facade:$chatType:$peer:$seq", listOf(seq))
    }

    private fun hookOnce(
        method: Method,
        action: MemberHookCreator.() -> Unit,
    ) {
        val id = method.toGenericString()
        if (!hookedMethods.add(id)) return
        method.isAccessible = true
        method.hook(action)
    }
}
