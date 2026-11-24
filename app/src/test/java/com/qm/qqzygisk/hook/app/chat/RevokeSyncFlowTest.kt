package com.qm.qqzygisk.hook.app.chat

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class RevokeSyncFlowTest {
    private val self = "u_self12345"
    private val other = "u_other1234"

    @Test
    fun `online recall and mixed offline sync preserve all unrelated wire data`() {
        val otherRecall = recall(other, 123456, 42)
        val ownRecall = recall(self, 123456, 43)
        val online = RevokePushParser.inspect(RevokePushParser.CMD_MSG_PUSH, bytes(1, otherRecall), self)!!
        assertEquals(RevokePushParser.Kind.GROUP, online.kind)
        assertEquals(other, online.operatorUid)
        assertEquals(listOf(42L), online.messageSeqs)

        val normal = cat(bytes(2, cat(number(1, 166), number(2, 0))), bytes(3, bytes(2, "hello".toByteArray())))
        val bodyPrefix = cat(bytes(2, "123456".toByteArray()), fixed32(25), bytes(19, byteArrayOf(0x80.toByte())))
        val bodySuffix = cat(number(17, 9), fixed64(26))
        fun body(vararg messages: ByteArray) = cat(bodyPrefix, *messages.map { bytes(8, it) }.toTypedArray(), bodySuffix)
        val untouchedBody = body(normal, ownRecall)
        val mixedBody = body(normal, otherRecall, ownRecall, otherRecall)
        val expectedBody = body(normal, ownRecall)
        val privateBody = cat(bytes(2, other.toByteArray()), bytes(8, otherRecall))
        val mismatchedBody = cat(bytes(2, "999999".toByteArray()), bytes(8, otherRecall))
        val syncPrefix = cat(bytes(3, number(1, 101)), bytes(12, byteArrayOf(1, 2, 3)))
        val syncSuffix = cat(bytes(5, number(1, 102)), fixed32(30))
        fun sync(body: ByteArray) = cat(syncPrefix, bytes(4, untouchedBody), bytes(4, privateBody), bytes(4, mismatchedBody), bytes(4, body), syncSuffix)
        val outerPrefix = cat(number(1, 77), bytes(7, byteArrayOf(0x80.toByte())), fixed64(22))
        val outerSuffix = cat(bytes(9, byteArrayOf(3, 2, 1)), number(10, 8))
        fun packet(body: ByteArray) = cat(outerPrefix, bytes(8, sync(body)), outerSuffix)
        val original = packet(mixedBody)
        val filtered = RevokePushParser.filterInfoSync(original, self)!!
        assertArrayEquals(packet(expectedBody), filtered.payload)
        assertEquals(2, filtered.recalls.size)
        assertEquals(listOf(other, other), filtered.recalls.map { it.operatorUid })
        assertEquals(listOf(listOf(42L), listOf(42L)), filtered.recalls.map { it.messageSeqs })
        assertEquals(listOf("123456"), filtered.recalls.first().peerCandidates)
        assertEquals(online.fingerprint, filtered.recalls.first().fingerprint)
        assertNull(RevokePushParser.filterInfoSync(filtered.payload, self))
        assertNull(RevokePushParser.filterInfoSync(original, ""))
        assertNull(RevokePushParser.filterInfoSync(original, "unknown"))
        assertNull(RevokePushParser.filterInfoSync(original.copyOf(original.size - 1), self))
        assertNull(RevokePushParser.filterInfoSync(cat(original, byteArrayOf(0x80.toByte())), self))
        assertNull(RevokePushParser.filterInfoSync(cat(original, byteArrayOf(0x0b)), self))
    }

    @Test
    fun `a batch containing only recalls retains its empty body and sync cursors`() {
        val bodyPrefix = bytes(2, "123456".toByteArray())
        val cursorStart = bytes(3, number(1, 100))
        val cursorEnd = bytes(5, number(1, 200))
        fun packet(body: ByteArray) = bytes(8, cat(cursorStart, bytes(4, body), cursorEnd))
        val filtered = RevokePushParser.filterInfoSync(
            packet(cat(bodyPrefix, bytes(8, recall(other, 123456, 12)))), self,
        )!!
        assertArrayEquals(packet(bodyPrefix), filtered.payload)
        assertEquals(listOf(12L), filtered.recalls.single().messageSeqs)
        assertNull(RevokePushParser.filterInfoSync(filtered.payload, self))
    }

    @Test
    fun `offline InfoSyncPush filters peer C2C recalls and works with packet selfUid fallback`() {
        val peerC2c = c2cRecall(fromUid = other, toUid = self, seq = 501, routingToUid = self)
        val ownC2c = c2cRecall(fromUid = self, toUid = other, seq = 502, routingToUid = self)
        val normal = cat(bytes(2, cat(number(1, 166), number(2, 0))), bytes(3, bytes(2, "hi".toByteArray())))
        val bodyPrefix = cat(bytes(2, other.toByteArray()), fixed32(25))
        fun c2cBody(vararg messages: ByteArray) = cat(bodyPrefix, *messages.map { bytes(8, it) }.toTypedArray())
        val mismatchedC2cBody = cat(bytes(2, "u_another99".toByteArray()), bytes(8, peerC2c))
        val syncPrefix = bytes(3, number(1, 300))
        val syncSuffix = bytes(5, number(1, 301))
        fun packet(body: ByteArray) = bytes(8, cat(syncPrefix, bytes(4, mismatchedC2cBody), bytes(4, body), syncSuffix))

        val original = packet(c2cBody(normal, peerC2c, ownC2c))
        val expected = packet(c2cBody(normal, ownC2c))

        // 正常运行期（带 runtime selfUid）过滤对方私聊离线撤回，保留自己撤回与普通消息
        val filtered = RevokePushParser.filterInfoSync(original, self)!!
        assertArrayEquals(expected, filtered.payload)
        assertEquals(1, filtered.recalls.size)
        val recall = filtered.recalls.single()
        assertEquals(RevokePushParser.Kind.C2C, recall.kind)
        assertEquals(NtMsgAccess.CHAT_TYPE_C2C, recall.chatType)
        assertEquals(other, recall.operatorUid)
        assertEquals(listOf(other), recall.peerCandidates)
        assertEquals(listOf(501L), recall.messageSeqs)

        // 冷启动初期 runtime selfUid 为空时，也能借助包内 routing_head.to_uid 精准拦截对方撤回并放行自己撤回
        val filteredWithoutRuntimeSelf = RevokePushParser.filterInfoSync(original, "")!!
        assertArrayEquals(expected, filteredWithoutRuntimeSelf.payload)
        assertEquals(listOf(501L), filteredWithoutRuntimeSelf.recalls.single().messageSeqs)

        // 过滤后的载荷不再重复命中
        assertNull(RevokePushParser.filterInfoSync(filtered.payload, self))
    }

    @Test
    fun `unwrapped group recall payload without 7-byte prefix is recognized while non-recall op is rejected`() {
        val info = cat(bytes(1, other.toByteArray()), bytes(3, number(1, 88)))
        val unwrappedBody = cat(number(1, 7), number(4, 234567), bytes(11, info))
        val unwrappedMsg = cat(
            bytes(1, bytes(6, self.toByteArray())),
            bytes(2, cat(number(1, 732), number(2, 17))),
            bytes(3, bytes(2, unwrappedBody)),
        )
        val online = RevokePushParser.inspect(RevokePushParser.CMD_MSG_PUSH, bytes(1, unwrappedMsg), self)!!
        assertEquals(RevokePushParser.Kind.GROUP, online.kind)
        assertEquals(other, online.operatorUid)
        assertEquals("234567", online.peerCandidates.first())
        assertEquals(listOf(88L), online.messageSeqs)
        assertEquals(self, online.packetSelfUid)

        // 离线 InfoSyncPush 中的无前缀群撤回同样能被识别与过滤
        val syncPacket = bytes(8, bytes(4, cat(bytes(2, "234567".toByteArray()), bytes(8, unwrappedMsg))))
        val filtered = RevokePushParser.filterInfoSync(syncPacket, "")!!
        assertEquals(listOf(88L), filtered.recalls.single().messageSeqs)

        // op_type != 7 的 732/17 非撤回通知必须拒绝
        val nonRecallBody = cat(number(1, 3), number(4, 234567), bytes(11, info))
        val nonRecallMsg = cat(
            bytes(2, cat(number(1, 732), number(2, 17))),
            bytes(3, bytes(2, nonRecallBody)),
        )
        assertNull(RevokePushParser.inspect(RevokePushParser.CMD_MSG_PUSH, bytes(1, nonRecallMsg), self))
    }

    @Test
    fun `printable ASCII nested protobuf submessage is never shadowed as plain string`() {
        // 构造一个字段字节全部落入可打印 ASCII（32..126）区间的合法嵌套子消息：
        // routing_head 含 field 6 = "u_self12345" -> tag=0x32('2'), len=11? 改为 32 字节长使 tag/len/内容全为可打印 ASCII
        val asciiPadUid = "u_self_printable_uid_01234567890" // 32 bytes (0x20 = ' ')
        val routingPayload = bytes(6, asciiPadUid.toByteArray()) // [0x32, 0x20, ...ASCII...] -> 100% printable ASCII!
        assertTrue(
            "precondition: routing bytes must all be printable ASCII",
            routingPayload.all { it.toInt() in 32..126 },
        )

        val peerUid = "u_peer_printable_uid_01234567890" // 32 bytes (0x20 = ' ')
        // MsgInfo: field 1 (0x0a) 非可打印，但 routing_head 全为可打印 ASCII；同时测试 RoutingHead.group (field 8 = 0x42)
        // Group { group_code = 48 } -> [0x08, 0x30]；为了构造 100% 可打印 ASCII 子消息，
        // 令外层 routingPayload 为全可打印 ASCII 并验证 routing.string(6) 被准确提取为 packetSelfUid。
        val msg = cat(
            bytes(1, routingPayload),
            bytes(2, cat(number(1, 528), number(2, 138))),
            bytes(3, bytes(2, bytes(1, cat(bytes(1, peerUid.toByteArray()), bytes(2, asciiPadUid.toByteArray()), number(20, 777))))),
        )
        val parsed = RevokePushParser.inspect(RevokePushParser.CMD_MSG_PUSH, bytes(1, msg), "")!!
        assertEquals(asciiPadUid, parsed.packetSelfUid)
        assertEquals(peerUid, parsed.operatorUid)
        assertEquals(listOf(peerUid), parsed.peerCandidates)
        assertEquals(listOf(777L), parsed.messageSeqs)
    }

    @Test
    fun `WUP 4-byte length prefix unwrap and rewrap preserve framing contract`() {
        val payload = byteArrayOf(0x0a, 0x03, 0x01, 0x02, 0x03)
        val total = payload.size + 4
        val framed = byteArrayOf(
            (total ushr 24).toByte(),
            (total ushr 16).toByte(),
            (total ushr 8).toByte(),
            total.toByte(),
        ) + payload

        val unwrapped = RevokePushParser.unwrapWupBuffer(framed)!!
        assertArrayEquals(payload, unwrapped)

        val shorter = byteArrayOf(0x0a, 0x01, 0x7f)
        val rewrapped = RevokePushParser.rewrapWupBuffer(framed, shorter)
        assertEquals(shorter.size + 4, rewrapped.size)
        assertEquals(shorter.size + 4, rewrapped[3].toInt() and 0xff)
        assertArrayEquals(shorter, rewrapped.copyOfRange(4, rewrapped.size))

        // 无长度前缀的裸载荷 unwrap 与 rewrap 均保持裸载荷形态
        assertArrayEquals(payload, RevokePushParser.unwrapWupBuffer(payload))
        assertArrayEquals(shorter, RevokePushParser.rewrapWupBuffer(payload, shorter))
        assertNull(RevokePushParser.unwrapWupBuffer(null))
        assertNull(RevokePushParser.unwrapWupBuffer(ByteArray(0)))
    }

    private fun c2cRecall(fromUid: String, toUid: String, seq: Long, routingToUid: String? = null): ByteArray {
        val info = cat(bytes(1, fromUid.toByteArray()), bytes(2, toUid.toByteArray()), number(20, seq))
        val routing = routingToUid?.let { bytes(1, bytes(6, it.toByteArray())) } ?: ByteArray(0)
        return cat(
            routing,
            bytes(2, cat(number(1, 528), number(2, 138))),
            bytes(3, bytes(2, bytes(1, info))),
        )
    }

    private fun recall(operator: String, group: Long, seq: Long): ByteArray {
        val info = cat(bytes(1, operator.toByteArray()), bytes(3, number(1, seq)))
        val payload = cat(ByteArray(7), number(1, 7), number(4, group), bytes(11, info))
        return cat(bytes(2, cat(number(1, 732), number(2, 17))), bytes(3, bytes(2, payload)))
    }

    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var n = value
        while (n >= 128) {
            out.write(((n and 127) or 128).toInt())
            n = n ushr 7
        }
        out.write(n.toInt())
        return out.toByteArray()
    }

    private fun number(field: Int, value: Long) = cat(varint((field * 8).toLong()), varint(value))
    private fun bytes(field: Int, value: ByteArray) = cat(varint((field * 8 + 2).toLong()), varint(value.size.toLong()), value)
    private fun fixed32(field: Int) = cat(varint((field * 8 + 5).toLong()), byteArrayOf(1, 2, 3, 4))
    private fun fixed64(field: Int) = cat(varint((field * 8 + 1).toLong()), byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
    private fun cat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        parts.forEach { out.write(it) }
    }.toByteArray()
}
