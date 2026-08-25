package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.utils.HookSettings
import org.json.JSONObject
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
        "/storage/emulated/0/Android/media/com.tencent.mobileqq/.qhook"
    const val DEFAULT_FUNBOX_PATH =
        "/storage/self/primary/Android/media/com.tencent.mobileqq/.fun/Sticker/Storage"
    const val DEFAULT_TG_STICKERS_PATH =
        "/storage/self/primary/Android/media/com.tencent.mobileqq/TGStickersExported/v1"
    val ROOT_PATH: String
        get() = configuredPath(QQ_ZYGISK_PATH_KEY, DEFAULT_QQ_ZYGISK_PATH)
    val SCAN_ROOTS: List<String>
        get() = buildList {
            add(configuredPath(FUNBOX_PATH_KEY, DEFAULT_FUNBOX_PATH))
            add(configuredPath(TG_STICKERS_PATH_KEY, DEFAULT_TG_STICKERS_PATH))
            add(ROOT_PATH)
            val legacy = File(LEGACY_LOCAL_PATH)
            if (legacy.isDirectory && LEGACY_LOCAL_PATH != ROOT_PATH) {
                add(LEGACY_LOCAL_PATH)
            }
        }.distinct()
    private const val LEGACY_LOCAL_PATH =
        "/storage/emulated/0/Android/media/com.tencent.mobileqq/.qqzygisk"
    private const val LAST_FOLDER_FILE = ".last_folder"
    private const val LAST_FOLDER_KEY = "emoticon_panel_last_folder"
    private const val USAGE_FILE = ".usage.json"
    const val HISTORY_DIR_NAME = "__history__"
    private const val HISTORY_LIMIT = 80
    private val usageLock = Any()
    @Volatile
    private var usageCache: UsageStore? = null
    private val namePattern = Regex("[\\/:*?\"<>|]")
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")
    @Volatile
    private var funBoxNameCache = FunBoxNameCache()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    fun root(): File = File(ROOT_PATH).apply { mkdirs() }

    fun isOwned(folder: File): Boolean =
        folder.absolutePath.startsWith(root().absolutePath + "/")

    fun historyFolder(): File = File(root(), HISTORY_DIR_NAME)

    fun isHistoryFolder(folder: File): Boolean = folder.name == HISTORY_DIR_NAME

    fun folders(includeExternal: Boolean = false): List<File> {
        val owned = listChildFolders(root())
        val extra = if (includeExternal) {
            SCAN_ROOTS
                .filter { it != ROOT_PATH }
                .flatMap { listChildFolders(File(it)) }
        } else {
            emptyList()
        }
        val real = (owned + extra)
            .distinctBy { it.absolutePath }
            .filter { !isHistoryFolder(it) }
            .sortedWith(
                compareByDescending<File> { folderUsage(it) }
                    .thenBy { displayName(it).lowercase() },
            )
        return if (includeExternal) listOf(historyFolder()) + real else real
    }

    fun images(folder: File): List<File> {
        if (isHistoryFolder(folder)) return historyImages()
        return folder.listFiles()
            ?.filter { it.isFile && isImageFile(it) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun coverFile(folder: File): File? {
        if (isHistoryFolder(folder)) return historyImages().firstOrNull()
        return folder.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && isImageFile(it) }
            ?.minByOrNull { it.lastModified() }
    }

    fun displayName(folder: File): String {
        if (isHistoryFolder(folder)) return "历史"
        val storagePath = configuredPath(FUNBOX_PATH_KEY, DEFAULT_FUNBOX_PATH)
        if (folder.parentFile?.absolutePath != File(storagePath).absolutePath) return folder.name
        return funBoxPackNames(storagePath)[folder.name]?.takeIf { it.isNotBlank() } ?: folder.name
    }

    fun recordUsage(file: File) {
        if (!file.isFile || !isImageFile(file)) return
        val now = System.currentTimeMillis()
        synchronized(usageLock) {
            val store = loadUsageLocked()
            bump(store.files, file.absolutePath, now)
            val parent = file.parentFile
            if (parent != null && !isHistoryFolder(parent)) {
                bump(store.folders, parent.absolutePath, now)
            }
            saveUsageLocked(store)
        }
    }

    fun folderUsage(folder: File): Int {
        if (isHistoryFolder(folder)) return 0
        return synchronized(usageLock) {
            loadUsageLocked().folders[folder.absolutePath]?.count ?: 0
        }
    }

    fun lastFolder(includeExternal: Boolean = false): File? {
        val available = folders(includeExternal)
        val saved = HookSettings.getString(LAST_FOLDER_KEY, "").ifBlank {
            File(root(), LAST_FOLDER_FILE)
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                .orEmpty()
        }
        if (saved.isEmpty()) return available.firstOrNull()
        return available.firstOrNull { it.absolutePath == saved }
            ?: available.firstOrNull { it.name == saved }
            ?: available.firstOrNull()
    }

    fun remember(folder: File) {
        HookSettings.setString(LAST_FOLDER_KEY, folder.absolutePath)
        runCatching {
            File(root(), LAST_FOLDER_FILE).apply {
                parentFile?.mkdirs()
                writeText(folder.absolutePath)
            }
        }
    }

    fun createFolder(rawName: String): File {
        val name = sanitizeName(rawName)
        check(name.isNotEmpty() && name != HISTORY_DIR_NAME && name != "历史") { "文件夹名无效" }
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

    fun deleteImage(file: File) {
        check(file.isFile && isImageFile(file)) { "不是可删除的表情文件" }
        check(file.delete()) { "无法删除表情" }
        synchronized(usageLock) {
            val store = loadUsageLocked()
            store.files.remove(file.absolutePath)
            saveUsageLocked(store)
        }
        notifyChanged()
    }

    private fun configuredPath(key: String, defaultValue: String): String =
        HookSettings.getString(key, defaultValue).trim().ifEmpty { defaultValue }

    private fun listChildFolders(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != HISTORY_DIR_NAME }
            .orEmpty()
            .toList()

    private fun historyImages(): List<File> {
        val entries = synchronized(usageLock) { loadUsageLocked().files.entries.toList() }
        return entries
            .sortedWith(
                compareByDescending<Map.Entry<String, UsageEntry>> { it.value.count }
                    .thenByDescending { it.value.lastUsed },
            )
            .map { File(it.key) }
            .filter { it.isFile && isImageFile(it) }
            .take(HISTORY_LIMIT)
    }

    private fun bump(map: MutableMap<String, UsageEntry>, key: String, now: Long) {
        val current = map[key]
        if (current == null) {
            map[key] = UsageEntry(1, now)
        } else {
            current.count += 1
            current.lastUsed = now
        }
    }

    private fun usageFile(): File = File(root(), USAGE_FILE)

    private fun loadUsageLocked(): UsageStore {
        usageCache?.let { return it }
        val parsed = runCatching {
            val raw = usageFile().takeIf { it.isFile }?.readText().orEmpty()
            if (raw.isBlank()) return@runCatching UsageStore()
            val json = JSONObject(raw)
            UsageStore(
                files = readUsageMap(json.optJSONObject("files")),
                folders = readUsageMap(json.optJSONObject("folders")),
            )
        }.getOrDefault(UsageStore())
        usageCache = parsed
        return parsed
    }

    private fun saveUsageLocked(store: UsageStore) {
        usageCache = store
        val json = JSONObject()
            .put("files", writeUsageMap(store.files))
            .put("folders", writeUsageMap(store.folders))
        val file = usageFile()
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
    }

    private fun readUsageMap(obj: JSONObject?): MutableMap<String, UsageEntry> {
        if (obj == null) return mutableMapOf()
        val result = mutableMapOf<String, UsageEntry>()
        obj.keys().forEach { key ->
            val item = obj.optJSONObject(key) ?: return@forEach
            result[key] = UsageEntry(
                count = item.optInt("c"),
                lastUsed = item.optLong("t"),
            )
        }
        return result
    }

    private fun writeUsageMap(map: Map<String, UsageEntry>): JSONObject {
        val obj = JSONObject()
        map.forEach { (key, entry) ->
            obj.put(
                key,
                JSONObject().put("c", entry.count).put("t", entry.lastUsed),
            )
        }
        return obj
    }

    private fun funBoxPackNames(storagePath: String): Map<String, String> {
        val packsFile = findFunBoxPacksFile(storagePath) ?: return emptyMap()
        val cached = funBoxNameCache
        if (
            cached.path == packsFile.absolutePath &&
            cached.lastModified == packsFile.lastModified() &&
            cached.length == packsFile.length()
        ) {
            return cached.names
        }

        val names = runCatching {
            val list = JSONObject(packsFile.readText()).optJSONArray("list")
                ?: return@runCatching emptyMap()
            buildMap {
                for (index in 0 until list.length()) {
                    val pack = list.optJSONObject(index) ?: continue
                    val id = pack.optString("id").trim()
                    val name = pack.optString("name").trim()
                    if (id.isNotEmpty() && name.isNotEmpty()) put(id, name)
                }
            }
        }.getOrDefault(emptyMap())
        funBoxNameCache = FunBoxNameCache(
            path = packsFile.absolutePath,
            lastModified = packsFile.lastModified(),
            length = packsFile.length(),
            names = names,
        )
        return names
    }

    private fun findFunBoxPacksFile(storagePath: String): File? {
        val stickerRoot = File(storagePath).parentFile ?: return null
        val direct = File(stickerRoot, "packs")
        if (direct.isFile) return direct
        return stickerRoot.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.map { File(it, "packs") }
            ?.firstOrNull(File::isFile)
    }

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

    private data class UsageEntry(var count: Int, var lastUsed: Long)

    private data class UsageStore(
        val files: MutableMap<String, UsageEntry> = mutableMapOf(),
        val folders: MutableMap<String, UsageEntry> = mutableMapOf(),
    )

    private data class FunBoxNameCache(
        val path: String = "",
        val lastModified: Long = Long.MIN_VALUE,
        val length: Long = Long.MIN_VALUE,
        val names: Map<String, String> = emptyMap(),
    )
}
