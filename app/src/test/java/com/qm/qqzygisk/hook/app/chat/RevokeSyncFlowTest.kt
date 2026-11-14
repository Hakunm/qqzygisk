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
