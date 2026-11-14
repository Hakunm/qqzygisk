package com.qm.qqzygisk.hook.app.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class RevokeNavigationFlowTest {
    @Test
    fun recallPushKeepsExactMessageTargetsInNativeNavigationLinks() {
        val self = "u_self_account"
        val peer = "u_peer_account"
        val privateInfos = message(1, text(1, peer) + text(2, self) + number(3, 77) + number(4, 99001) + number(20, 70101)) +
            message(1, text(1, peer) + text(2, self) + number(20, 70102)) +
            message(1, text(1, peer) + text(2, self) + number(20, 70101)) +
            message(1, text(1, "u_different_peer") + text(2, self) + number(20, 88888))
        val privatePush = push(528, 138, privateInfos)
        val original = privatePush.copyOf()
        val c2c = requireNotNull(RevokePushParser.inspect(RevokePushParser.CMD_MSG_PUSH, privatePush, self))
        assertEquals(listOf(70101L, 70102L), c2c.messageSeqs)
        assertEquals(peer, c2c.peerCandidates.first())
        assertArrayEquals("Parsing must not rewrite the intercepted push", original, privatePush)
        assertNavigation(c2c, 70101)
        assertNavigation(c2c, 70102)

        val groupContent = number(1, 7) + number(4, 123456789) + number(37, 999999) + message(
            11,
            text(1, peer) + message(3, number(1, 80101) + number(2, 1700000000)) +
                message(3, number(1, 80102)) + message(3, number(1, 80101)) + message(3, number(1, 0)),
        )
        val group = requireNotNull(RevokePushParser.inspect(
            RevokePushParser.CMD_MSG_PUSH, push(732, 17, ByteArray(7) + groupContent), self,
        ))
        assertEquals(NtMsgAccess.CHAT_TYPE_GROUP, group.chatType)
        assertEquals("123456789", group.peerCandidates.first())
        assertEquals(listOf(80101L, 80102L), group.messageSeqs)
        group.messageSeqs.forEach { assertNavigation(group, it) }

        // Client sequence / msgUid / outer group sequence must never become message navigation targets.
        val noPrivateTarget = requireNotNull(RevokePushParser.inspect(
            RevokePushParser.CMD_MSG_PUSH,
            push(528, 138, message(1, text(1, peer) + text(2, self) + number(3, 123) + number(4, 456))), self,
        ))
        assertTrue(noPrivateTarget.messageSeqs.isEmpty())
        assertPlainTip(noPrivateTarget.chatType, null)
        assertPlainTip(noPrivateTarget.chatType, 0)
        assertPlainTip(noPrivateTarget.chatType, -1)
        val noGroupTarget = requireNotNull(RevokePushParser.inspect(
            RevokePushParser.CMD_MSG_PUSH,
            push(732, 17, ByteArray(7) + number(1, 7) + number(4, 123456789) +
                number(37, 77777) + message(11, text(1, peer) + message(3, number(2, 1700000000)))), self,
        ))
        assertTrue(noGroupTarget.messageSeqs.isEmpty())
        assertPlainTip(noGroupTarget.chatType, null)
        assertNull(RevokePushParser.inspect("unrelated.push", privatePush, self))
    }

    private fun assertNavigation(recall: RevokePushParser.RecallPush, sequence: Long) {
        val root = JSONObject(RevokeTip.grayTipJson(recall.chatType, null, sequence, RevokeMessageLookup.Status.PRESENT))
        val items = root.getJSONArray("items")
        val links = (0 until items.length()).map { items.getJSONObject(it) }.filter { it.optString("type") == "url" }
        assertEquals(1, links.size)
        val link = links.single()
        assertEquals(93, link.getInt("local_jp"))
        assertEquals(sequence, link.getJSONObject("param").getLong("msgseq"))
        assertFalse("Must not turn local navigation into a network/scheme action", link.has("jp"))
    }

    private fun assertPlainTip(chatType: Int, sequence: Long?) {
        val items = JSONObject(RevokeTip.grayTipJson(chatType, null, sequence, RevokeMessageLookup.Status.UNKNOWN)).getJSONArray("items")
        assertFalse((0 until items.length()).any { items.getJSONObject(it).optString("type") == "url" })
    }

    interface Callback {
        fun onResult(code: Int, message: String, records: ArrayList<Record>)
    }

    class Contact
    class Element(val elementType: Int)
    class Record(val chatType: Int, val peerUid: String, val msgSeq: Long, val elements: ArrayList<Element>)
    class Service(private val responses: Map<Long, Pair<Int, ArrayList<Record>>?>) {
        fun getSingleMsg(contact: Contact, seq: Long, callback: Callback) {
            responses[seq]?.let { (code, records) -> callback.onResult(code, "", records) }
        }
    }

    @Test
    fun lookupAndAcceptedTipsOnlyMarkRealMessagesForBothChatTypes() {
        for ((chatType, peer) in listOf(NtMsgAccess.CHAT_TYPE_C2C to "u_peer_account", NtMsgAccess.CHAT_TYPE_GROUP to "123456789")) {
            val account = "12345"
            fun record(seq: Long, type: Int) = Record(chatType, peer, seq, arrayListOf(Element(type)))
            val service = Service(mapOf(
                1L to (0 to arrayListOf(record(1, 1))),
                2L to (0 to arrayListOf()),
                3L to (0 to arrayListOf(record(3, 8))),
                4L to (1 to arrayListOf(record(4, 1))),
                5L to null,
                6L to (0 to arrayListOf(record(99, 1))),
                7L to (0 to arrayListOf(record(7, 1))),
            ))
            val marks = mutableListOf<Long>()
            val statuses = mutableMapOf<Long, RevokeMessageLookup.Status>()
            val pending = (1L..7L).map { "$it" to it as Long? }
            assertTrue(RevokeTip.processCandidate(pending, { true }, { seq ->
                RevokeMessageLookup.lookup(service, Contact(), RecalledMessageKey(account, chatType, peer, seq!!), 1)
            }, { _, seq, status ->
                statuses[seq!!] = status
                val json = JSONObject(RevokeTip.grayTipJson(chatType, null, seq, status))
                val items = json.getJSONArray("items")
                val links = (0 until items.length()).map { items.getJSONObject(it) }.filter { it.optString("type") == "url" }
                assertEquals(if (status == RevokeMessageLookup.Status.PRESENT) 1 else 0, links.size)
                if (links.isNotEmpty()) assertEquals(seq, links.single().getJSONObject("param").getLong("msgseq"))
                val text = (0 until items.length()).joinToString("") { items.getJSONObject(it).optString("txt") }
                assertEquals(status == RevokeMessageLookup.Status.PRESENT, text.contains("已保留"))
                assertEquals(status == RevokeMessageLookup.Status.PRESENT, RevokeTip.recentAbstract(chatType, status).contains("已保留"))
                if (seq == 7L) RevokeTip.Outcome.UNCONFIRMED else RevokeTip.Outcome.ACCEPTED
            }, { marks.add(it) }))
            assertEquals(listOf(1L), marks)
            assertEquals(RevokeMessageLookup.Status.MISSING, statuses[2L])
            assertEquals(RevokeMessageLookup.Status.MISSING, statuses[3L])
            assertEquals(RevokeMessageLookup.Status.UNKNOWN, statuses[4L])
            assertEquals(RevokeMessageLookup.Status.UNKNOWN, statuses[5L])
            assertEquals(RevokeMessageLookup.Status.MISSING, statuses[6L])
            assertEquals(RevokeMessageLookup.Status.PRESENT, statuses[7L])
        }
    }

    @Test
    fun accountSwitchAndUnconfirmedTipDoNotCreateRetainedMarks() {
        val marked = mutableListOf<Long>()
        var current = true
        var inserted = false
        val pending = listOf("one" to 1L as Long?)
        assertFalse(RevokeTip.processCandidate(pending, { current }, {
            current = false
            RevokeMessageLookup.Status.PRESENT
        }, { _, _, _ ->
            inserted = true
            RevokeTip.Outcome.ACCEPTED
        }, { marked.add(it) }))
        assertFalse(inserted)
        assertTrue(marked.isEmpty())
        assertTrue(RevokeTip.processCandidate(pending, { true }, { RevokeMessageLookup.Status.PRESENT },
            { _, _, _ -> RevokeTip.Outcome.UNCONFIRMED }, { marked.add(it) }))
        assertTrue(marked.isEmpty())
    }

    private fun push(type: Long, subtype: Long, content: ByteArray): ByteArray =
        message(1, message(2, number(1, type) + number(2, subtype)) + message(3, message(2, content)))

    private fun text(field: Int, value: String): ByteArray = message(field, value.toByteArray(Charsets.UTF_8))
    private fun message(field: Int, value: ByteArray): ByteArray = varint((field.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
    private fun number(field: Int, value: Long): ByteArray = varint(field.toLong() shl 3) + varint(value)
    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var remaining = value
        do {
            val low = (remaining and 0x7f).toInt()
            remaining = remaining ushr 7
            out.write(if (remaining == 0L) low else low or 0x80)
        } while (remaining != 0L)
        return out.toByteArray()
    }
}
