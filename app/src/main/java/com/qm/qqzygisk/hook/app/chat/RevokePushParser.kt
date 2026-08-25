package com.qm.qqzygisk.hook.app.chat

/**
 * Reads the NT MSF recall pushes by exact field number.
 *
 * Field layout taken from the QAuxiliary trpc protos
 * (app/src/main/proto/trpc/msg), which is what RevokeMsgHook parses:
 *
 *   MsgPush          { Message message = 1 }
 *   Message          { RoutingHead routing_head = 1, ContentHead content_head = 2, MessageBody body = 3 }
 *   RoutingHead      { from_uin = 1, from_uid = 2, to_uin = 5, to_uid = 6, Group group = 8 }
 *   Group            { group_code = 1 }
 *   ContentHead      { type = 1, sub_type = 2 }
 *   MessageBody      { msg_content = 2 }
 *   C2CMsgRecall     { MsgInfo msg_infos = 1 { from_uid = 1, to_uid = 2, msg_uid = 4, msg_seq = 20 } }
 *   GroupMsgRecall   { op_type = 1, group_code = 4,
 *                      MsgRecallInfo msg_recall = 11 { operator_uid = 1, msg_infos = 3 } }
 *
 * ContentHead 528/138 is a C2C recall, 732/17 a group recall, and the group payload
 * carries 7 leading bytes before GroupMsgRecall starts.
 *
 * `InfoSyncPush` is deliberately ignored: it is a batched offline sync packet and
 * dropping it would lose real messages.
 */
internal object RevokePushParser {
    const val CMD_MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush"

    private const val GROUP_CONTENT_PREFIX = 7
    private const val GROUP_OP_TYPE_RECALL = 7L

    enum class Kind { C2C, GROUP }

    /**
     * [peerCandidates] is ordered by confidence: the exact protobuf field first, then
     * fallbacks in case a QQ build moves things around.
     */
    data class RecallPush(
        val kind: Kind,
        val chatType: Int,
        val operatorUid: String,
        val peerCandidates: List<String>,
        val fingerprint: String,
    )

    fun inspect(cmd: String, proto: ByteArray?, selfUid: String): RecallPush? {
        if (cmd != CMD_MSG_PUSH || proto == null || proto.isEmpty()) return null
        val message = parse(proto, 0, proto.size).message(1) ?: return null
        val head = message.message(2) ?: return null
        val kind = when {
            head.varint(1) == 528L && head.varint(2) == 138L -> Kind.C2C
            head.varint(1) == 732L && head.varint(2) == 17L -> Kind.GROUP
            else -> return null
        }
        val routing = message.message(1)
        val content = message.message(3)?.bytes(2) ?: return null
        val fingerprint = "${proto.size}:${proto.contentHashCode()}"
        return when (kind) {
            Kind.C2C -> c2cRecall(content, routing, selfUid, fingerprint)
            Kind.GROUP -> groupRecall(content, routing, fingerprint)
        }
    }

    /** C2CMsgRecall.msg_infos[0]: from_uid is always the operator (QAuxiliary). */
    private fun c2cRecall(
        content: ByteArray,
        routing: ProtoMessage?,
        selfUid: String,
        fingerprint: String,
    ): RecallPush? {
        val info = parse(content, 0, content.size).message(1) ?: return null
        val fromUid = info.string(1).orEmpty()
        val toUid = info.string(2).orEmpty()
        val operator = fromUid.takeIf { NtMsgAccess.isUid(it) }
            ?: routing?.string(2)?.takeIf { NtMsgAccess.isUid(it) }
            ?: return null
        val peers = LinkedHashSet<String>()
        // QAuxiliary: selfUid.equals(fromUid) ? toUid : fromUid
        if (selfUid.isNotEmpty() && selfUid == fromUid) {
            peers.add(toUid)
        } else {
            peers.add(fromUid)
        }
        peers.add(toUid)
        peers.add(fromUid)
        routing?.string(6)?.let { peers.add(it) }
        return RecallPush(
            kind = Kind.C2C,
            chatType = NtMsgAccess.CHAT_TYPE_C2C,
            operatorUid = operator,
            peerCandidates = peers.filter { NtMsgAccess.isUid(it) && it != selfUid },
            fingerprint = fingerprint,
        )
    }

