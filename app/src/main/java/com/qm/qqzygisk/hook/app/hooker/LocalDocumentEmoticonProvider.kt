package com.qm.qqzygisk.hook.app.hooker

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.chat.ImageFolderStore
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.utils.set
import java.io.File

abstract class ExtraEmoticon {
    abstract fun QQEmoticonObject(): Any
}

abstract class ExtraEmoticonPanel {
    abstract fun emoticons(): List<ExtraEmoticon>
    abstract fun emoticonPanelIconURL(): String?
    abstract fun uniqueId(): String
}

abstract class ExtraEmoticonProvider {
    abstract fun extraEmoticonList(): List<ExtraEmoticonPanel>
    abstract fun uniqueId(): String
}

data class FileInfo(val name: String, val fullPath: String)

val allowedExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp")

fun listDir(directoryPath: String): List<FileInfo> {
    return File(directoryPath).listFiles()?.map { FileInfo(it.name, it.absolutePath) } ?: listOf()
}

fun listFile(directoryPath: String): List<FileInfo> {
    val file = File(directoryPath)
    return file.listFiles()
        ?.filter {
            !it.name.contains(".") ||
                allowedExtensions.contains(it.name.substring(it.name.lastIndexOf(".")))
        }
        ?.map { FileInfo(it.name, it.absolutePath) } ?: listOf()
}

class LocalDocumentEmoticonProvider : ExtraEmoticonProvider() {
    class Panel(val path: String, val id: String) : ExtraEmoticonPanel() {
        private var emoticons: List<ExtraEmoticon> = listOf()
        private var iconPath: String? = null
        private var lastEmoticonUpdateTime = 0L

        fun invalidate() {
            lastEmoticonUpdateTime = 0L
            iconPath = null
        }

        private fun updateEmoticons() {
            val files = listFile(path)
            val next = mutableListOf<ExtraEmoticon>()
            val infoObj = "com.tencent.mobileqq.emoticonview.FavoriteEmoticonInfo"
                .toAppClass()
                .resolve()
                .firstConstructor()
            for (file in files) {
                val filename = file.name
                if (!isEmoticonFile(filename)) continue
                next.add(
                    object : ExtraEmoticon() {
                        val info = infoObj.create()
                        init {
                            info.set("path", file.fullPath)
                            info.set("actionData", "${uniqueId()}:${file.fullPath}")
                        }
                        override fun QQEmoticonObject(): Any = info
                    },
                )
            }
            emoticons = next
            iconPath = resolveIconPath(files)
        }

        override fun emoticons(): List<ExtraEmoticon> {
            if (System.currentTimeMillis() - lastEmoticonUpdateTime > 1000) {
                lastEmoticonUpdateTime = System.currentTimeMillis()
                updateEmoticons()
            }
            return emoticons
        }

        override fun emoticonPanelIconURL(): String? {
            val cachedPath = iconPath?.takeIf { File(it).isFile }
            val resolvedPath = cachedPath ?: resolveIconPath(listFile(path)).also {
                iconPath = it
            }
            return resolvedPath?.let { "file://$it" }
        }

        override fun uniqueId(): String = id

        private fun resolveIconPath(files: List<FileInfo>): String? =
            files.firstOrNull { it.name.startsWith("__cover__.") }?.fullPath
                ?: ImageFolderStore.coverFile(File(path))?.absolutePath
                ?: files.firstOrNull { isEmoticonFile(it.name) }?.fullPath

        private fun isEmoticonFile(filename: String): Boolean =
            !filename.startsWith("__cover__.") &&
                !filename.endsWith(".nomedia") &&
                !filename.endsWith(".txt.jpg")
    }

    private class HistoryPanel : ExtraEmoticonPanel() {
        override fun uniqueId(): String = ImageFolderStore.HISTORY_DIR_NAME

        override fun emoticons(): List<ExtraEmoticon> {
            val infoObj = "com.tencent.mobileqq.emoticonview.FavoriteEmoticonInfo"
                .toAppClass()
                .resolve()
                .firstConstructor()
            return ImageFolderStore.images(ImageFolderStore.historyFolder()).map { file ->
                object : ExtraEmoticon() {
                    val info = infoObj.create()
                    init {
                        info.set("path", file.absolutePath)
                        info.set("actionData", "${uniqueId()}:${file.absolutePath}")
                    }
                    override fun QQEmoticonObject(): Any = info
                }
            }
        }

        override fun emoticonPanelIconURL(): String? = null
    }

    private val panelsMap = mutableMapOf<String, Panel>()
    private val historyPanel = HistoryPanel()

    fun invalidateCache() {
        panelsMap.values.forEach { it.invalidate() }
    }

    override fun extraEmoticonList(): List<ExtraEmoticonPanel> {
        val panels = mutableListOf<ExtraEmoticonPanel>()
        val seen = mutableSetOf<String>()
        val dirs = mutableListOf<File>()
        for (baseDir in ImageFolderStore.SCAN_ROOTS) {
            File(baseDir).listFiles()?.forEach { dirs.add(it) }
        }
        dirs.addAll(ImageFolderStore.folders())
        for (dir in dirs) {
            val path = dir.absolutePath
            if (!seen.add(path)) continue
            if (ImageFolderStore.isHistoryFolder(dir)) continue
            if (!dir.isDirectory || dir.name.startsWith(".")) continue
            val existing = panelsMap[path]
            if (existing != null) {
                panels.add(existing)
                continue
            }
            val hasImages = ImageFolderStore.images(dir).isNotEmpty() || listFile(path).isNotEmpty()
            if (!hasImages) continue
            val panel = Panel(path, dir.name)
            panelsMap[path] = panel
            panels.add(panel)
        }
        val sorted = panels.sortedByDescending { panel ->
            val path = (panel as? Panel)?.path ?: return@sortedByDescending 0
            ImageFolderStore.folderUsage(File(path))
        }
        return listOf(historyPanel) + sorted
    }

    override fun uniqueId(): String = "LocalDocumentEmoticonProvider"
}
