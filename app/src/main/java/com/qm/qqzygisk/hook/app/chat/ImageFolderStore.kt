package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 聊天长按保存图片的本地目录。
 * 子文件夹会作为表情面板分组，第一张图默认当封面。
 *
 * 打开面板只读本地 `.qhook` 目录；FunBox / TG 导入放到后台，避免主线程卡顿。
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
    val IMPORT_ROOTS: List<String>
        get() = listOf(
            configuredPath(FUNBOX_PATH_KEY, DEFAULT_FUNBOX_PATH),
            configuredPath(TG_STICKERS_PATH_KEY, DEFAULT_TG_STICKERS_PATH),
        ).distinct()
    val SCAN_ROOTS: List<String>
        get() = buildList {
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
    private const val IMPORT_INDEX_FILE = ".import_index.json"
    private const val IMPORT_CHECK_INTERVAL_MS = 30_000L
    private val importLock = Any()
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
    @Volatile
    private var ownedFolderCache: CachedFolders? = null
    private val imageListCache = ConcurrentHashMap<String, CachedImages>()
    @Volatile
    private var lastImportCheckAt = 0L
    private val importRunning = AtomicBoolean(false)
    private val importExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "qh-import").apply { isDaemon = true }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        invalidateListingCaches()
        listeners.forEach { it() }
    }

    fun root(): File = File(ROOT_PATH).apply { mkdirs() }

    fun isOwned(folder: File): Boolean =
        folder.absolutePath.startsWith(root().absolutePath + "/")

    fun historyFolder(): File = File(root(), HISTORY_DIR_NAME)

    fun isHistoryFolder(folder: File): Boolean = folder.name == HISTORY_DIR_NAME

    fun folders(includeExternal: Boolean = false): List<File> {
        val owned = cachedOwnedFolders()
        return if (includeExternal) listOf(historyFolder()) + owned else owned
    }

    fun scheduleImport() {
        if (!importRunning.compareAndSet(false, true)) return
        importExecutor.execute {
            try {
                val copied = importExternalIfNeeded(force = false)
                if (copied > 0) {
                    Log.info("background imported $copied stickers")
                }
            } catch (error: Throwable) {
                Log.warn("background import failed", error)
            } finally {
                importRunning.set(false)
            }
        }
    }

    fun isLocalEmoticon(file: File): Boolean {
        if (!file.isFile || !isImageFile(file)) return false
        val path = file.absolutePath
        return (SCAN_ROOTS + IMPORT_ROOTS).any { root ->
            path == root || path.startsWith("$root/")
        }
    }

    fun importExternalIfNeeded(force: Boolean = false): Int {
        synchronized(importLock) {
            val now = System.currentTimeMillis()
            if (!force && now - lastImportCheckAt < IMPORT_CHECK_INTERVAL_MS) return 0
            lastImportCheckAt = now
            val index = loadImportIndex()
            var imported = 0
            var changed = false
            IMPORT_ROOTS.forEach { sourceRoot ->
                listChildFolders(File(sourceRoot)).forEach { source ->
                    if (isOwned(source) || isHistoryFolder(source)) return@forEach
                    val stamp = cheapStamp(source)
                    if (stamp.startsWith("0:")) return@forEach
                    val previous = index[source.absolutePath]
                    if (!force && previous?.stamp == stamp) return@forEach
                    val dest = ownedFolderFor(source, previous?.destName)
                    if (!force && previous != null && imageNameCount(dest) >= stampCount(stamp)) {
                        index[source.absolutePath] = ImportRecord(dest.name, stamp)
                        changed = true
                        return@forEach
                    }
                    val copied = copyImages(listImageFiles(source), dest)
                    imported += copied
                    index[source.absolutePath] = ImportRecord(dest.name, stamp)
                    changed = true
                }
            }
            if (changed) {
                saveImportIndex(index)
                notifyChanged()
            }
            return imported
        }
    }

    fun images(folder: File): List<File> {
        if (isHistoryFolder(folder)) return historyImages()
        val stamp = listingStamp(folder)
        imageListCache[folder.absolutePath]?.let { cached ->
            if (cached.stamp == stamp) return cached.files
        }
        val files = listImageFiles(folder).sortedByDescending { it.lastModified() }
        imageListCache[folder.absolutePath] = CachedImages(stamp, files)
        return files
    }

    fun hasImages(folder: File): Boolean {
        if (isHistoryFolder(folder)) return historyImages().isNotEmpty()
        imageListCache[folder.absolutePath]?.let { return it.files.isNotEmpty() }
        val names = folder.list() ?: return false
        return names.any(::isImageName)
    }

    fun coverFile(folder: File): File? {
        if (isHistoryFolder(folder)) return historyImages().firstOrNull()
        return images(folder).lastOrNull()
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

    fun lastFolder(includeExternal: Boolean = false): File? =
        matchSavedFolder(folders(includeExternal))

    fun matchSavedFolder(available: List<File>): File? {
        if (available.isEmpty()) return null
        val saved = HookSettings.getString(LAST_FOLDER_KEY, "").ifBlank {
            File(root(), LAST_FOLDER_FILE)
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                .orEmpty()
        }
        if (saved.isEmpty()) return available.first()
        return available.firstOrNull { it.absolutePath == saved }
            ?: available.firstOrNull { it.name == saved }
            ?: available.first()
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

    private fun cachedOwnedFolders(): List<File> {
        val root = root()
        val stamp = listingStamp(root)
        ownedFolderCache?.let { cached ->
            if (cached.path == root.absolutePath && cached.stamp == stamp) {
                return cached.folders
            }
        }
        val folders = listChildFolders(root)
            .distinctBy { it.absolutePath }
            .filter { !isHistoryFolder(it) }
            .sortedWith(
                compareByDescending<File> { folderUsage(it) }
                    .thenBy { it.name.lowercase() },
            )
        ownedFolderCache = CachedFolders(root.absolutePath, stamp, folders)
        return folders
    }

    private fun invalidateListingCaches() {
        ownedFolderCache = null
        imageListCache.clear()
    }

    private fun listingStamp(folder: File): String {
        val names = folder.list() ?: emptyArray()
        return "${names.size}:${folder.lastModified()}"
    }

    private fun cheapStamp(folder: File): String {
        val names = folder.list() ?: emptyArray()
        val count = names.count(::isImageName)
        return "$count:${folder.lastModified()}"
    }

    private fun stampCount(stamp: String): Int =
        stamp.substringBefore(':').toIntOrNull() ?: 0

    private fun imageNameCount(folder: File): Int =
        folder.list()?.count(::isImageName) ?: 0

    private fun listImageFiles(folder: File): List<File> {
        val names = folder.list() ?: return emptyList()
        return names.mapNotNull { name ->
            if (isImageName(name)) File(folder, name) else null
        }
    }

    private fun ownedFolderFor(source: File, previousName: String?): File {
        val preferred = previousName
            ?.takeIf { it.isNotBlank() && it != HISTORY_DIR_NAME }
            ?: displayName(source)
        val name = sanitizeName(preferred).ifEmpty { sanitizeName(source.name) }
        check(name.isNotEmpty() && name != HISTORY_DIR_NAME) { "导入文件夹名无效" }
        val folder = File(root(), name)
        if (!folder.exists()) {
            check(folder.mkdirs()) { "无法创建收藏夹: $name" }
        }
        return folder
    }

    private fun copyImages(images: List<File>, dest: File): Int {
        ensureFolder(dest)
        val existing = dest.listFiles()?.filter(::isImageFile).orEmpty()
        if (existing.size >= images.size) return 0
        val existingNames = existing.mapTo(HashSet()) { it.name }
        val existingSizes = existing.mapTo(HashSet()) { it.length() }
        var copied = 0
        images.forEach { source ->
            val destName = destImageName(source)
            if (destName in existingNames || source.length() in existingSizes) return@forEach
            val destFile = File(dest, destName)
            if (runCatching { source.copyTo(destFile, overwrite = false) }.isSuccess) {
                existingNames.add(destName)
                existingSizes.add(source.length())
                copied += 1
            }
        }
        return copied
    }

    private fun destImageName(source: File): String {
        val ext = normalizeExtension(source.extension)
        val base = sanitizeName(source.nameWithoutExtension).ifEmpty { "sticker" }
        return "${source.length()}_${base}.$ext"
    }

    private fun loadImportIndex(): MutableMap<String, ImportRecord> {
        val file = File(root(), IMPORT_INDEX_FILE)
        if (!file.isFile) return mutableMapOf()
        return runCatching {
            val json = JSONObject(file.readText())
            val result = mutableMapOf<String, ImportRecord>()
            json.keys().forEach { key ->
                val item = json.optJSONObject(key) ?: return@forEach
                val destName = item.optString("dest").trim()
                val stamp = item.optString("stamp").trim()
                if (destName.isNotEmpty() && stamp.isNotEmpty()) {
                    result[key] = ImportRecord(destName, stamp)
                }
            }
            result
        }.getOrDefault(mutableMapOf())
    }

    private fun saveImportIndex(index: Map<String, ImportRecord>) {
        val json = JSONObject()
        index.forEach { (source, record) ->
            json.put(
                source,
                JSONObject()
                    .put("dest", record.destName)
                    .put("stamp", record.stamp),
            )
        }
        val file = File(root(), IMPORT_INDEX_FILE)
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
    }

    private fun configuredPath(key: String, defaultValue: String): String =
        HookSettings.getString(key, defaultValue).trim().ifEmpty { defaultValue }

    private fun listChildFolders(dir: File): List<File> {
        val names = dir.list() ?: return emptyList()
        return names.mapNotNull { name ->
            if (name.startsWith(".") || name == HISTORY_DIR_NAME) return@mapNotNull null
            val child = File(dir, name)
            if (child.isDirectory) child else null
        }
    }

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
        return stickerRoot.list()
            ?.asSequence()
            ?.map { File(stickerRoot, it) }
            ?.filter(File::isDirectory)
            ?.map { File(it, "packs") }
            ?.firstOrNull(File::isFile)
    }

    private fun isImageFile(file: File): Boolean = file.isFile && isImageName(file.name)

    private fun isImageName(name: String): Boolean {
        if (name.startsWith(".") || name.startsWith("__cover__.") || name.endsWith(".txt.jpg")) {
            return false
        }
        return name.substringAfterLast('.', "").lowercase() in imageExtensions
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

    private data class ImportRecord(val destName: String, val stamp: String)

    private data class CachedFolders(
        val path: String,
        val stamp: String,
        val folders: List<File>,
    )

    private data class CachedImages(
        val stamp: String,
        val files: List<File>,
    )

    private data class FunBoxNameCache(
        val path: String = "",
        val lastModified: Long = Long.MIN_VALUE,
        val length: Long = Long.MIN_VALUE,
        val names: Map<String, String> = emptyMap(),
    )
}