    /** GroupMsgRecall: op_type must be 7, group_code is field 4, operator in msg_recall. */
    private fun groupRecall(
        content: ByteArray,
        routing: ProtoMessage?,
        fingerprint: String,
    ): RecallPush? {
        if (content.size <= GROUP_CONTENT_PREFIX) return null
        val payload = content.copyOfRange(GROUP_CONTENT_PREFIX, content.size)
        val recall = parse(payload, 0, payload.size)
        val opType = recall.varint(1)
        if (opType != null && opType != GROUP_OP_TYPE_RECALL) return null
        val info = recall.message(11)
        val operator = info?.string(1)?.takeIf { NtMsgAccess.isUid(it) }
            ?: recall.strings().firstOrNull { NtMsgAccess.isUid(it) }
            ?: return null
        val peers = LinkedHashSet<String>()
        recall.varint(4)?.let { peers.add(it.toString()) }
        routing?.message(8)?.varint(1)?.let { peers.add(it.toString()) }
        routing?.varint(5)?.let { peers.add(it.toString()) }
        recall.rootVarints().forEach { peers.add(it.toString()) }
        return RecallPush(
            kind = Kind.GROUP,
            chatType = NtMsgAccess.CHAT_TYPE_GROUP,
            operatorUid = operator,
            peerCandidates = peers.filter { it.length in 5..12 },
            fingerprint = fingerprint,
        )
    }

    private class ProtoMessage {
        private val fields = HashMap<Int, MutableList<Any>>()

        fun add(field: Int, value: Any) {
            fields.getOrPut(field) { ArrayList() }.add(value)
        }

        fun varint(field: Int): Long? = fields[field]?.firstNotNullOfOrNull { it as? Long }

        fun string(field: Int): String? = fields[field]?.firstNotNullOfOrNull { it as? String }

        fun bytes(field: Int): ByteArray? = fields[field]?.firstNotNullOfOrNull { it as? ByteArray }

        fun message(field: Int): ProtoMessage? = fields[field]?.firstNotNullOfOrNull { it as? ProtoMessage }

        private fun ordered(): List<Any> = fields.entries.sortedBy { it.key }.flatMap { it.value }

        /** Direct varint fields only, in field-number order. */
        fun rootVarints(): List<Long> = ordered().filterIsInstance<Long>()

        fun strings(): List<String> {
            val out = ArrayList<String>()
            ordered().forEach { value ->
                if (value is String) out.add(value)
                if (value is ProtoMessage) out.addAll(value.strings())
            }
            return out
        }
    }

    private fun parse(buf: ByteArray, start: Int, end: Int): ProtoMessage {
        val message = ProtoMessage()
        var pos = start
        try {
            while (pos < end) {
                val tag = readVarint(buf, pos, end)
                pos = tag.pos
                val field = (tag.value ushr 3).toInt()
                when ((tag.value and 7L).toInt()) {
                    0 -> {
                        val number = readVarint(buf, pos, end)
                        pos = number.pos
                        message.add(field, number.value)
                    }
                    1 -> pos += 8
                    2 -> {
                        val len = readVarint(buf, pos, end)
                        pos = len.pos
                        if (len.value < 0 || len.value > (end - pos)) return message
                        val next = pos + len.value.toInt()
                        val slice = buf.copyOfRange(pos, next)
                        val text = asText(slice)
                        if (text != null) {
                            message.add(field, text)
                        } else {
                            message.add(field, parse(slice, 0, slice.size))
                        }
                        message.add(field, slice)
                        pos = next
                    }
                    5 -> pos += 4
                    else -> return message
                }
            }
        } catch (_: Throwable) {
            return message
        }
        return message
    }

    private fun asText(slice: ByteArray): String? {
        if (slice.isEmpty() || slice.size > 128) return null
        val text = runCatching { String(slice, Charsets.UTF_8) }.getOrNull() ?: return null
        val printable = text.all { ch -> ch.code in 32..126 || ch in '\u4e00'..'\u9fff' }
        return if (printable) text else null
    }

    private data class Varint(val value: Long, val pos: Int)

    private fun readVarint(buf: ByteArray, start: Int, end: Int): Varint {
        var pos = start
        var result = 0L
        var shift = 0
        while (pos < end) {
            val byte = buf[pos].toInt() and 0xff
            pos++
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return Varint(result, pos)
            shift += 7
            if (shift > 63) break
        }
        throw IllegalArgumentException("truncated varint")
    }
}
