package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.utils.Log
import java.io.File
import java.lang.reflect.Modifier

/**
 * 从 QQ 9.3.55 的 PicElement 取出可预览/保存的图片来源。
 *
 * 新版会把本地缓存路径写进 originImageUrl / sourcePath；再按旧逻辑拼
 * `https://gchat.qpic.cn` 就会下到一页 JSON，保存面板只剩裂图。
 * 已下载的文件优先，再问内核路径和本地缓存，NT `/download` 补 rkey、spec=0。
 */
internal object NtPicResolver {
    private const val NT_HOST = "https://multimedia.nt.qq.com.cn"
    private const val GCHAT_HOST = "https://gchat.qpic.cn"
    private val extraUrlPattern = Regex(
        """https?://[^\s\u0000\"'<>]+|/download\?[^\s\u0000\"'<>]+""",
    )
    private val extraRkeyPattern = Regex("""rkey=[^\s\u0000&\"']+""")

    private val qqDataRoots = listOf(
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ",
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/files",
        "/data/user/0/com.tencent.mobileqq/files",
        "/data/data/com.tencent.mobileqq/files",
    )

    fun resolve(picElement: Any): List<String> {
        val originUrl = stringOf(picElement, "getOriginImageUrl", "originImageUrl")
        val sourcePath = stringOf(picElement, "getSourcePath", "sourcePath")
        val md5 = stringOf(picElement, "getMd5HexStr", "md5HexStr")
            ?: stringOf(picElement, "getOriginImageMd5", "originImageMd5")
        val fileName = stringOf(picElement, "getFileName", "fileName")
        val fileUuid = stringOf(picElement, "getFileUuid", "fileUuid")
        val extraBytes = listOfNotNull(
            bytesOf(picElement, "picElemExtraData", "getPicElemExtraData"),
            bytesOf(picElement, "importRichMediaContext", "getImportRichMediaContext"),
        )
        val extraUrls = extraBytes.flatMap(::extractUrls)
        val extraRkeys = extraBytes.flatMap(::extractRkeys)
        val snapshot = mergeRkeys(NtImageRkeyProvider.snapshot(), extraRkeys)
        val extraLocals = buildList {
            runCatching { addAll(NtKernelPicPath.assemble(picElement)) }
                .onFailure { Log.warn("内核拼路径失败", it) }
            runCatching { addAll(NtPicCache.find(md5, fileName, fileUuid)) }
                .onFailure { Log.warn("扫描本地图片缓存失败", it) }
        }
        return expand(
            originUrl = originUrl,
            sourcePath = sourcePath,
            thumbPaths = thumbPaths(picElement),
            md5 = md5,
            emojiWebUrl = stringOf(picElement, "getEmojiWebUrl", "emojiWebUrl"),
            snapshot = snapshot,
            extraLocals = extraLocals,
            fileUuid = fileUuid,
            extraUrls = extraUrls,
        )
    }

