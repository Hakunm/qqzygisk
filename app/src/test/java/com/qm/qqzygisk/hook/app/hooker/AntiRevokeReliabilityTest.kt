package com.qm.qqzygisk.hook.app.hooker

import com.qm.qqzygisk.hook.app.chat.NtMsgAccess
import com.qm.qqzygisk.hook.app.chat.RevokePushParser
import com.qm.qqzygisk.hook.extension.HookDeoptimizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防撤回关键可靠性修复的回归测试：
 * - 反射缓存查询不存在字段/方法时不触发 ConcurrentHashMap 空值 NPE；
 * - 成员去重键区分 ClassLoader，避免宿主替换 ClassLoader 后跳过重装；
 * - 已知 selfUid 时绝不因本机刚撤回其他消息而误放行对方撤回；未知 selfUid 时仅按会话精确兜底。
 */
class AntiRevokeReliabilityTest {

    @After
    fun tearDown() {
        AntiRevokeHooker.clearLocalRecallState()
    }

    private class SampleTarget {
        @Suppress("unused")
        val existingField: String = "ok"

        @Suppress("unused")
        fun existingMethod(): String = "ok"
    }

    @Test
    fun `NtMsgAccess reflection cache safely caches missing fields and methods without NPE`() {
        val target = SampleTarget()
        assertNull(NtMsgAccess.findField(SampleTarget::class.java, "nonExistentField"))
        assertNull(NtMsgAccess.findField(SampleTarget::class.java, "nonExistentField"))
        assertNull(NtMsgAccess.findNoArgMethod(SampleTarget::class.java, "nonExistentMethod"))
        assertNull(NtMsgAccess.findNoArgMethod(SampleTarget::class.java, "nonExistentMethod"))
        assertEquals("ok", NtMsgAccess.read(target, "nonExistentGetter", "existingField"))
        assertEquals("ok", NtMsgAccess.invokeNoArg(target, "existingMethod"))
        assertNull(NtMsgAccess.invokeNoArg(target, "nonExistentMethod"))
    }

    @Test
    fun `HookDeoptimizer memberKey distinguishes identical signatures across distinct ClassLoaders`() {
        val bytes = SampleTarget::class.java.classLoader!!
            .getResourceAsStream(SampleTarget::class.java.name.replace('.', '/') + ".class")!!
            .use { it.readBytes() }

        class IsolatedLoader : ClassLoader(null) {
            fun define(name: String, data: ByteArray): Class<*> = defineClass(name, data, 0, data.size)
        }

        val loaderA = IsolatedLoader()
        val loaderB = IsolatedLoader()
        val classA = loaderA.define(SampleTarget::class.java.name, bytes)
        val classB = loaderB.define(SampleTarget::class.java.name, bytes)
        val methodA = classA.getDeclaredMethod("existingMethod")
        val methodB = classB.getDeclaredMethod("existingMethod")

        // 旧实现仅用 toGenericString() 会将两者判为同一方法，导致 ClassLoader 切换后漏挂 hook
        assertEquals(methodA.toGenericString(), methodB.toGenericString())
        assertNotEquals(HookDeoptimizer.memberKey(methodA), HookDeoptimizer.memberKey(methodB))

        // 已标记为 hook 目标的方法绝不会被 deoptimize 冲掉入口
        HookDeoptimizer.markHooked(methodA)
        assertTrue(HookDeoptimizer.isHooked(methodA))
        assertFalse(HookDeoptimizer.isHooked(methodB))
        assertFalse(HookDeoptimizer.deoptimize(methodA))
    }

    @Test
    fun `shouldPassOwnRecall never lets peer recalls through when selfUid is known even after local recall`() {
        val self = "u_self12345"
        val peer = "u_other1234"
        val now = 1_000_000L

        AntiRevokeHooker.recordLocalRecallPeer("123456", now)

        val peerGroupRecall = RevokePushParser.RecallPush(
            kind = RevokePushParser.Kind.GROUP,
            chatType = NtMsgAccess.CHAT_TYPE_GROUP,
            operatorUid = peer,
            peerCandidates = listOf("123456"),
            fingerprint = "fp-1",
            messageSeqs = listOf(10L),
        )
        val ownGroupRecall = peerGroupRecall.copy(operatorUid = self)

        // 即使刚刚在同一个群自己撤回过消息，只要能确定 selfUid，对方撤回也绝不放行
        assertFalse(AntiRevokeHooker.shouldPassOwnRecall(peerGroupRecall, self, now + 500L))
        assertTrue(AntiRevokeHooker.shouldPassOwnRecall(ownGroupRecall, self, now + 500L))

        // runtime selfUid 为空但包内携带 packetSelfUid（如 C2C routing_head.to_uid）时同样严格判定
        val peerC2cWithPacketSelf = RevokePushParser.RecallPush(
            kind = RevokePushParser.Kind.C2C,
            chatType = NtMsgAccess.CHAT_TYPE_C2C,
            operatorUid = peer,
            peerCandidates = listOf(peer),
            fingerprint = "fp-2",
            messageSeqs = listOf(11L),
            packetSelfUid = self,
        )
        AntiRevokeHooker.recordLocalRecallPeer(peer, now)
        assertFalse(AntiRevokeHooker.shouldPassOwnRecall(peerC2cWithPacketSelf, "", now + 500L))
        assertTrue(AntiRevokeHooker.shouldPassOwnRecall(peerC2cWithPacketSelf.copy(operatorUid = self), "", now + 500L))
    }

    @Test
    fun `when selfUid is unavailable local recall window only matches the same conversation`() {
        val now = 2_000_000L
        AntiRevokeHooker.recordLocalRecallPeer("111111", now)

        val sameGroup = RevokePushParser.RecallPush(
            kind = RevokePushParser.Kind.GROUP,
            chatType = NtMsgAccess.CHAT_TYPE_GROUP,
            operatorUid = "u_someone123",
            peerCandidates = listOf("111111"),
            fingerprint = "fp-same",
            messageSeqs = listOf(20L),
        )
        val otherGroup = sameGroup.copy(peerCandidates = listOf("222222"), fingerprint = "fp-other")

        assertTrue(AntiRevokeHooker.shouldPassOwnRecall(sameGroup, "", now + 1_000L))
        assertFalse(AntiRevokeHooker.shouldPassOwnRecall(otherGroup, "", now + 1_000L))
        // 超出时间窗口后不再放行
        assertFalse(AntiRevokeHooker.shouldPassOwnRecall(sameGroup, "", now + 10_000L))
    }
}
