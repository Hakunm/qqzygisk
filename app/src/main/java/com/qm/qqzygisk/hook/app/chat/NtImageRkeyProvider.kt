package com.qm.qqzygisk.hook.app.chat

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.extension.hookAll
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal object NtImageRkeyProvider {
    private const val RKEY_SERVICE_COMMAND = "OidbSvcTrpcTcp.0x9067_202"

    @Volatile
    private var groupRkey: String? = null

    @Volatile
    private var privateRkey: String? = null

    private val responseFields = ConcurrentHashMap<Class<*>, Field>()
    private val serviceCommandMethods = ConcurrentHashMap<Class<*>, Method>()
    private val wupBufferMethods = ConcurrentHashMap<Class<*>, Method>()

    fun installHook() {
        val handlerClass = Class.forName(
            "mqq.app.msghandle.MsgRespHandler",
            false,
            appClassLoader,
        )
        val dispatchMethods = handlerClass.resolve().method {
            name = "dispatchRespMsg"
        }
        check(dispatchMethods.isNotEmpty()) { "未找到 MsgRespHandler.dispatchRespMsg" }

        dispatchMethods.hookAll {
            before {
                val responseHolder = args.getOrNull(1) ?: return@before
                runCatching {
                    captureRkeys(responseHolder)
                }.onFailure {
                    Log.error("捕获 NT 图片 rkey 失败", it)
                }
            }
        }
    }

    fun get(originUrl: String): String? =
        if (originUrl.contains("appid=1406")) groupRkey else privateRkey

    private fun captureRkeys(responseHolder: Any) {
        val fromServiceMsg = findFromServiceMsg(responseHolder) ?: return
        val serviceCommand = serviceCommandMethods[fromServiceMsg.javaClass]
            ?: fromServiceMsg.javaClass.getMethod("getServiceCmd").also {
                serviceCommandMethods[fromServiceMsg.javaClass] = it
            }
        if (serviceCommand.invoke(fromServiceMsg) != RKEY_SERVICE_COMMAND) return

        val getWupBuffer = wupBufferMethods[fromServiceMsg.javaClass]
            ?: fromServiceMsg.javaClass.getMethod("getWupBuffer").also {
                wupBufferMethods[fromServiceMsg.javaClass] = it
            }
        val buffer = getWupBuffer.invoke(fromServiceMsg) as? ByteArray ?: return
        val (newGroupRkey, newPrivateRkey) = parseRkeys(unpackWupBuffer(buffer))
        groupRkey = newGroupRkey
        privateRkey = newPrivateRkey
        Log.debug("已更新 NT 图片 rkey")
    }

    private fun findFromServiceMsg(responseHolder: Any): Any? {
        val holderClass = responseHolder.javaClass
        val field = responseFields[holderClass] ?: generateSequence(holderClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull {
                it.name == "fromServiceMsg" ||
                    it.type.name == "com.tencent.qphone.base.remote.FromServiceMsg"
            }
            ?.apply { isAccessible = true }
            ?.also { responseFields[holderClass] = it }
            ?: return null
        return field.get(responseHolder)
    }

    private fun unpackWupBuffer(buffer: ByteArray): ByteArray =
        if (buffer.size >= 4 && buffer[0].toInt() == 0) {
            buffer.copyOfRange(4, buffer.size)
        } else {
            buffer
        }

    private fun parseRkeys(buffer: ByteArray): Pair<String, String> {
        val response = lengthDelimitedFields(buffer, 4).firstOrNull()
            ?: error("NT 图片 rkey 响应缺少字段 4")
        val downloadInfo = lengthDelimitedFields(response, 4).firstOrNull()
            ?: error("NT 图片 rkey 缺少下载信息")
        val entries = lengthDelimitedFields(downloadInfo, 1)
        check(entries.size >= 2) { "NT 图片 rkey 条目不足" }

        return readRkey(entries[0]) to readRkey(entries[1])
    }

    private fun readRkey(entry: ByteArray): String {
        val value = lengthDelimitedFields(entry, 1).firstOrNull()
            ?: error("NT 图片 rkey 值为空")
        return value.toString(Charsets.UTF_8)
            .trimEnd('\u0000')
            .takeIf { it.contains("rkey=") }
            ?: error("无效的 NT 图片 rkey")
    }

    private fun lengthDelimitedFields(data: ByteArray, fieldNumber: Int): List<ByteArray> {
        val values = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val tag = readVarint(data, offset)
            offset = tag.nextOffset
            check(tag.value != 0L) { "无效的 protobuf tag" }

            when ((tag.value and 7).toInt()) {
                0 -> offset = readVarint(data, offset).nextOffset
                1 -> offset = checkedOffset(offset, 8, data.size)
                2 -> {
                    val lengthValue = readVarint(data, offset)
                    offset = lengthValue.nextOffset
                    check(lengthValue.value <= Int.MAX_VALUE) { "protobuf 字段过大" }
                    val endOffset = checkedOffset(offset, lengthValue.value.toInt(), data.size)
                    if ((tag.value ushr 3).toInt() == fieldNumber) {
                        values += data.copyOfRange(offset, endOffset)
                    }
                    offset = endOffset
                }

                5 -> offset = checkedOffset(offset, 4, data.size)
                else -> error("不支持的 protobuf wire type: ${tag.value and 7}")
            }
        }
        return values
    }

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
