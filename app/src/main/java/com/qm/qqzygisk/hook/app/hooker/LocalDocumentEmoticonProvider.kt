package com.qm.qqzygisk.hook.app.hooker

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.utils.set
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

abstract class ExtraEmoticon {
    //    abstract fun emoticonId(): String
//    abstract fun emoticonName(): String
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

val executor: ExecutorService = Executors.newFixedThreadPool(2)
val allowedExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp")
var baseDirs = listOf(
    "/storage/self/primary/Android/media/com.tencent.mobileqq/.fun/Sticker/Storage/",
    "/storage/self/primary/Android/media/com.tencent.mobileqq/TGStickersExported/v1/"
)

fun listDir(directoryPath: String): List<FileInfo> {
    return File(directoryPath).listFiles()?.map { FileInfo(it.name, it.absolutePath) } ?: listOf()
}
fun listFile(directoryPath: String): List<FileInfo> {
    val file = File(directoryPath)
    return file.listFiles()
        ?.filter { !it.name.contains(".") || allowedExtensions.contains(it.name.substring(it.name.lastIndexOf("."))) }
        ?.map { FileInfo(it.name, it.absolutePath) } ?: listOf()
}

class LocalDocumentEmoticonProvider : ExtraEmoticonProvider() {
    class Panel(val path: String, val id: String) : ExtraEmoticonPanel() {
        private var emoticons: List<ExtraEmoticon> = listOf()
        private var iconPath: String? = null
        private fun updateEmoticons() {
            val files = listFile(path)

            val emoticons = mutableListOf<ExtraEmoticon>()
            val infoObj = "com.tencent.mobileqq.emoticonview.FavoriteEmoticonInfo".toAppClass().resolve().firstConstructor()
            for (file in files) {
                val filename = file.name
                if(filename.startsWith("__cover__.")) {
                    iconPath = file.fullPath
                    continue
                }

                if(filename.endsWith(".nomedia")) return
                if(filename.endsWith(".txt.jpg")) return

                emoticons.add(object : ExtraEmoticon() {
                    val info = infoObj.create()
                    init {
                        info.set("path", file.fullPath)

                        // for recent use sorting
                        info.set("actionData", "${uniqueId()}:${file.fullPath}")
                    }
                    override fun QQEmoticonObject(): Any {
                        return info
                    }
                })
            }
            this.emoticons = emoticons
            if (iconPath == null && files.isNotEmpty()) {
                iconPath = files[0].fullPath
            }
        }
        init {
            executor.execute {
                updateEmoticons()
            }
        }
        private var lastEmoticonUpdateTime = 0L
        override fun emoticons(): List<ExtraEmoticon> {
            if(System.currentTimeMillis() - lastEmoticonUpdateTime > 1000 * 5) {
                lastEmoticonUpdateTime = System.currentTimeMillis()
                executor.execute {
                    updateEmoticons()
                }
            }
            return emoticons
        }

        override fun emoticonPanelIconURL(): String? {
            return if(iconPath != null)  "file://$iconPath" else null
        }

        override fun uniqueId(): String {
            return id
        }
    }
    private val panelsMap = mutableMapOf<String, Panel?>()

    override fun extraEmoticonList(): List<ExtraEmoticonPanel> {
        val panels = mutableListOf<ExtraEmoticonPanel>()
        for (baseDir in baseDirs) {
            val files = listDir(baseDir)
            for (fileInfo in files) {
                val file = fileInfo.fullPath
                if (panelsMap.containsKey(file)) {
                    if (panelsMap[file] != null)
                        panels.add(panelsMap[file]!!)
                    continue
                }
                if(file.endsWith(".nomedia")) {
                    panelsMap[file] = null
                    continue
                }
                if(!File(file).isDirectory) {
                    panelsMap[file] = null
                    continue
                }
                if(listDir(file).isEmpty()) {
                    panelsMap[file] = null
                    continue
                }
                if (!panelsMap.containsKey(file)) {
                    val panel = Panel(file, fileInfo.name)
                    panelsMap[file] = panel
                }

                panels.add(panelsMap[file]!!)
            }
        }
        return panels
    }
    override fun uniqueId(): String {
        return "LocalDocumentEmoticonProvider"
    }
}
