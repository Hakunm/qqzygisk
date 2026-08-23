package com.qm.qqzygisk.hook.app.chat

internal data class RkeySnapshot(
    val byType: Map<Int, String>,
    val expiresAtMillis: Long,
) {
    fun hasUsable(nowMillis: Long): Boolean =
        byType.values.any { it.isNotBlank() } && nowMillis < expiresAtMillis
}

internal object NtImageRkey {
    const val TYPE_PRIVATE = 10
    const val TYPE_GROUP = 20
    const val APPID_PRIVATE = "1406"
    const val APPID_GROUP = "1407"
    const val DEFAULT_TTL_MS = 25 * 60 * 1000L
    const val REFRESH_SKEW_MS = 60_000L
    const val REFRESH_COOLDOWN_MS = 15_000L
    const val EMPTY_COOLDOWN_MS = 10_000L
    const val REFRESH_WAIT_MS = 2_500L

    private val RKEY_QUERY = Regex("""([?&])rkey=[^&]*""")

    val REQUEST_OIDB: ByteArray = hex(
        "08e7a00210ca01221c0a130a05080110ca011206a80602b006011a02080122050a030a1400",
    )

    fun needsRkey(url: String): Boolean {
        if (url.isBlank() || url.contains("gchat.qpic.cn")) return false
        return url.contains("multimedia.nt.qq.com.cn") ||
            url.contains(".nt.qq.com.cn") ||
            url.contains("/download")
    }

    fun typeForUrl(url: String): Int =
        if (url.contains("appid=$APPID_PRIVATE")) TYPE_PRIVATE else TYPE_GROUP

    fun select(url: String, snapshot: RkeySnapshot): String? {
        val primary = typeForUrl(url)
        val fallback = if (primary == TYPE_PRIVATE) TYPE_GROUP else TYPE_PRIVATE
        return snapshot.byType[primary]?.takeIf { it.isNotBlank() }
            ?: snapshot.byType[fallback]?.takeIf { it.isNotBlank() }
    }

    fun shouldRefresh(
        snapshot: RkeySnapshot?,
        nowMillis: Long,
        skewMillis: Long = REFRESH_SKEW_MS,
    ): Boolean {
        if (snapshot == null) return true
        if (snapshot.byType.values.none { it.isNotBlank() }) return true
        return nowMillis + skewMillis >= snapshot.expiresAtMillis
    }

    fun apply(url: String, rkey: String): String {
        val token = tokenOf(rkey)
        if (token.isBlank()) return url
        val replaced = RKEY_QUERY.replace(url) { match ->
            "${match.groupValues[1]}rkey=$token"
        }
        if (replaced != url) return replaced
        val separator = when {
            url.endsWith('?') || url.endsWith('&') -> ""
            url.contains('?') -> "&"
            else -> "?"
        }
        return url + separator + "rkey=" + token
    }

    fun prefixWup(body: ByteArray): ByteArray {
        val out = ByteArray(4 + body.size)
        val len = body.size
        out[0] = (len ushr 24).toByte()
        out[1] = (len ushr 16).toByte()
        out[2] = (len ushr 8).toByte()
        out[3] = len.toByte()
        body.copyInto(out, 4)
        return out
    }

    fun unpackWup(buffer: ByteArray): ByteArray =
        if (buffer.size >= 4 && buffer[0].toInt() == 0) {
            buffer.copyOfRange(4, buffer.size)
        } else {
            buffer
        }

    fun parse(buffer: ByteArray, nowMillis: Long): RkeySnapshot {
        val response = proto(buffer).messages(4).firstOrNull()
            ?: error("NT 图片 rkey 响应缺少字段 4")
        val downloadInfo = response.messages(4).firstOrNull()
            ?: error("NT 图片 rkey 缺少下载信息")
        val entries = downloadInfo.bytes(1)
        check(entries.isNotEmpty()) { "NT 图片 rkey 条目不足" }

        val byType = linkedMapOf<Int, String>()
        var minExpiry = Long.MAX_VALUE
        entries.forEachIndexed { index, raw ->
            val entry = parseEntry(raw, index, nowMillis) ?: return@forEachIndexed
            byType[entry.type] = entry.value
            minExpiry = minOf(minExpiry, entry.expiresAtMillis)
        }
        check(byType.isNotEmpty()) { "NT 图片 rkey 值为空" }
        val expiresAt = if (minExpiry == Long.MAX_VALUE) nowMillis + DEFAULT_TTL_MS else minExpiry
        return RkeySnapshot(byType, expiresAt)
    }

    internal fun encodeResponse(entries: List<RkeyFixture>): ByteArray {
        val downloadInfo = buildProto {
            entries.forEach { entry ->
                bytes(
                    1,
                    buildProto {
                        bytes(1, entry.value.toByteArray(Charsets.UTF_8))
                        entry.ttlSeconds?.let { varint(2, it) }
                        entry.createTimeSeconds?.let { varint(3, it) }
                        entry.type?.let { varint(5, it.toLong()) }
                    },
                )
            }
        }
        val response = buildProto { bytes(4, downloadInfo) }
        return buildProto { bytes(4, response) }
    }