    fun expand(
        originUrl: String?,
        sourcePath: String?,
        thumbPaths: List<String>,
        md5: String?,
        emojiWebUrl: String?,
        snapshot: RkeySnapshot?,
        fileExists: (String) -> Boolean = { File(it).isFile },
        extraLocals: List<String> = emptyList(),
        fileUuid: String? = null,
        extraUrls: List<String> = emptyList(),
    ): List<String> {
        val sources = linkedSetOf<String>()
        extraLocals.filter(fileExists).forEach(sources::add)
        localFiles(sourcePath, originUrl, thumbPaths, fileExists).forEach(sources::add)
        val remotes = buildList {
            addAll(remoteUrls(originUrl, snapshot, fileExists))
            extraUrls.forEach { raw ->
                addAll(remoteUrls(raw, snapshot, fileExists))
            }
            fileUuidUrls(fileUuid, originUrl, extraUrls).forEach { raw ->
                addAll(remoteUrls(raw, snapshot, fileExists))
            }
        }
        remotes.forEach(sources::add)
        emojiWebUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let(sources::add)
        if (sources.isEmpty()) {
            md5?.takeIf { it.isNotBlank() }?.let {
                sources += "$GCHAT_HOST/gchatpic_new/0/0-0-${it.uppercase()}/0"
            }
        }
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

    fun extractUrls(data: ByteArray?): List<String> {
        if (data == null || data.isEmpty()) return emptyList()
        val found = linkedSetOf<String>()
        listOf(data.toString(Charsets.UTF_8), data.toString(Charsets.ISO_8859_1)).forEach { text ->
            extraUrlPattern.findAll(text).forEach { match ->
                found += match.value.trimEnd(',', ';', ')', ']', '"', '\'')
            }
        }
        return found.toList()
    }

    fun extractRkeys(data: ByteArray?): List<String> {
        if (data == null || data.isEmpty()) return emptyList()
        val found = linkedSetOf<String>()
        listOf(data.toString(Charsets.UTF_8), data.toString(Charsets.ISO_8859_1)).forEach { text ->
            extraRkeyPattern.findAll(text).forEach { match ->
                found += match.value
            }
        }
        return found.toList()
    }

    fun specVariants(url: String): List<String> {
        if (!url.contains("/download") && !url.contains("fileid=")) return listOf(url)
        if (url.contains("spec=")) return listOf(url)
        val separator = if (url.contains('?')) "&" else "?"
        return listOf(url, "$url${separator}spec=0")
    }

    fun describe(picElement: Any): String {
        val type = picElement.javaClass
        val parts = mutableListOf("class=${type.name}")
        generateSequence(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { Modifier.isPublic(it.modifiers) }
            .forEach { field ->
                field.isAccessible = true
                val value = runCatching { field.get(picElement) }.getOrNull()
                val shown = when (value) {
                    null -> "null"
                    is ByteArray -> {
                        val preview = value.toString(Charsets.ISO_8859_1)
                            .replace('\n', ' ')
                            .take(80)
                        "bytes[${value.size}] $preview"
                    }
                    is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") {
                        "${it.key}=${it.value}"
                    }
                    else -> value.toString().replace('\n', ' ').take(180)
                }
                parts += "${field.name}=$shown"
            }
        return parts.joinToString(" ")
    }

    private fun fileUuidUrls(
        fileUuid: String?,
        originUrl: String?,
        extraUrls: List<String>,
    ): List<String> {
        val id = fileUuid?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("/") } ?: return emptyList()
        val already = listOfNotNull(originUrl) + extraUrls
        if (already.any { it.contains(id) }) return emptyList()
        return listOf(
            "$NT_HOST/download?appid=1406&fileid=$id&spec=0",
            "$NT_HOST/download?appid=1407&fileid=$id&spec=0",
        )
    }

    private fun mergeRkeys(snapshot: RkeySnapshot?, extra: List<String>): RkeySnapshot? {
        if (extra.isEmpty()) return snapshot
        val byType = snapshot?.byType?.toMutableMap() ?: mutableMapOf()
        extra.forEachIndexed { index, value ->
            val type = when (index) {
                0 -> NtImageRkey.TYPE_PRIVATE
                1 -> NtImageRkey.TYPE_GROUP
                else -> return@forEachIndexed
            }
            byType.putIfAbsent(type, if (value.startsWith("rkey=")) "&$value" else value)
        }
        val expires = snapshot?.expiresAtMillis ?: (System.currentTimeMillis() + NtImageRkey.DEFAULT_TTL_MS)
        return RkeySnapshot(byType, expires)
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
        val bases = normalizeRemote(raw).flatMap(::specVariants)
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

    internal fun normalizeRemote(raw: String): List<String> {
        val value = raw.trim()
        return when {
            value.startsWith("https://") || value.startsWith("http://") -> listOf(value)
            value.startsWith("//") -> listOf("https:$value")
            value.startsWith("/download") || value.startsWith("download?") -> {
                val path = if (value.startsWith("/")) value else "/$value"
                listOf("$NT_HOST$path", "$GCHAT_HOST$path")
            }
            looksLikeLocalPath(value) -> emptyList()
            value.contains("multimedia.nt.qq.com.cn") || value.contains(".nt.qq.com.cn") ->
                listOf(if (value.startsWith("http")) value else "https://$value")
            value.contains("qpic.cn") ->
                listOf(if (value.startsWith("http")) value else "https://$value")
            value.startsWith("/") -> listOf("$GCHAT_HOST$value")
            else -> emptyList()
        }
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

    private fun bytesOf(instance: Any, vararg names: String): ByteArray? {
        names.forEach { name ->
            (invoke(instance, name) as? ByteArray)?.takeIf { it.isNotEmpty() }?.let { return it }
            (field(instance, name) as? ByteArray)?.takeIf { it.isNotEmpty() }?.let { return it }
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
