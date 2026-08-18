package com.qm.qqzygisk.hook.app.chat

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 聊天长按保存图片的本地目录。
 * 子文件夹会作为表情面板分组，第一张图默认当封面。
 */
object ImageFolderStore {
    const val ROOT_PATH = "/storage/emulated/0/Android/media/com.tencent.mobileqq/.qqzygisk"
    private const val LAST_FOLDER_FILE = ".last_folder"
    private val namePattern = Regex("[\\/:*?\"<>|]")
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }


    fun root(): File = File(ROOT_PATH).apply { mkdirs() }

    fun folders(): List<File> =
        root().listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")

    fun images(folder: File): List<File> =
        folder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in imageExtensions }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun coverFile(folder: File): File? = images(folder).lastOrNull()

    fun lastFolder(): File? {
        val name = File(root(), LAST_FOLDER_FILE)
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            .orEmpty()
        return folders().firstOrNull { it.name == name } ?: folders().firstOrNull()
    }

    fun remember(folder: File) {
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
        ensureFolder(folder)
        val file = File(folder, "img_${System.currentTimeMillis()}.${normalizeExtension(extension)}")
        file.writeBytes(bytes)
        remember(folder)
        notifyChanged()
        return file
    }

    private fun ensureFolder(folder: File) {
        check(folder.isDirectory || folder.mkdirs()) { "无法使用文件夹: ${folder.absolutePath}" }
    }

    private fun sanitizeName(rawName: String): String =
        namePattern.replace(rawName.trim(), "_")
            .trim('.', ' ')

    private fun normalizeExtension(extension: String): String {
        val ext = extension.lowercase().removePrefix(".")
        return if (ext in setOf("png", "jpg", "jpeg", "gif", "webp")) ext else "jpg"
    }
}
