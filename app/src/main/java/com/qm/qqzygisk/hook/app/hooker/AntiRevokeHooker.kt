package com.qm.qqzygisk.hook.app.hooker

import android.content.Context
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.chat.NtMsgAccess
import com.qm.qqzygisk.hook.app.chat.RecalledMessages
import com.qm.qqzygisk.hook.app.chat.RevokePushParser
import com.qm.qqzygisk.hook.app.chat.RevokeTip
import com.qm.qqzygisk.hook.app.data.HostData
import com.qm.qqzygisk.hook.extension.HookDeoptimizer
import com.qm.qqzygisk.hook.extension.MemberHookCreator
import com.qm.qqzygisk.hook.extension.MethodCall
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.DexCallerScanner
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Keeps messages that the other side revoked, for both C2C and group chats.
 *
 * Layered interception with ART caller deoptimization:
 *  - `mqq.app.msghandle.MsgRespHandler.dispatchRespMsg`: upstream MSF response dispatch layer,
 *    before packets enter the NT JNI bridge. Filters peer C2C/group recalls from InfoSyncPush
 *    in-place via `FromServiceMsg.putWupBuffer` and drops single peer online recall pushes.
 *  - `IQQNTWrapperSession$CppProxy.onMsfPush`: NT JNI proxy fallback in case a push arrives
 *    without passing through `MsgRespHandler`.
 *  - `MessageFacadeImpl.handleRevokedNotifyAndNotify`: legacy notify fallback.
 *
 * All known and DEX-discovered callers of `dispatchRespMsg` and `onMsfPush` (including
 * `IQQNTWrapperSession` interface call sites) are deoptimized via [HookDeoptimizer] so
 * background AOT (`speed-profile`) or JIT inlining cannot silently bypass the hooks.
 *
 * Nothing rewrites a MsgRecord or its elements. The "已撤回" hint is delivered as a separate
 * local gray tip and bubble outline instead.
 */
object AntiRevokeHooker : BaseHooker() {
    override val key = "anti_revoke"
    override val name = "消息防撤回"
    override val description = "保留本机已收到的对方撤回消息；未收到正文时明确提示。支持私聊和群聊，自己撤回不受影响。"
    override val defaultEnabled = false

    private const val LOCAL_RECALL_WINDOW_MS = 8_000L
    private const val LOCAL_RECALL_MAX_PEERS = 64
    private const val CALLER_CACHE_KEY = "qq-anti-revoke-callers"

    /** 去重键含 ClassLoader 与声明类身份，宿主切换 ClassLoader 后能对新副本重新安装 hook。 */
    private val hookedMethods = Collections.synchronizedSet(mutableSetOf<String>())

    /** 记录每个 ClassLoader 是否已经调度过 DEX 调用方去优化。 */
    private val callerDeoptScheduledLoaders = Collections.synchronizedSet(mutableSetOf<Int>())

    /** 按会话记录本机主动撤回时间，仅在无法识别 selfUid 时作为兜底，不全局放行其他会话的对方撤回。 */
    private val recentLocalRecallByPeer = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastUnknownPeerLocalRecallAt = 0L