    internal data class RkeyFixture(
        val value: String,
        val type: Int? = null,
        val ttlSeconds: Long? = null,
        val createTimeSeconds: Long? = null,
    )

    private data class ParsedEntry(
        val value: String,
        val type: Int,
        val expiresAtMillis: Long,
    )

    private fun parseEntry(raw: ByteArray, index: Int, nowMillis: Long): ParsedEntry? {
        val message = proto(raw)
        val value = message.bytes(1).firstOrNull()
            ?.toString(Charsets.UTF_8)
            ?.trimEnd('\u0000')
            ?.takeIf { it.contains("rkey=") || it.isNotBlank() }
            ?: return null
        val type = message.varints(5).firstOrNull()?.toInt()
            ?.takeIf { it == TYPE_PRIVATE || it == TYPE_GROUP }
            ?: when (index) {
                0 -> TYPE_PRIVATE
                1 -> TYPE_GROUP
                else -> return null
            }
        val ttlSeconds = message.varints(2).firstOrNull()?.takeIf { it in 1..7 * 24 * 3600 }
        val createTimeSeconds = message.varints(3).firstOrNull()?.takeIf { it > 1_000_000_000L }
            ?: message.varints(4).firstOrNull()?.takeIf { it > 1_000_000_000L }
        val expiresAt = when {
            ttlSeconds != null && createTimeSeconds != null ->
                createTimeSeconds * 1000 + ttlSeconds * 1000
            ttlSeconds != null -> nowMillis + ttlSeconds * 1000
            else -> nowMillis + DEFAULT_TTL_MS
        }
        return ParsedEntry(value, type, expiresAt)
    }

    private fun tokenOf(rkey: String): String =
        rkey.removePrefix("?").removePrefix("&").removePrefix("rkey=")

    private fun hex(value: String): ByteArray {
        check(value.length % 2 == 0) { "invalid hex" }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private data class ProtoMessage(
        private val delimited: Map<Int, List<ByteArray>>,
        private val varints: Map<Int, List<Long>>,
    ) {
        fun bytes(field: Int): List<ByteArray> = delimited[field].orEmpty()
        fun varints(field: Int): List<Long> = varints[field].orEmpty()
        fun messages(field: Int): List<ProtoMessage> = bytes(field).map(::proto)
    }

    private fun proto(data: ByteArray): ProtoMessage {
        val delimited = linkedMapOf<Int, MutableList<ByteArray>>()
        val varints = linkedMapOf<Int, MutableList<Long>>()
        var offset = 0
        while (offset < data.size) {
            val tag = readVarint(data, offset)
            offset = tag.nextOffset
            check(tag.value != 0L) { "无效的 protobuf tag" }
            val field = (tag.value ushr 3).toInt()
            when ((tag.value and 7).toInt()) {
                0 -> {
                    val number = readVarint(data, offset)
                    offset = number.nextOffset
                    varints.getOrPut(field) { mutableListOf() } += number.value
                }
                1 -> offset = checkedOffset(offset, 8, data.size)
                2 -> {
                    val length = readVarint(data, offset)
                    offset = length.nextOffset
                    check(length.value <= Int.MAX_VALUE) { "protobuf 字段过大" }
                    val end = checkedOffset(offset, length.value.toInt(), data.size)
                    delimited.getOrPut(field) { mutableListOf() } += data.copyOfRange(offset, end)
                    offset = end
                }
                5 -> offset = checkedOffset(offset, 4, data.size)
                else -> error("不支持的 protobuf wire type: ${tag.value and 7}")
            }
        }
        return ProtoMessage(delimited, varints)
    }

    private class ProtoWriter {
        private val out = java.io.ByteArrayOutputStream()

        fun varint(field: Int, value: Long) {
            writeVarint((field.toLong() shl 3) or 0)
            writeVarint(value)
        }

        fun bytes(field: Int, value: ByteArray) {
            writeVarint((field.toLong() shl 3) or 2)
            writeVarint(value.size.toLong())
            out.write(value)
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun writeVarint(value: Long) {
            var current = value
            while (true) {
                if (current and 0x7fL.inv() == 0L) {
                    out.write(current.toInt())
                    return
                }
                out.write(((current and 0x7f) or 0x80).toInt())
                current = current ushr 7
            }
        }
    }

    private inline fun buildProto(block: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().apply(block).toByteArray()

    private fun checkedOffset(offset: Int, byteCount: Int, size: Int): Int {
        check(byteCount >= 0 && offset <= size - byteCount) { "protobuf 字段被截断" }
        return offset + byteCount
    }

    private fun readVarint(data: ByteArray, startOffset: Int): Varint {
        var offset = startOffset
        var value = 0L
        var shift = 0
        while (offset < data.size && shift < Long.SIZE_BITS) {
            val current = data[offset++].toInt() and 0xff
            value = value or ((current and 0x7f).toLong() shl shift)
            if (current and 0x80 == 0) return Varint(value, offset)
            shift += 7
        }
        error("无效的 protobuf varint")
    }

    private data class Varint(
        val value: Long,
        val nextOffset: Int,
    )
}
