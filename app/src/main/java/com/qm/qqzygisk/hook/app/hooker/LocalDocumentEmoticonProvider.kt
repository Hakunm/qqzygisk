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
val baseDirs = listOf(
    "/storage/self/primary/Android/media/com.tencent.mobileqq/.fun/Sticker/Storage/",
    "/storage/self/primary/Android/media/com.tencent.mobileqq/TGStickersExported/v1/",
    ImageFolderStore.ROOT_PATH,
)

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
                if (filename.startsWith("__cover__.")) {
                    iconPath = file.fullPath
                    continue
                }
                if (filename.endsWith(".nomedia") || filename.endsWith(".txt.jpg")) continue
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
            if (iconPath == null) {
                iconPath = ImageFolderStore.coverFile(File(path))?.absolutePath
                    ?: files.firstOrNull()?.fullPath
            }
        }

        override fun emoticons(): List<ExtraEmoticon> {
            if (System.currentTimeMillis() - lastEmoticonUpdateTime > 1000) {
                lastEmoticonUpdateTime = System.currentTimeMillis()
                updateEmoticons()
            }
            return emoticons
        }

        override fun emoticonPanelIconURL(): String? {
            return if (iconPath != null) "file://$iconPath" else null
        }

        override fun uniqueId(): String = id
    }

    private val panelsMap = mutableMapOf<String, Panel>()

    fun invalidateCache() {
        panelsMap.values.forEach { it.invalidate() }
    }

    override fun extraEmoticonList(): List<ExtraEmoticonPanel> {
        val panels = mutableListOf<ExtraEmoticonPanel>()
        val seen = mutableSetOf<String>()
        val dirs = mutableListOf<File>()
        for (baseDir in baseDirs) {
            File(baseDir).listFiles()?.forEach { dirs.add(it) }
        }
        dirs.addAll(ImageFolderStore.folders())
        for (dir in dirs) {
            val path = dir.absolutePath
            if (!seen.add(path)) continue
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
        return panels
    }

    override fun uniqueId(): String = "LocalDocumentEmoticonProvider"
}