    private val callerScanExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "qh-anti-revoke-deopt").apply { isDaemon = true }
        }

    private val deoptTargets: Set<DexCallerScanner.TargetMethod> =
        setOf(
            DexCallerScanner.TargetMethod("Lmqq/app/msghandle/MsgRespHandler;", "dispatchRespMsg"),
            DexCallerScanner.TargetMethod("Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession;", "onMsfPush"),
            DexCallerScanner.TargetMethod("Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession\$CppProxy;", "onMsfPush"),
            DexCallerScanner.TargetMethod("Lcom/tencent/qqnt/kernelpublic/nativeinterface/IQQNTWrapperSession;", "onMsfPush"),
            DexCallerScanner.TargetMethod("Lcom/tencent/qqnt/kernelpublic/nativeinterface/IQQNTWrapperSession\$CppProxy;", "onMsfPush"),
        )

    override fun initOnce() {
        retry()
    }

    /**
     * 在宿主 Context 可用时（`attachBaseContext` / `Application.onCreate` / `Activity.onCreate`）调用：
     * 补齐尚未安装的 hook、预热已撤回标记存储，并对所有关键调用方执行反内联去优化。
     *
     * 该入口不依赖 Activity 创建，因此 QQ 后台推送拉起时防撤回与去优化也能完整生效。
     */
    fun onHostContextReady(context: Context) {
        val appContext = context.applicationContext ?: context
        runCatching { HookSettings.initialize(appContext) }
        runCatching { RecalledMessages.initialize(appContext) }
        retry()
        ensureCallersDeoptimized(appContext)
    }

    fun retry() {
        runCatching { hookMsgRespHandler() }
            .onFailure { Log.warn("MsgRespHandler anti-revoke hook failed", it) }
        runCatching { hookMsfPush() }
            .onFailure { Log.warn("onMsfPush anti-revoke hook failed", it) }
        runCatching { hookLocalRecall() }
            .onFailure { Log.warn("local recall hook failed", it) }
        runCatching { hookRevokeFacades() }
            .onFailure { Log.warn("facade anti-revoke hook failed", it) }
    }

    private fun enabled(): Boolean = HookSettings.isEnabled(key, defaultEnabled)

    private fun hookMsgRespHandler() {
        NtMsgAccess.msgRespHandlers.forEach { name ->
            val type = NtMsgAccess.loadClass(name) ?: return@forEach
            val methods = type.declaredMethods.filter {
                it.name == "dispatchRespMsg" && it.returnType == Void.TYPE && it.parameterCount >= 1
            }
            if (methods.isEmpty()) return@forEach
            // 先对分发类自身的非目标方法及已知调用方去优化，避免等待异步扫描前就被 AOT/JIT 内联绕过。
            HookDeoptimizer.deoptimizeDeclaredExecutables(type) { it.name == "dispatchRespMsg" }
            NtMsgAccess.knownMsfDispatcherClasses.forEach { dispatcherName ->
                NtMsgAccess.loadClass(dispatcherName)?.let { HookDeoptimizer.deoptimizeDeclaredExecutables(it) }
            }
            methods.forEach { method ->
                if (hookOnce(method) {
                        before { handleDispatchRespMsg(this) }
                    }
                ) {
                    Log.info("anti-revoke hooked ${type.simpleName}.dispatchRespMsg(${method.parameterCount}) loader=${loaderTag(type)}")
                }
            }
        }
    }

    private fun handleDispatchRespMsg(call: MethodCall) {
        runCatching {
            if (!enabled()) return
            val pair = extractServiceMessages(call.args) ?: return
            // 只拦截服务端主动下发的 push（toServiceMsg == null），不碰客户端请求回包。
            if (pair.toServiceMsg != null) return
            val fromServiceMsg = pair.fromServiceMsg
            val cmd = NtMsgAccess.asString(NtMsgAccess.read(fromServiceMsg, "getServiceCmd", "serviceCmd")) ?: return
            if (cmd != RevokePushParser.CMD_MSG_PUSH && cmd != RevokePushParser.CMD_INFO_SYNC_PUSH) return
            val rawWup = NtMsgAccess.read(fromServiceMsg, "getWupBuffer", "wupBuffer") as? ByteArray ?: return
            val proto = RevokePushParser.unwrapWupBuffer(rawWup) ?: return
            if (proto.isEmpty()) return

            val selfUid = NtMsgAccess.selfUid()
            if (cmd == RevokePushParser.CMD_INFO_SYNC_PUSH) {
                val filtered = RevokePushParser.filterInfoSync(proto, selfUid) ?: return
                val updatedWup = RevokePushParser.rewrapWupBuffer(rawWup, filtered.payload)
                if (!NtMsgAccess.invokeSingleArg(fromServiceMsg, "putWupBuffer", ByteArray::class.java, updatedWup)) {
                    Log.warn("anti-revoke failed to write filtered InfoSyncPush back to FromServiceMsg")
                    return
                }
                dispatchTips(filtered.recalls)
                Log.info("anti-revoke filtered ${filtered.recalls.size} offline recall(s) at MsgRespHandler")
                return
            }

            val push = RevokePushParser.inspect(cmd, proto, selfUid) ?: return
            if (shouldPassOwnRecall(push, selfUid)) {
                Log.info("anti-revoke passing own recall ${push.kind} at MsgRespHandler")
                return
            }
            dispatchTips(listOf(push))
            call.result = null
            Log.info("anti-revoke blocked ${push.kind} at MsgRespHandler peers=${push.peerCandidates}")
        }.onFailure { error ->
            Log.warn("anti-revoke MsgRespHandler callback error", error)
        }
    }

    private data class ServiceMessagePair(
        val toServiceMsg: Any?,
        val fromServiceMsg: Any,
    )

    private fun extractServiceMessages(args: Array<Any?>): ServiceMessagePair? {
        if (args.isEmpty()) return null
        if (args.size == 1) {
            val wrapper = args[0] ?: return null
            val from = NtMsgAccess.read(wrapper, "fromServiceMsg", "getFromServiceMsg") ?: return null
            val to = NtMsgAccess.read(wrapper, "toServiceMsg", "getToServiceMsg")
            return ServiceMessagePair(to, from)
        }
        var to: Any? = null
        var from: Any? = null
        for (arg in args) {
            if (arg == null) continue
            val name = arg.javaClass.name
            if (from == null && name.endsWith("FromServiceMsg")) from = arg
            else if (to == null && name.endsWith("ToServiceMsg")) to = arg
        }
        return from?.let { ServiceMessagePair(to, it) }
    }

    private fun hookMsfPush() {
        NtMsgAccess.sessionProxies.forEach { name ->
            val type = NtMsgAccess.loadClass(name) ?: return@forEach
            type.declaredMethods
                .filter { it.name == "onMsfPush" && it.returnType == Void.TYPE && it.parameterCount in 2..3 }
                .forEach { method ->
                    if (hookOnce(method) {
                            before { skipPeerRecallPush(this) }
                        }
                    ) {
                        Log.info("anti-revoke hooked ${type.simpleName}.onMsfPush loader=${loaderTag(type)}")
                    }
                }
        }
    }

    private fun skipPeerRecallPush(call: MethodCall) {
        runCatching {
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
                dispatchTips(filtered.recalls)
                Log.info("anti-revoke filtered ${filtered.recalls.size} offline recall(s) at onMsfPush, sync preserved")
                return
            }
            val push = RevokePushParser.inspect(cmd, proto, selfUid) ?: return
            if (shouldPassOwnRecall(push, selfUid)) {
                Log.info("anti-revoke passing own recall ${push.kind} at onMsfPush")
                return
            }
            dispatchTips(listOf(push))
            call.result = null
            Log.info("anti-revoke blocked ${push.kind} at onMsfPush peers=${push.peerCandidates}")
        }.onFailure { error ->
            Log.warn("anti-revoke onMsfPush callback error", error)
        }
    }

    private fun dispatchTips(recalls: List<RevokePushParser.RecallPush>) {
        // Read the account now, while the push is being handled: the tip worker may run
        // after an account switch, and a retained mark must belong to the account that
        // actually received this revoke.
        val accountUin = runCatching { NtMsgAccess.selfUin() }.getOrDefault("")
        recalls.forEach { push ->
            RevokeTip.show(accountUin, push.chatType, push.peerCandidates, push.operatorUid, push.fingerprint, push.messageSeqs)
        }
    }

    /**
     * 判定该撤回是否属于本机自己撤回：
     * - 只要能确定本机 UID（运行时或包内 `routing_head.to_uid`），严格以 `operatorUid == effectiveSelfUid` 为准，
     *   绝不因为刚撤回过其他消息而误放行对方撤回；
     * - 仅当完全无法拿到本机 UID 时，才回退到按会话匹配的短时间窗兜底。
     */
    internal fun shouldPassOwnRecall(
        push: RevokePushParser.RecallPush,
        runtimeSelfUid: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val effectiveSelf = RevokePushParser.resolveSelfUid(runtimeSelfUid, push.packetSelfUid)
        if (effectiveSelf.isNotEmpty()) {
            return push.operatorUid == effectiveSelf
        }
        return isRecentLocalRecallForPeers(push.peerCandidates, nowMs)
    }

    internal fun recordLocalRecallPeer(peerUid: String?, nowMs: Long = System.currentTimeMillis()) {
        if (!peerUid.isNullOrEmpty()) {
            if (recentLocalRecallByPeer.size >= LOCAL_RECALL_MAX_PEERS) {
                recentLocalRecallByPeer.entries.removeIf { (_, ts) -> nowMs - ts >= LOCAL_RECALL_WINDOW_MS }
            }
            recentLocalRecallByPeer[peerUid] = nowMs
        } else {
            lastUnknownPeerLocalRecallAt = nowMs
        }
    }

    internal fun clearLocalRecallState() {
        recentLocalRecallByPeer.clear()
        lastUnknownPeerLocalRecallAt = 0L
    }

    private fun isRecentLocalRecallForPeers(
        peers: Collection<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (nowMs - lastUnknownPeerLocalRecallAt in 0 until LOCAL_RECALL_WINDOW_MS) return true
        for (peer in peers) {
            val ts = recentLocalRecallByPeer[peer] ?: continue
            if (nowMs - ts in 0 until LOCAL_RECALL_WINDOW_MS) return true
        }
        return false
    }

    /** Records local revokes per conversation so an echo push is recognized even if selfUid is not ready yet. */
    private fun hookLocalRecall() {
        NtMsgAccess.kernelMsgServiceProxies.forEach { name ->
            val type = NtMsgAccess.loadClass(name) ?: return@forEach
            type.declaredMethods
                .filter { it.name == "recallMsg" }
                .forEach { method ->
                    if (hookOnce(method) {
                            before {
                                runCatching {
                                    val contact = args.firstOrNull()
                                    val peer = contact?.let {
                                        NtMsgAccess.asString(NtMsgAccess.read(it, "getPeerUid", "peerUid"))
                                    }
                                    recordLocalRecallPeer(peer)
                                }
                            }
                        }
                    ) {
                        Log.info("anti-revoke hooked ${type.simpleName}.recallMsg loader=${loaderTag(type)}")
                    }
                }
        }
    }

    private fun ensureCallersDeoptimized(context: Context) {
        val loader = HostData.appClassLoader
        val loaderId = System.identityHashCode(loader)
        if (!callerDeoptScheduledLoaders.add(loaderId)) return

        // 同版本已缓存调用方时同步执行去优化（< 1ms），在首条 MSF push 到达前即关闭内联绕过。
        val cached = DexCallerScanner.loadCachedCallers(context, CALLER_CACHE_KEY, deoptTargets)
        if (cached != null) {
            applyCallerDeoptimization(cached, loader, source = "cache")
            return
        }

        // 版本首次启动走后台扫描，不阻塞宿主主线程启动，扫完后立即去优化并落盘缓存。
        callerScanExecutor.execute {
            runCatching {
                val discovered = DexCallerScanner.discoverCallers(context, loader, CALLER_CACHE_KEY, deoptTargets)
                applyCallerDeoptimization(discovered, loader, source = "scan")
            }.onFailure { error ->
                callerDeoptScheduledLoaders.remove(loaderId)
                Log.warn("anti-revoke caller deoptimization scan failed", error)
            }
        }
    }

    private fun applyCallerDeoptimization(
        callers: List<DexCallerScanner.CallerMethod>,
        loader: ClassLoader,
        source: String,
    ) {
        var applied = 0
        for (caller in callers) {
            val executable = caller.resolve(loader) ?: continue
            if (HookDeoptimizer.deoptimize(executable)) {
                applied++
            }
        }
        Log.info("anti-revoke caller deopt($source): total=${callers.size} applied=$applied loader=@${System.identityHashCode(loader)}")
    }

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
                if (hookOnce(method) {
                        before { filterRevokeNotify(this) }
                    }
                ) {
                    Log.info("anti-revoke hooked ${type.simpleName}.${method.name} loader=${loaderTag(type)}")
                }
            }
        }
    }

    private fun isRevokeNotifySignature(method: Method): Boolean =
        method.returnType == Void.TYPE &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0] == ArrayList::class.java &&
            method.parameterTypes[1] == Boolean::class.javaPrimitiveType

    private fun filterRevokeNotify(call: MethodCall) {
        runCatching {
            if (!enabled()) return
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
        }.onFailure { error ->
            Log.warn("anti-revoke facade callback error", error)
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
    ): Boolean {
        val id = HookDeoptimizer.memberKey(method)
        if (!hookedMethods.add(id)) return false
        method.isAccessible = true
        return try {
            method.hook(action)
            HookDeoptimizer.markHooked(method)
            true
        } catch (th: Throwable) {
            hookedMethods.remove(id)
            throw th
        }
    }

    private fun loaderTag(type: Class<*>): String =
        "${type.classLoader?.javaClass?.simpleName ?: "boot"}@${System.identityHashCode(type.classLoader)}"
}
