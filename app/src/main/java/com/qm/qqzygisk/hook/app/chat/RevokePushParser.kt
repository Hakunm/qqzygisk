package com.qm.qqzygisk.hook.app.chat

import java.io.ByteArrayOutputStream

/**
 * Reads the NT MSF recall pushes by exact field number.
 *
 * Field layout:
 *
 *   MsgPush          { Message message = 1 }
 *   Message          { RoutingHead routing_head = 1, ContentHead content_head = 2, MessageBody body = 3 }
 *   RoutingHead      { from_uin = 1, from_uid = 2, to_uin = 5, to_uid = 6, Group group = 8 }
 *   Group            { group_code = 1 }
 *   ContentHead      { type = 1, sub_type = 2 }
 *   MessageBody      { msg_content = 2 }
 *   C2CMsgRecall     { repeated MsgInfo msg_infos = 1
 *                        { from_uid = 1, to_uid = 2, msg_uid = 4, msg_seq = 20 } }
 *   GroupMsgRecall   { op_type = 1, group_code = 4,
 *                      MsgRecallInfo msg_recall = 11 { operator_uid = 1,
 *                        repeated MsgInfo msg_infos = 3 { msg_seq = 1 } } }
 *
 * ContentHead 528/138 is a C2C recall, 732/17 a group recall. Group payloads normally carry
 * 7 leading bytes before GroupMsgRecall starts, and unwrapped payloads without the prefix
 * are accepted as a strict fallback.
 *
 * Only those msg_seq fields can locate the original message. msg_uid/client_seq and
 * the group packet's outer field 37 hold different numbers and are never used;
 * navigating with them opens the conversation at the wrong message.
 *
 * InfoSyncPush is filtered at the offline sync path (`8 -> 4 -> 8`) for both group (732/17)
 * and C2C (528/138) peer recalls without dropping the surrounding sync packet, cursors,
 * ordinary messages, or own recalls.
 */
internal object RevokePushParser {
    const val CMD_MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush"
    const val CMD_INFO_SYNC_PUSH = "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush"

    private const val GROUP_CONTENT_PREFIX = 7
    private const val GROUP_OP_TYPE_RECALL = 7L
    private const val MAX_PROTO_RECURSION_DEPTH = 8

    data class FilteredSync(val payload: ByteArray, val recalls: List<RecallPush>)

    /**
     * 剥离 MSF `FromServiceMsg.getWupBuffer()` 可能携带的 4 字节大端总长度头。
     *
     * 当首 4 字节大端整数恰好等于数组总长度时视为长度前缀并剥离，否则原样返回。
     */
    fun unwrapWupBuffer(raw: ByteArray?): ByteArray? {
        if (raw == null || raw.isEmpty()) return null
        return if (hasWupLengthPrefix(raw)) {
            if (raw.size == 4) ByteArray(0) else raw.copyOfRange(4, raw.size)
        } else {
            raw
        }
    }

    /** 按原始 WUP 缓冲是否带 4 字节长度头，对修改后的 protobuf 载荷重新封包。 */
    fun rewrapWupBuffer(original: ByteArray, payload: ByteArray): ByteArray {
        if (!hasWupLengthPrefix(original)) return payload
        val total = payload.size + 4
        val wrapped = ByteArray(total)
        wrapped[0] = (total ushr 24).toByte()
        wrapped[1] = (total ushr 16).toByte()
        wrapped[2] = (total ushr 8).toByte()
        wrapped[3] = total.toByte()
        System.arraycopy(payload, 0, wrapped, 4, payload.size)
        return wrapped
    }

    private fun hasWupLengthPrefix(buf: ByteArray): Boolean {
        if (buf.size < 4) return false
        val declared =
            ((buf[0].toInt() and 0xff) shl 24) or
                ((buf[1].toInt() and 0xff) shl 16) or
                ((buf[2].toInt() and 0xff) shl 8) or
                (buf[3].toInt() and 0xff)
        return declared == buf.size
    }

