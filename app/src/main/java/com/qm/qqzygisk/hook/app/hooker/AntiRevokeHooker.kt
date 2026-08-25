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
 *  - `IQQNTWrapperSession$CppProxy.onMsfPush`: drops the single recall push
 *    (ContentHead 528/138 for C2C, 732/17 for group) before the kernel applies it.
 *  - `MessageFacadeImpl.handleRevokedNotifyAndNotify`: the legacy notify path.
 *
 * Nothing rewrites a MsgRecord or its elements. Earlier builds did that and every
 * one of them crashed when a chat containing a revoked message was opened, so the
 * "已撤回" hint is delivered as a separate local gray tip instead.
 *
 * Self revokes are let through: either the operator uid equals the current account,
 * or a local `recallMsg` call happened moments ago.
 */
object AntiRevokeHooker : BaseHooker() {
    override val key = "anti_revoke"
    override val name = "消息防撤回"
    override val description = "私聊和群聊都保留对方撤回的消息，并提示已撤回。自己撤回的不受影响。"
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
        val proto = call.args.getOrNull(1) as? ByteArray ?: return
        val selfUid = NtMsgAccess.selfUid()
        val push = RevokePushParser.inspect(cmd, proto, selfUid) ?: return
        if (selfUid.isNotEmpty() && push.operatorUid == selfUid) return
        if (recentLocalRecall()) {
            Log.info("anti-revoke passing own recall ${push.kind}")
            return
        }
        RevokeTip.show(push.chatType, push.peerCandidates, push.operatorUid, push.fingerprint)
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

    private fun recentLocalRecall(): Boolean =
        System.currentTimeMillis() - lastLocalRecallAt < LOCAL_RECALL_WINDOW_MS

    private fun hookRevokeFacades() {
        NtMsgAccess.messageFacades.forEach { name ->
            val type = NtMsgAccess.loadClass(name) ?: return@forEach
            val candidates = type.declaredMethods.filter { isRevokeNotifySignature(it) }
            val targets = when {
                candidates.any { it.name == "handleRevokedNotifyAndNotify" } ->
                    candidates.filter { it.name == "handleRevokedNotifyAndNotify" }
                candidates.size == 1 -> candidates
                else -> candidates.filter {
                    it.name.contains("Revok", ignoreCase = true) ||
                        it.name.contains("Recall", ignoreCase = true)
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
        val iterator = list.iterator()
        var removed = 0
        while (iterator.hasNext()) {
            val item = iterator.next() ?: continue
            if (!isPeerRevokeInfo(item, self)) continue
            tipFromFacade(item)
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

    private fun isPeerRevokeInfo(item: Any, self: Set<String>): Boolean {
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

    private fun tipFromFacade(item: Any) {
        val chatType = NtMsgAccess.normalizedChatType(item)
        val raw = NtMsgAccess.asString(
            NtMsgAccess.read(item, "getFriendUin", "friendUin", "getPeerUid", "peerUid", "getPeerUin", "peerUin"),
        ).orEmpty()
        if (raw.isEmpty()) return
        // The legacy notify carries uins; a C2C gray tip needs the NT uid.
        val peer = if (chatType == NtMsgAccess.CHAT_TYPE_C2C && raw.all(Char::isDigit)) {
            NtMsgAccess.uidFromUin(raw).orEmpty()
        } else {
            raw
        }
        if (peer.isEmpty()) return
        val seq = NtMsgAccess.asLong(
            NtMsgAccess.read(item, "getShmsgseq", "shmsgseq", "getMsgSeq", "msgSeq"),
        ) ?: 0L
        val operator = NtMsgAccess.revokeNotifyOperator(item)
        val operatorUid = if (NtMsgAccess.isUid(operator)) {
            operator
        } else {
            NtMsgAccess.uidFromUin(operator).orEmpty()
        }
        RevokeTip.show(chatType, listOf(peer), operatorUid, "facade:$chatType:$peer:$seq")
    }

    private fun hookOnce(method: Method, action: MemberHookCreator.() -> Unit) {
        val id = method.toGenericString()
        if (!hookedMethods.add(id)) return
        method.isAccessible = true
        method.hook(action)
    }
}
