package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.utils.HookSettings
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 聊天长按保存图片的本地目录。
 * 子文件夹会作为表情面板分组，第一张图默认当封面。
 * 浏览时还会带上模块内置贴纸目录。
 */
object ImageFolderStore {
    const val QQ_ZYGISK_PATH_KEY = "qq_zygisk_image_path"
    const val FUNBOX_PATH_KEY = "funbox_emoticon_path"
    const val TG_STICKERS_PATH_KEY = "tg_stickers_emoticon_path"
    const val DEFAULT_QQ_ZYGISK_PATH =
        "/storage/emulated/0/Android/media/com.tencent.mobileqq/.qqzygisk"
    const val DEFAULT_FUNBOX_PATH =
        "/storage/self/primary/Android/media/com.tencent.mobileqq/.fun/Sticker/Storage"
    const val DEFAULT_TG_STICKERS_PATH =
        "/storage/self/primary/Android/media/com.tencent.mobileqq/TGStickersExported/v1"
    val ROOT_PATH: String
        get() = configuredPath(QQ_ZYGISK_PATH_KEY, DEFAULT_QQ_ZYGISK_PATH)
    val SCAN_ROOTS: List<String>
        get() = listOf(
            configuredPath(FUNBOX_PATH_KEY, DEFAULT_FUNBOX_PATH),
            configuredPath(TG_STICKERS_PATH_KEY, DEFAULT_TG_STICKERS_PATH),
            ROOT_PATH,
        ).distinct()
    private const val LAST_FOLDER_FILE = ".last_folder"
    private val namePattern = Regex("[\\/:*?\"<>|]")
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    fun root(): File = File(ROOT_PATH).apply { mkdirs() }

    fun isOwned(folder: File): Boolean =
        folder.absolutePath.startsWith(root().absolutePath + "/")

    fun folders(includeExternal: Boolean = false): List<File> {
        val owned = listChildFolders(root())
        if (!includeExternal) return owned.sortedBy { it.name.lowercase() }
        val extra = SCAN_ROOTS
            .filter { it != ROOT_PATH }
            .flatMap { listChildFolders(File(it)) }
        return (owned + extra)
            .distinctBy { it.absolutePath }
            .sortedBy { it.name.lowercase() }
    }

    fun images(folder: File): List<File> =
        folder.listFiles()
            ?.filter { it.isFile && isImageFile(it) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun coverFile(folder: File): File? =
        folder.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && isImageFile(it) }
            ?.minByOrNull { it.lastModified() }

    fun lastFolder(includeExternal: Boolean = false): File? {
        val available = folders(includeExternal)
        val name = File(root(), LAST_FOLDER_FILE)
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            .orEmpty()
        return available.firstOrNull { it.name == name } ?: available.firstOrNull()
    }

    fun remember(folder: File) {
        if (!isOwned(folder)) return
        File(root(), LAST_FOLDER_FILE).writeText(folder.name)
    }

    fun createFolder(rawName: String): File {
        val name = sanitizeName(rawName)
        check(name.isNotEmpty()) { "文件夹名无效" }
        val folder = File(root(), name)
        check(!folder.exists()) { "文件夹已存在: $name" }
        check(folder.mkdirs()) { "无法创建文件夹: $name" }
        remember(folder)
        notifyChanged()
        return folder
    }

    fun saveImage(folder: File, bytes: ByteArray, extension: String): File {
        check(isOwned(folder)) { "只能保存到模块自己的文件夹" }
        ensureFolder(folder)
        val file = File(folder, "${md5(bytes)}.${normalizeExtension(extension)}")
        val created = !file.exists()
        if (created) file.writeBytes(bytes)
        remember(folder)
        if (created) notifyChanged()
        return file
    }

    private fun configuredPath(key: String, defaultValue: String): String =
        HookSettings.getString(key, defaultValue).trim().ifEmpty { defaultValue }

    private fun listChildFolders(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            .orEmpty()
            .toList()

    private fun isImageFile(file: File): Boolean {
        val name = file.name
        if (name.startsWith(".") || name.startsWith("__cover__.") || name.endsWith(".txt.jpg")) {
            return false
        }
        val ext = file.extension.lowercase()
        return ext.isEmpty() || ext in imageExtensions
    }

    private fun ensureFolder(folder: File) {
        check(folder.isDirectory || folder.mkdirs()) { "无法使用文件夹: ${folder.absolutePath}" }
    }

    private fun sanitizeName(rawName: String): String =
        namePattern.replace(rawName.trim(), "_")
            .trim('.', ' ')

    private fun normalizeExtension(extension: String): String {
        val ext = extension.lowercase().removePrefix(".")
        return if (ext in imageExtensions) ext else "jpg"
    }

    private fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }
}
