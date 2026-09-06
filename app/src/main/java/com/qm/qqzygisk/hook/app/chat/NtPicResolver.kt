package com.qm.qqzygisk.hook.app.chat

import java.io.File

/**
 * 从 QQ 9.3.55 的 PicElement 取出可预览/保存的图片来源。
 *
 * 新版会把本地缓存路径写进 originImageUrl / sourcePath；再按旧逻辑拼
 * `https://gchat.qpic.cn` 就会下到一页 JSON，保存面板只剩裂图。
 * 已下载的文件优先，NT `/download` 再补 rkey 和多媒体域名。
 */
internal object NtPicResolver {
    private const val NT_HOST = "https://multimedia.nt.qq.com.cn"
    private const val GCHAT_HOST = "https://gchat.qpic.cn"

    private val qqDataRoots = listOf(
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ",
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/files",
        "/data/user/0/com.tencent.mobileqq/files",
        "/data/data/com.tencent.mobileqq/files",
    )

    fun resolve(picElement: Any): List<String> =
        expand(
            originUrl = stringOf(picElement, "getOriginImageUrl", "originImageUrl"),
            sourcePath = stringOf(picElement, "getSourcePath", "sourcePath"),
            thumbPaths = thumbPaths(picElement),
            md5 = stringOf(picElement, "getMd5HexStr", "md5HexStr")
                ?: stringOf(picElement, "getOriginImageMd5", "originImageMd5"),
            emojiWebUrl = stringOf(picElement, "getEmojiWebUrl", "emojiWebUrl"),
            snapshot = NtImageRkeyProvider.snapshot(),
        )

    fun expand(
        originUrl: String?,
        sourcePath: String?,
        thumbPaths: List<String>,
        md5: String?,
        emojiWebUrl: String?,
        snapshot: RkeySnapshot?,
        fileExists: (String) -> Boolean = { File(it).isFile },
    ): List<String> {
        val sources = linkedSetOf<String>()
        localFiles(sourcePath, originUrl, thumbPaths, fileExists).forEach(sources::add)
        remoteUrls(originUrl, snapshot, fileExists).forEach(sources::add)
        emojiWebUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let(sources::add)
        md5?.takeIf { it.isNotBlank() && sources.none { candidate -> candidate.startsWith("http") } }
            ?.let { sources += "$GCHAT_HOST/gchatpic_new/0/0-0-${it.uppercase()}/0" }
        return sources.toList()
    }

    fun looksLikeLocalPath(path: String): Boolean {
        if (path.startsWith("file:")) return true
        if (path.contains("appid=") || path.contains("fileid=") || path.contains("rkey=")) {
            return false
        }
        if (path.startsWith("/download") || path.startsWith("download?")) return false
        if (path.startsWith("/gchatpic") || path.startsWith("/offpic") || path.startsWith("/qmeetpic")) {
            return false
        }
        return path.startsWith("/data/") ||
            path.startsWith("/storage/") ||
            path.startsWith("/sdcard") ||
            path.startsWith("/mnt/") ||
            path.contains("/Android/data/com.tencent.mobileqq") ||
            path.contains("/Tencent/MobileQQ") ||
            path.contains("/nt_data/") ||
            path.contains("/chatpic")
    }

    private fun localFiles(
        sourcePath: String?,
        originUrl: String?,
        thumbPaths: List<String>,
        fileExists: (String) -> Boolean,
    ): List<String> {
        val out = linkedSetOf<String>()
        listOfNotNull(sourcePath, originUrl).forEach { raw ->
            resolveExisting(raw, fileExists)?.let(out::add)
        }
        thumbPaths.forEach { raw ->
            resolveExisting(raw, fileExists)?.let(out::add)
        }
        return out.toList()
    }

    private fun resolveExisting(
        raw: String,
        fileExists: (String) -> Boolean,
    ): String? {
        val path = raw.removePrefix("file://")
        if (path.isBlank()) return null
        if (fileExists(path)) return path
        if (looksLikeLocalPath(path) && path.startsWith("/")) return null
        if (path.startsWith("/")) return null
        return qqDataRoots
            .map { root -> "$root/${path.trimStart('/')}" }
            .firstOrNull(fileExists)
    }

    private fun remoteUrls(
        originUrl: String?,
        snapshot: RkeySnapshot?,
        fileExists: (String) -> Boolean,
    ): List<String> {
        val raw = originUrl?.trim().orEmpty()
        if (raw.isEmpty() || looksLikeLocalPath(raw) || fileExists(raw)) return emptyList()
        val bases = when {
            raw.startsWith("https://") || raw.startsWith("http://") -> listOf(raw)
            raw.startsWith("/download") || raw.startsWith("download?") -> {
                val path = if (raw.startsWith("/")) raw else "/$raw"
                listOf("$NT_HOST$path", "$GCHAT_HOST$path")
            }
            raw.startsWith("/") -> listOf("$GCHAT_HOST$raw")
            else -> emptyList()
        }
        val out = linkedSetOf<String>()
        bases.forEach { base ->
            if (NtImageRkey.needsRkey(base)) {
                out.addAll(NtImageRkey.signedCandidates(base, snapshot))
            } else {
                out += base
            }
        }
        return out.toList()
    }

    private fun stringOf(instance: Any, vararg names: String): String? {
        names.forEach { name ->
            invoke(instance, name)?.let { value ->
                (value as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            }
            field(instance, name)?.let { value ->
                (value as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    private fun thumbPaths(instance: Any): List<String> {
        val raw = invoke(instance, "getThumbPath") ?: field(instance, "thumbPath")
        val map = raw as? Map<*, *> ?: return emptyList()
        return map.values.mapNotNull { value ->
            when (value) {
                is String -> value.takeIf { it.isNotBlank() }
                is File -> value.absolutePath
                else -> value?.toString()?.takeIf { it.isNotBlank() }
            }
        }
    }

    private fun invoke(instance: Any, name: String): Any? =
        runCatching {
            instance.javaClass.methods.firstOrNull { method ->
                method.name == name && method.parameterCount == 0
            }?.apply { isAccessible = true }?.invoke(instance)
        }.getOrNull()

    private fun field(instance: Any, name: String): Any? =
        runCatching {
            generateSequence(instance.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { it.name == name }
                ?.apply { isAccessible = true }
                ?.get(instance)
        }.getOrNull()
}