    /** Removes positively identified peer recalls (both group and C2C) from offline sync. */
    fun filterInfoSync(proto: ByteArray, selfUid: String): FilteredSync? {
        if (proto.isEmpty()) return null
        val recalls = ArrayList<RecallPush>()
        return try {
            val payload = filterSyncContainer(proto, 0, proto.size, 0, selfUid, recalls) ?: return null
            FilteredSync(payload, recalls)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // Established offline sync path: InfoSyncPush.8 -> sync_msg_recall.4 -> body.8.
    // Unchanged fields are copied as original wire bytes, including noncanonical tags.
    private fun filterSyncContainer(
        buf: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        selfUid: String,
        recalls: MutableList<RecallPush>,
    ): ByteArray? {
        var bodyPeerRaw: String? = null
        var bodyGroup: String? = null
        var bodyC2cPeer: String? = null
        if (depth == 2) {
            scanWire(buf, start, end) { field ->
                if (field.number == 2 && field.wire == 2) {
                    val peer = String(buf, field.valueStart, field.end - field.valueStart, Charsets.UTF_8)
                    bodyPeerRaw = peer
                    bodyGroup =
                        if (peer.isNotEmpty() && peer.all { it in '0'..'9' }) {
                            peer.toLongOrNull()?.takeIf { it > 0 }?.toString()
                        } else {
                            null
                        }
                    bodyC2cPeer = peer.takeIf { NtMsgAccess.isUid(it) }
                }
            }
            if (bodyPeerRaw != null && bodyGroup == null && bodyC2cPeer == null) return null
        }
        var output: ByteArrayOutputStream? = null
        var copied = start
        scanWire(buf, start, end) { field ->
            val target = when (depth) { 0 -> 8; 1 -> 4; else -> 8 }
            if (field.number == target && field.wire == 2) {
                if (depth == 2) {
                    val recall = inspectSyncMessage(buf, field.valueStart, field.end, selfUid)
                    if (recall != null && shouldFilterSyncRecall(recall, selfUid, bodyPeerRaw, bodyGroup, bodyC2cPeer)) {
                        val out = output ?: ByteArrayOutputStream().also { output = it }
                        out.write(buf, copied, field.start - copied)
                        copied = field.end
                        recalls.add(recall)
                    }
                } else {
                    val replacement = filterSyncContainer(buf, field.valueStart, field.end, depth + 1, selfUid, recalls)
                    if (replacement != null) {
                        val out = output ?: ByteArrayOutputStream().also { output = it }
                        out.write(buf, copied, field.start - copied)
                        out.write(buf, field.start, field.tagEnd - field.start)
                        writeWireLength(out, replacement.size)
                        out.write(replacement)
                        copied = field.end
                    }
                }
            }
        }
        return output?.also { it.write(buf, copied, end - copied) }?.toByteArray()
    }

    private fun shouldFilterSyncRecall(
        recall: RecallPush,
        runtimeSelfUid: String,
        bodyPeerRaw: String?,
        bodyGroup: String?,
        bodyC2cPeer: String?,
    ): Boolean {
        val effectiveSelf = resolveSelfUid(runtimeSelfUid, recall.packetSelfUid)
        if (!NtMsgAccess.isUid(effectiveSelf)) return false
        if (recall.operatorUid == effectiveSelf) return false
        return when (recall.kind) {
            Kind.GROUP -> bodyGroup != null && recall.peerCandidates.singleOrNull() == bodyGroup
            Kind.C2C -> {
                if (bodyPeerRaw != null) {
                    bodyC2cPeer != null &&
                        bodyC2cPeer == recall.operatorUid &&
                        bodyC2cPeer in recall.peerCandidates
                } else {
                    recall.peerCandidates.isNotEmpty()
                }
            }
        }
    }

    private data class WireField(
        val number: Int, val wire: Int, val start: Int, val tagEnd: Int,
        val valueStart: Int, val end: Int, val value: Long,
    )

    private inline fun scanWire(buf: ByteArray, start: Int, end: Int, visit: (WireField) -> Unit) {
        var pos = start
        while (pos < end) {
            val fieldStart = pos
            val tag = readStrictVarint(buf, pos, end)
            pos = tag.pos
            val number = tag.value ushr 3
            if (number == 0L || number > 0x1fffffffL) throw IllegalArgumentException("invalid tag")
            val wire = (tag.value and 7).toInt()
            val tagEnd = pos
            var value = 0L
            val valueStart: Int
            when (wire) {
                0 -> {
                    valueStart = pos
                    val item = readStrictVarint(buf, pos, end)
                    value = item.value
                    pos = item.pos
                }
                1, 5 -> {
                    valueStart = pos
                    val size = if (wire == 1) 8 else 4
                    if (end - pos < size) throw IllegalArgumentException("truncated fixed field")
                    pos += size
                }
                2 -> {
                    val length = readStrictVarint(buf, pos, end)
                    pos = length.pos
                    if (length.value < 0 || length.value > end - pos) throw IllegalArgumentException("invalid length")
                    valueStart = pos
                    pos += length.value.toInt()
                }
                else -> throw IllegalArgumentException("unsupported wire type")
            }
            visit(WireField(number.toInt(), wire, fieldStart, tagEnd, valueStart, pos, value))
        }
    }

    private fun readStrictVarint(buf: ByteArray, start: Int, end: Int): Varint {
        var value = 0L
        for (index in 0..9) {
            val pos = start + index
            if (pos >= end) throw IllegalArgumentException("truncated varint")
            val byte = buf[pos].toInt() and 0xff
            if (index == 9 && byte > 1) throw IllegalArgumentException("varint overflow")
            value = value or ((byte and 0x7f).toLong() shl (index * 7))
            if (byte and 0x80 == 0) return Varint(value, pos + 1)
        }
        throw IllegalArgumentException("varint overflow")
    }

    private fun writeWireLength(out: ByteArrayOutputStream, length: Int) {
        var value = length
        while (value >= 128) {
            out.write((value and 127) or 128)
            value = value ushr 7
        }
        out.write(value)
    }

    private fun inspectSyncMessage(
        buf: ByteArray,
        start: Int,
        end: Int,
        selfUid: String,
    ): RecallPush? {
        var type: Long? = null
        var subtype: Long? = null
        var routingStart = -1
        var routingEnd = -1
        var contentStart = -1
        var contentEnd = -1
        scanWire(buf, start, end) { field ->
            when {
                field.number == 1 && field.wire == 2 -> {
                    routingStart = field.valueStart
                    routingEnd = field.end
                }
                field.number == 2 && field.wire == 2 -> {
                    scanWire(buf, field.valueStart, field.end) { head ->
                        if (head.wire == 0 && head.number == 1) type = head.value
                        if (head.wire == 0 && head.number == 2) subtype = head.value
                    }
                }
                field.number == 3 && field.wire == 2 -> {
                    scanWire(buf, field.valueStart, field.end) { body ->
                        if (body.number == 2 && body.wire == 2) {
                            contentStart = body.valueStart
                            contentEnd = body.end
                        }
                    }
                }
            }
        }
        if (contentStart < 0 || contentEnd <= contentStart) return null
        val routing = if (routingStart >= 0) parse(buf, routingStart, routingEnd) else null
        val packetSelfUid = routing?.string(6)?.takeIf { NtMsgAccess.isUid(it) }.orEmpty()
        val effectiveSelfUid = resolveSelfUid(selfUid, packetSelfUid)
        val fingerprint = canonicalMsgPushFingerprint(buf, start, end)
        return when {
            type == 732L && subtype == 17L ->
                inspectSyncGroupRecall(buf, contentStart, contentEnd, routing, packetSelfUid, fingerprint)
            type == 528L && subtype == 138L -> {
                val content = buf.copyOfRange(contentStart, contentEnd)
                c2cRecall(content, routing, effectiveSelfUid, packetSelfUid, fingerprint)
            }
            else -> null
        }
    }

    private fun inspectSyncGroupRecall(
        buf: ByteArray,
        contentStart: Int,
        contentEnd: Int,
        routing: ProtoMessage?,
        packetSelfUid: String,
        fingerprint: String,
    ): RecallPush? {
        // 标准形态：7 字节前缀 + GroupMsgRecall；若前缀剥离后无法解析则回退到无前缀形态。
        val prefixed =
            if (contentEnd - contentStart > GROUP_CONTENT_PREFIX) {
                parseSyncGroupBody(buf, contentStart + GROUP_CONTENT_PREFIX, contentEnd, routing, strictOp = true)
            } else {
                null
            }
        val parsed =
            prefixed
                ?: parseSyncGroupBody(buf, contentStart, contentEnd, routing, strictOp = true)
                ?: return null
        return RecallPush(
            kind = Kind.GROUP,
            chatType = NtMsgAccess.CHAT_TYPE_GROUP,
            operatorUid = parsed.operatorUid,
            peerCandidates = listOf(parsed.groupCode.toString()),
            fingerprint = fingerprint,
            messageSeqs = parsed.seqs,
            packetSelfUid = packetSelfUid,
        )
    }

    private data class SyncGroupBody(
        val groupCode: Long,
        val operatorUid: String,
        val seqs: List<Long>,
    )

    private fun parseSyncGroupBody(
        buf: ByteArray,
        start: Int,
        end: Int,
        routing: ProtoMessage?,
        strictOp: Boolean,
    ): SyncGroupBody? {
        var op: Long? = null
        var group: Long? = null
        var operator: String? = null
        val seqs = LinkedHashSet<Long>()
        try {
            scanWire(buf, start, end) { field ->
                if (field.wire == 0 && field.number == 1) op = field.value
                if (field.wire == 0 && field.number == 4) group = field.value
                if (field.wire == 2 && field.number == 11) {
                    scanWire(buf, field.valueStart, field.end) { info ->
                        if (info.number == 1 && info.wire == 2) {
                            operator = String(buf, info.valueStart, info.end - info.valueStart, Charsets.UTF_8)
                        } else if (info.number == 3 && info.wire == 2) {
                            var seq: Long? = null
                            scanWire(buf, info.valueStart, info.end) { entry ->
                                if (entry.number == 1 && entry.wire == 0) seq = entry.value
                            }
                            seq?.takeIf { it > 0 }?.let { seqs.add(it) }
                        }
                    }
                }
            }
        } catch (_: IllegalArgumentException) {
            return null
        }
        val uid = operator?.takeIf { NtMsgAccess.isUid(it) } ?: return null
        if (strictOp) {
            if (op != GROUP_OP_TYPE_RECALL) return null
        } else if (op != null && op != GROUP_OP_TYPE_RECALL) {
            return null
        }
        val groupCode =
            group?.takeIf { it > 0 }
                ?: routing?.message(8)?.varint(1)?.takeIf { it > 0 }
                ?: routing?.varint(1)?.takeIf { it > 0 }
                ?: return null
        return SyncGroupBody(groupCode, uid, seqs.toList())
    }

    /** Hash the canonical MsgPush field 1 tag and length followed by Message bytes. */
    private fun canonicalMsgPushFingerprint(buf: ByteArray, start: Int, end: Int): String {
        val messageSize = end - start
        var hash = 31 + 10
        var length = messageSize
        var lengthSize = 1
        while (length >= 128) {
            hash = 31 * hash + ((length and 127) or 128).toByte().toInt()
            length = length ushr 7
            lengthSize++
        }
        hash = 31 * hash + length.toByte().toInt()
        for (i in start until end) hash = 31 * hash + buf[i]
        return "${1L + lengthSize + messageSize}:$hash"
    }

    enum class Kind { C2C, GROUP }

    /**
     * [peerCandidates] is ordered by confidence: the exact protobuf field first, then
     * fallbacks in case a QQ build moves things around.
     *
     * [messageSeqs] holds the msg_seq of every revoked message that provably belongs
     * to this conversation, in packet order and without duplicates. It stays empty
     * when the push carries no usable seq, and a tip then renders without navigation.
     *
     * [packetSelfUid] carries the recipient uid from `routing_head.to_uid` when present,
     * allowing accurate self-vs-peer classification even before runtime uid lookup is ready.
     */
    data class RecallPush(
        val kind: Kind,
        val chatType: Int,
        val operatorUid: String,
        val peerCandidates: List<String>,
        val fingerprint: String,
        val messageSeqs: List<Long>,
        val packetSelfUid: String = "",
    )

    fun inspect(
        cmd: String,
        proto: ByteArray?,
        selfUid: String,
    ): RecallPush? {
        if (cmd != CMD_MSG_PUSH || proto == null || proto.isEmpty()) return null
        val message = parse(proto, 0, proto.size).message(1) ?: return null
        val head = message.message(2) ?: return null
        val kind =
            when {
                head.varint(1) == 528L && head.varint(2) == 138L -> Kind.C2C
                head.varint(1) == 732L && head.varint(2) == 17L -> Kind.GROUP
                else -> return null
            }
        val routing = message.message(1)
        val packetSelfUid = routing?.string(6)?.takeIf { NtMsgAccess.isUid(it) }.orEmpty()
        val effectiveSelfUid = resolveSelfUid(selfUid, packetSelfUid)
        val content = message.message(3)?.bytes(2) ?: return null
        val fingerprint = "${proto.size}:${proto.contentHashCode()}"
        return when (kind) {
            Kind.C2C -> c2cRecall(content, routing, effectiveSelfUid, packetSelfUid, fingerprint)
            Kind.GROUP -> groupRecall(content, routing, packetSelfUid, fingerprint)
        }
    }

    internal fun resolveSelfUid(runtimeSelfUid: String, packetSelfUid: String): String =
        runtimeSelfUid.takeIf { NtMsgAccess.isUid(it) }
            ?: packetSelfUid.takeIf { NtMsgAccess.isUid(it) }
            ?: ""

    /**
     * C2CMsgRecall.msg_infos is repeated and QQ batches several revoked messages into
     * one push. `from_uid` of the first entry is always the operator, so
     * that entry decides the conversation and every later entry must repeat both
     * uids exactly before its seq may be linked.
     */
    private fun c2cRecall(
        content: ByteArray,
        routing: ProtoMessage?,
        selfUid: String,
        packetSelfUid: String,
        fingerprint: String,
    ): RecallPush? {
        val envelope = parse(content, 0, content.size)
        val infos = envelope.messages(1)
        val info = infos.firstOrNull() ?: return null
        val fromUid = info.string(1).orEmpty()
        val toUid = info.string(2).orEmpty()
        val operator =
            fromUid.takeIf { NtMsgAccess.isUid(it) }
                ?: routing?.string(2)?.takeIf { NtMsgAccess.isUid(it) }
                ?: return null
        val peers = LinkedHashSet<String>()
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
            messageSeqs = c2cSeqs(infos, fromUid, toUid),
            packetSelfUid = packetSelfUid,
        )
    }

    /**
     * Only seqs whose entry repeats the first entry's from/to uid verbatim are linked.
     * An entry with a different direction belongs to another conversation, and an
     * empty uid makes the batch unattributable, so nothing is linked at all.
     */
    private fun c2cSeqs(
        infos: List<ProtoMessage>,
        fromUid: String,
        toUid: String,
    ): List<Long> {
        if (fromUid.isEmpty() || toUid.isEmpty()) return emptyList()
        val seqs = LinkedHashSet<Long>()
        infos.forEach { info ->
            if (info.string(1) != fromUid || info.string(2) != toUid) return@forEach
            val seq = info.varint(20) ?: return@forEach
            if (seq > 0) seqs.add(seq)
        }
        return seqs.toList()
    }

    /**
     * GroupMsgRecall: op_type must be 7 when present, group_code is field 4, operator in msg_recall.
     * Supports both the standard 7-byte prefixed payload and an unwrapped nested protobuf payload.
     */
    private fun groupRecall(
        content: ByteArray,
        routing: ProtoMessage?,
        packetSelfUid: String,
        fingerprint: String,
    ): RecallPush? {
        if (content.size > GROUP_CONTENT_PREFIX) {
            val prefixed = parseGroupRecallMessage(
                content,
                GROUP_CONTENT_PREFIX,
                content.size,
                routing,
                packetSelfUid,
                fingerprint,
                strictWire = false,
            )
            if (prefixed != null) return prefixed
        }
        return parseGroupRecallMessage(
            content,
            0,
            content.size,
            routing,
            packetSelfUid,
            fingerprint,
            strictWire = true,
        )
    }

    private fun parseGroupRecallMessage(
        content: ByteArray,
        start: Int,
        end: Int,
        routing: ProtoMessage?,
        packetSelfUid: String,
        fingerprint: String,
        strictWire: Boolean,
    ): RecallPush? {
        if (end <= start) return null
        if (strictWire && !isStrictProto(content, start, end)) return null
        val recall = parse(content, start, end)
        val opType = recall.varint(1)
        if (opType != null && opType != GROUP_OP_TYPE_RECALL) return null
        val info = recall.message(11)
        val operator =
            info?.string(1)?.takeIf { NtMsgAccess.isUid(it) }
                ?: (if (!strictWire) recall.strings().firstOrNull { NtMsgAccess.isUid(it) } else null)
                ?: return null
        val peers = LinkedHashSet<String>()
        recall.varint(4)?.let { peers.add(it.toString()) }
        routing?.message(8)?.varint(1)?.let { peers.add(it.toString()) }
        routing?.varint(1)?.let { peers.add(it.toString()) }
        routing?.varint(5)?.let { peers.add(it.toString()) }
        recall.rootVarints().forEach { peers.add(it.toString()) }
        return RecallPush(
            kind = Kind.GROUP,
            chatType = NtMsgAccess.CHAT_TYPE_GROUP,
            operatorUid = operator,
            peerCandidates = peers.filter { it.length in 5..12 },
            fingerprint = fingerprint,
            messageSeqs = groupSeqs(info),
            packetSelfUid = packetSelfUid,
        )
    }

    private fun isStrictProto(buf: ByteArray, start: Int, end: Int): Boolean {
        if (end <= start) return false
        var count = 0
        return try {
            scanWire(buf, start, end) { count++ }
            count > 0
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /**
     * msgs all belong to the single recalled group, so the only condition is a
     * positive msg_seq. The group packet's outer seq (field 37) is not a msg_seq.
     */
    private fun groupSeqs(info: ProtoMessage?): List<Long> {
        val seqs = LinkedHashSet<Long>()
        info?.messages(3)?.forEach { entry ->
            val seq = entry.varint(1) ?: return@forEach
            if (seq > 0) seqs.add(seq)
        }
        return seqs.toList()
    }

    /**
     * length-delimited 字段的原始字节载体。
     *
     * 按需解码为文本或嵌套 [ProtoMessage]，两者互不排斥：即使某个嵌套子消息的字节恰好全部落在
     * 可打印 ASCII / 中文区间，也不会被误判成纯字符串而跳过子消息解析。
     */
    private class RawBytes(val bytes: ByteArray) {
        private var textResolved = false
        private var cachedText: String? = null
        private var parsedMessage: ProtoMessage? = null

        fun text(): String? {
            if (!textResolved) {
                cachedText = asText(bytes)
                textResolved = true
            }
            return cachedText
        }

        fun asMessage(): ProtoMessage {
            parsedMessage?.let { return it }
            return parse(bytes, 0, bytes.size).also { parsedMessage = it }
        }

        fun asStrictMessageOrNull(): ProtoMessage? =
            if (isStrictProto(bytes, 0, bytes.size)) asMessage() else null
    }

    private class ProtoMessage {
        private val fields = HashMap<Int, MutableList<Any>>()

        fun add(
            field: Int,
            value: Any,
        ) {
            fields.getOrPut(field) { ArrayList() }.add(value)
        }

        fun varint(field: Int): Long? = fields[field]?.firstNotNullOfOrNull { it as? Long }

        fun string(field: Int): String? =
            fields[field]?.firstNotNullOfOrNull { (it as? RawBytes)?.text() }

        fun bytes(field: Int): ByteArray? =
            fields[field]?.firstNotNullOfOrNull { (it as? RawBytes)?.bytes }

        fun message(field: Int): ProtoMessage? =
            fields[field]?.firstNotNullOfOrNull { (it as? RawBytes)?.asMessage() }

        fun messages(field: Int): List<ProtoMessage> =
            fields[field]?.mapNotNull { (it as? RawBytes)?.asMessage() }.orEmpty()

        private fun ordered(): List<Any> = fields.entries.sortedBy { it.key }.flatMap { it.value }

        /** Direct varint fields only, in field-number order. */
        fun rootVarints(): List<Long> = ordered().filterIsInstance<Long>()

        fun strings(depth: Int = 0): List<String> {
            if (depth >= MAX_PROTO_RECURSION_DEPTH) return emptyList()
            val out = ArrayList<String>()
            ordered().forEach { value ->
                if (value is RawBytes) {
                    value.text()?.let { out.add(it) }
                    value.asStrictMessageOrNull()?.let { out.addAll(it.strings(depth + 1)) }
                }
            }
            return out
        }
    }

    private fun parse(
        buf: ByteArray,
        start: Int,
        end: Int,
    ): ProtoMessage {
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

                    1 -> {
                        pos += 8
                    }

                    2 -> {
                        val len = readVarint(buf, pos, end)
                        pos = len.pos
                        if (len.value < 0 || len.value > (end - pos)) return message
                        val next = pos + len.value.toInt()
                        val slice = buf.copyOfRange(pos, next)
                        message.add(field, RawBytes(slice))
                        pos = next
                    }

                    5 -> {
                        pos += 4
                    }

                    else -> {
                        return message
                    }
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

    private data class Varint(
        val value: Long,
        val pos: Int,
    )

    private fun readVarint(
        buf: ByteArray,
        start: Int,
        end: Int,
    ): Varint {
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
