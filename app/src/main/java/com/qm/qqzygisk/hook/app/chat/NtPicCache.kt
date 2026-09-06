package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.utils.ImagePayload
import java.io.File

/**
 * 按 MD5 / 文件名在 QQ 本地缓存里找已经下载好的图。
 * 旧版 chatpic 用 CRC64 算 Cache_ 路径；NT 则扫 nt_data。
 */
internal object NtPicCache {
    private val chatPicRoots = listOf(
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic",
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ",
    )
    private val walkRoots = listOf(
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic",
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/nt_data",
        "/data/user/0/com.tencent.mobileqq/files/nt_data",
        "/data/user/0/com.tencent.mobileqq/files/nt_pic",
    )
    private val folders = listOf("chatraw", "chatimg", "chatthumb")
    private const val CRC64_POLY = -7661587058870466123L
    private const val MAX_WALK_FILES = 400

    private val crcTable: LongArray by lazy {
        LongArray(256) { index ->
            var value = index.toLong()
            repeat(8) {
                value = if (value and 1L != 0L) {
                    (value shr 1) xor CRC64_POLY
                } else {
                    value shr 1
                }
            }
            value
        }
    }

    fun find(
        md5: String?,
        fileName: String?,
        fileUuid: String?,
        extraRoots: List<String> = emptyList(),
        fileExists: (String) -> Boolean = { File(it).isFile },
    ): List<String> {
        val out = linkedSetOf<String>()
        predictedPaths(md5, extraRoots).forEach { path ->
            if (fileExists(path)) out += path
        }
        if (out.isNotEmpty()) return out.toList()
        val tokens = listOfNotNull(md5, fileName, fileUuid)
            .map { it.trim() }
            .filter { it.length >= 6 }
        if (tokens.isEmpty()) return emptyList()
        walkHits(tokens, extraRoots).forEach(out::add)
        return out.toList()
    }

    fun predictedPaths(md5: String?, extraRoots: List<String> = emptyList()): List<String> {
        val hex = md5?.trim()?.takeIf { it.length >= 8 } ?: return emptyList()
        val variants = linkedSetOf(hex.uppercase(), hex.lowercase())
        val roots = buildList {
            addAll(chatPicRoots)
            addAll(extraRoots)
            runCatching { NtMsgAccess.selfUin() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { uin ->
                add("/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/$uin")
            }
        }.distinct()
        val out = linkedSetOf<String>()
        variants.forEach { value ->
            folders.forEach { folder ->
                val relative = cacheRelativePath(folder, value)
                roots.forEach { root ->
                    out += "$root/$relative"
                    out += "$root/chatpic/$relative"
                }
            }
        }
        return out.toList()
    }

    fun cacheRelativePath(folder: String, md5: String): String {
        val name = cacheFileName(folder, md5)
        val dir = name.takeLast(3)
        return "$folder/$dir/$name"
    }

    fun cacheFileName(folder: String, md5: String): String {
        val crc = crc64("$folder:$md5")
        return "Cache_" + java.lang.Long.toHexString(crc).trimStart('0').ifEmpty { "0" }
    }

    fun crc64(text: String): Long {
        var value = -1L
        text.forEach { ch ->
            val index = ((ch.code.toLong() xor value) and 255L).toInt()
            value = crcTable[index] xor (value shr 8)
        }
        return value
    }

    private fun walkHits(tokens: List<String>, extraRoots: List<String>): List<String> {
        val needles = tokens.map { it.lowercase() }
        val roots = (walkRoots + extraRoots).map(::File).filter { it.isDirectory }
        val hits = linkedSetOf<String>()
        var scanned = 0
        val queue = ArrayDeque<File>()
        roots.forEach(queue::add)
        while (queue.isNotEmpty() && scanned < MAX_WALK_FILES && hits.size < 6) {
            val dir = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                scanned++
                if (scanned > MAX_WALK_FILES) break
                if (child.isDirectory) {
                    queue.add(child)
                    continue
                }
                if (!child.isFile || child.length() < 32L) continue
                val haystack = child.absolutePath.lowercase()
                if (needles.none { it in haystack }) continue
                if (isImageFile(child)) hits += child.absolutePath
            }
        }
        return hits.toList()
    }

    private fun isImageFile(file: File): Boolean =
        runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(16)
                val read = input.read(header)
                read >= 12 && ImagePayload.isImage(header.copyOf(read.coerceAtLeast(12)))
            }
        }.getOrDefault(false)
}
