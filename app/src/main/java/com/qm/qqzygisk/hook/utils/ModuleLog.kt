package com.qm.qqzygisk.hook.utils

import com.qm.qqzygisk.hook.app.chat.ImageFolderStore
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * 把模块日志写到 QQ 进程能写、用户也能打开的文件里。
 * 设置页、保存面板和 KernelSU WebUI 都读这里，不用再翻 logcat。
 */
object ModuleLog {
    const val FILE_NAME = "qhook.log"
    const val DEFAULT_MEDIA_DIR =
        "/storage/emulated/0/Android/media/com.tencent.mobileqq/.qhook"
    const val APP_FILES_LOG =
        "/data/user/0/com.tencent.mobileqq/files/qhook.log"

    private const val MAX_MEMORY_LINES = 300
    private const val MAX_FILE_BYTES = 256 * 1024
    private const val DEFAULT_TAIL = 200

    private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val memory = ArrayDeque<String>()
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "QHook-FileLog").apply { isDaemon = true }
    }

    @Volatile
    internal var pathOverride: List<String>? = null

    fun candidatePaths(): List<String> {
        pathOverride?.let { return it }
        return buildList {
            runCatching { add("${ImageFolderStore.ROOT_PATH}/$FILE_NAME") }
            add("$DEFAULT_MEDIA_DIR/$FILE_NAME")
            add(APP_FILES_LOG)
        }.distinct()
    }

    fun primaryPath(): String = candidatePaths().first()

    fun existingPaths(): List<String> =
        candidatePaths().filter { File(it).isFile }

    fun append(priority: String, msg: String, error: Throwable? = null) {
        val line = buildString {
            append(timeFormat.format(LocalDateTime.now()))
            append(' ')
            append(priority)
            append(' ')
            append(msg.replace('\n', ' '))
            if (error != null) {
                append(" | ")
                append(error.javaClass.simpleName)
                append(": ")
                append(error.message.orEmpty())
            }
        }
        synchronized(lock) {
            if (memory.size >= MAX_MEMORY_LINES) memory.removeFirst()
            memory.addLast(line)
        }
        if (pathOverride != null) {
            writeLine(line)
        } else {
            writer.execute { writeLine(line) }
        }
    }

    fun readTail(maxLines: Int = DEFAULT_TAIL): String {
        val fromFile = candidatePaths().firstNotNullOfOrNull { path ->
            val file = File(path)
            if (!file.isFile) return@firstNotNullOfOrNull null
            runCatching { tailLines(file, maxLines) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
        if (!fromFile.isNullOrBlank()) return fromFile
        synchronized(lock) {
            return memory.toList().takeLast(maxLines).joinToString("\n")
        }
    }

    fun clear() {
        synchronized(lock) { memory.clear() }
        candidatePaths().forEach { path ->
            runCatching { File(path).delete() }
        }
    }

    fun locationHint(): String {
        val existing = existingPaths()
        return if (existing.isNotEmpty()) {
            existing.joinToString("\n")
        } else {
            candidatePaths().joinToString("\n")
        }
    }

    private fun writeLine(line: String) {
        candidatePaths().forEach { path ->
            runCatching {
                val file = File(path)
                file.parentFile?.mkdirs()
                if (file.isFile && file.length() > MAX_FILE_BYTES) {
                    val keep = tailLines(file, DEFAULT_TAIL)
                    file.writeText(keep + "\n")
                }
                file.appendText(line + "\n")
            }
        }
    }

    private fun tailLines(file: File, maxLines: Int): String {
        val lines = file.readLines()
        return lines.takeLast(maxLines).joinToString("\n")
    }
}
