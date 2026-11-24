package com.qm.qqzygisk.hook.utils

import android.content.Context
import dalvik.system.BaseDexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Executable
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 纯 Kotlin 的 DEX 字节码调用方扫描器，用于定位可能将关键 Hook 目标内联的调用方法。
 *
 * 性能与缓存策略：
 * - 对每个 DEX 先在有序 `string_ids` / `type_ids` / `method_ids` 上二分查找目标方法索引，
 *   未引用目标方法的 DEX 直接跳过，不解析任何类与指令；
 * - 扫描结果按宿主 `versionCode + versionName + targetsHash` 落盘为 JSON 缓存，
 *   同一宿主版本仅首次扫描，后续启动直接读取缓存（通常 < 1ms）。
 */
internal object DexCallerScanner {
    data class TargetMethod(
        /** DEX 类型描述符，例如 `Lmqq/app/msghandle/MsgRespHandler;` */
        val classDescriptor: String,
        val methodName: String,
    )

    data class CallerMethod(
        val className: String,
        val methodName: String,
        val parameterTypes: List<String>,
    ) {
        fun resolve(loader: ClassLoader): Executable? = runCatching {
            val owner = Class.forName(className, false, loader)
            val params = Array(parameterTypes.size) { index -> loadType(parameterTypes[index], loader) }
            when (methodName) {
                "<init>" -> owner.getDeclaredConstructor(*params).apply { isAccessible = true }
                "<clinit>" -> null
                else -> owner.getDeclaredMethod(methodName, *params).apply { isAccessible = true }
            }
        }.getOrNull()
    }

    /** 仅尝试读取同版本已落盘缓存；命中返回调用方列表（可为空），未命中返回 null。 */
    fun loadCachedCallers(
        context: Context,
        cacheKey: String,
        targets: Set<TargetMethod>,
    ): List<CallerMethod>? {
        val version = hostVersion(context) ?: return null
        return loadCachedCallers(context.filesDir, version.first, version.second, cacheKey, targets)
    }

    internal fun loadCachedCallers(
        privateFiles: File,
        versionCode: Long,
        versionName: String,
        cacheKey: String,
        targets: Set<TargetMethod>,
    ): List<CallerMethod>? = runCatching {
        val file = cacheFile(privateFiles, cacheKey)
        if (!file.isFile) return null
        val lines = file.readLines(Charsets.UTF_8)
        if (lines.size < 3) return null
        if (lines[0].toLongOrNull() != versionCode) return null
        if (lines[1] != versionName) return null
        if (lines[2] != targetsFingerprint(targets)) return null
        val callers = ArrayList<CallerMethod>(lines.size - 3)
        for (i in 3 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            if (parts.size < 2) return null
            val params = if (parts.size >= 3 && parts[2].isNotEmpty()) parts[2].split(',') else emptyList()
            callers.add(
                CallerMethod(
                    className = parts[0],
                    methodName = parts[1],
                    parameterTypes = params,
                ),
            )
        }
        callers
    }.getOrNull()

    /**
     * 发现并缓存调用 [targets] 的所有宿主方法。
     *
     * 优先命中版本缓存；未命中时扫描宿主 APK / split APK / ClassLoader 路径下的全部 DEX 并写入缓存。
     */
    fun discoverCallers(
        context: Context,
        loader: ClassLoader,
        cacheKey: String,
        targets: Set<TargetMethod>,
    ): List<CallerMethod> {
        val version = hostVersion(context)
        if (version != null) {
            loadCachedCallers(context.filesDir, version.first, version.second, cacheKey, targets)?.let { cached ->
                Log.info("调用方 DEX 缓存命中: key=$cacheKey count=${cached.size}")
                return cached
            }
        }
        val startedAt = System.currentTimeMillis()
        val files = collectCodeContainers(context, loader)
        val callers = LinkedHashSet<CallerMethod>()
        for (file in files) {
            runCatching { callers.addAll(scanApkOrDex(file, targets)) }
                .onFailure { Log.warn("扫描调用方失败: ${file.name}", it) }
        }
        val result = callers.toList()
        if (version != null && files.isNotEmpty()) {
            runCatching {
                saveCachedCallers(context.filesDir, version.first, version.second, cacheKey, targets, result)
            }.onFailure { Log.warn("保存调用方 DEX 缓存失败: $cacheKey", it) }
        }
        Log.info("调用方 DEX 扫描完成: key=$cacheKey files=${files.size} count=${result.size} elapsed=${System.currentTimeMillis() - startedAt}ms")
        return result
    }

    internal fun saveCachedCallers(
        privateFiles: File,
        versionCode: Long,
        versionName: String,
        cacheKey: String,
        targets: Set<TargetMethod>,
        callers: List<CallerMethod>,
    ) {
        val body = buildString {
            append(versionCode).append('\n')
            append(versionName.replace('\n', ' ').replace('\r', ' ')).append('\n')
            append(targetsFingerprint(targets)).append('\n')
            for (caller in callers) {
                append(caller.className).append('\t')
                append(caller.methodName).append('\t')
                append(caller.parameterTypes.joinToString(",")).append('\n')
            }
        }
        writeAtomically(cacheFile(privateFiles, cacheKey), body)
    }

    /** 扫描单个 APK/ZIP/JAR 或裸 DEX 文件。 */
    fun scanApkOrDex(file: File, targets: Set<TargetMethod>): List<CallerMethod> {
        if (!file.isFile || !file.canRead() || file.length() < 112L || targets.isEmpty()) return emptyList()
        val header = ByteArray(4)
        file.inputStream().use { input ->
            if (input.read(header) < 4) return emptyList()
        }
        // Raw DEX: "dex\n"
        if (header[0] == 'd'.code.toByte() && header[1] == 'e'.code.toByte() &&
            header[2] == 'x'.code.toByte() && header[3] == '\n'.code.toByte()
        ) {
            return scanDex(file.readBytes(), targets)
        }
        // ZIP / APK / JAR: "PK\x03\x04"
        if (header[0] == 0x50.toByte() && header[1] == 0x4b.toByte()) {
            val found = LinkedHashSet<CallerMethod>()
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.endsWith(".dex", ignoreCase = true)) continue
                    val dexBytes = zip.getInputStream(entry).use { it.readBytes() }
                    found.addAll(scanDex(dexBytes, targets))
                }
            }
            return found.toList()
        }
        return emptyList()
    }

    /**
     * 扫描单个 DEX 字节数组，返回字节码中直接或通过接口调用 [targets] 的所有方法。
     *
     * 跳过 `<clinit>`（类静态初始化器无需去优化）与无字节码的 abstract/native 方法。
     */
    fun scanDex(dex: ByteArray, targets: Set<TargetMethod>): List<CallerMethod> {
        if (dex.size < 112 || targets.isEmpty()) return emptyList()
        if (dex[0] != 'd'.code.toByte() || dex[1] != 'e'.code.toByte() ||
            dex[2] != 'x'.code.toByte() || dex[3] != '\n'.code.toByte()
        ) {
            return emptyList()
        }
        val parser = DexParser(dex)
        val targetMethodIndices = parser.findTargetMethodIndices(targets)
        if (targetMethodIndices.isEmpty()) return emptyList()
        return parser.findCallers(targetMethodIndices)
    }

    private fun collectCodeContainers(context: Context, loader: ClassLoader): List<File> {
        val seen = LinkedHashMap<String, File>()
        fun addFile(file: File?) {
            if (file == null || !file.isFile || !file.canRead()) return
            val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            seen.putIfAbsent(canonical, file)
        }
        runCatching {
            val info = context.applicationInfo
            addFile(info.sourceDir?.let(::File))
            info.splitSourceDirs?.forEach { addFile(File(it)) }
        }
        runCatching {
            val pathListField = BaseDexClassLoader::class.java
                .getDeclaredField("pathList").apply { isAccessible = true }
            for (current in generateSequence(loader) { it.parent }) {
                if (current !is BaseDexClassLoader) continue
                val pathList = pathListField.get(current) ?: continue
                val elements = pathList.javaClass.getDeclaredField("dexElements")
                    .apply { isAccessible = true }.get(pathList) as? Array<*> ?: continue
                for (element in elements) {
                    if (element == null) continue
                    val pathField = runCatching { element.javaClass.getDeclaredField("path").apply { isAccessible = true } }.getOrNull()
                    addFile(pathField?.get(element) as? File)
                }
            }
        }
        return seen.values.toList()
    }

    private fun hostVersion(context: Context): Pair<Long, String>? = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.longVersionCode to info.versionName.orEmpty()
    }.getOrNull()

    private fun cacheFile(privateFiles: File, key: String): File =
        File(privateFiles, "qqzygisk/targets/$key.cache")

    private fun targetsFingerprint(targets: Set<TargetMethod>): String {
        val canonical = targets
            .map { "${it.classDescriptor}#${it.methodName}" }
            .sorted()
            .joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun writeAtomically(file: File, text: String) {
        val directory = file.parentFile ?: return
        check(directory.isDirectory || directory.mkdirs()) { "无法创建调用方缓存目录" }
        val pending = File.createTempFile(file.name, ".tmp", directory)
        try {
            FileOutputStream(pending).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            pending.delete()
        }
    }

    internal fun descriptorToClassName(descriptor: String): String =
        when {
            descriptor.length == 1 -> when (descriptor[0]) {
                'Z' -> "boolean"
                'B' -> "byte"
                'C' -> "char"
                'S' -> "short"
                'I' -> "int"
                'J' -> "long"
                'F' -> "float"
                'D' -> "double"
                'V' -> "void"
                else -> descriptor
            }
            descriptor.startsWith("[") -> descriptor.replace('/', '.')
            descriptor.startsWith("L") && descriptor.endsWith(";") ->
                descriptor.substring(1, descriptor.length - 1).replace('/', '.')
            else -> descriptor.replace('/', '.')
        }

    private fun loadType(name: String, loader: ClassLoader): Class<*> =
        when (name) {
            "boolean" -> Boolean::class.javaPrimitiveType!!
            "byte" -> Byte::class.javaPrimitiveType!!
            "char" -> Char::class.javaPrimitiveType!!
            "short" -> Short::class.javaPrimitiveType!!
            "int" -> Int::class.javaPrimitiveType!!
            "long" -> Long::class.javaPrimitiveType!!
            "float" -> Float::class.javaPrimitiveType!!
            "double" -> Double::class.javaPrimitiveType!!
            "void" -> Void.TYPE
            else -> Class.forName(name, false, loader)
        }

    private class DexParser(private val buf: ByteArray) {
        private val stringIdsSize = u32(56)
        private val stringIdsOff = u32(60)
        private val typeIdsSize = u32(64)
        private val typeIdsOff = u32(68)
        private val protoIdsSize = u32(72)
        private val protoIdsOff = u32(76)
        private val methodIdsSize = u32(88)
        private val methodIdsOff = u32(92)
        private val classDefsSize = u32(96)
        private val classDefsOff = u32(100)

        fun findTargetMethodIndices(targets: Set<TargetMethod>): Set<Int> {
            if (stringIdsSize <= 0 || typeIdsSize <= 0 || methodIdsSize <= 0) return emptySet()
            val byClass = HashMap<Int, MutableSet<Int>>()
            for (target in targets) {
                val nameStrIdx = findStringIndex(target.methodName)
                if (nameStrIdx < 0) continue
                val classStrIdx = findStringIndex(target.classDescriptor)
                if (classStrIdx < 0) continue
                val classTypeIdx = findTypeIndex(classStrIdx)
                if (classTypeIdx < 0) continue
                byClass.getOrPut(classTypeIdx) { HashSet() }.add(nameStrIdx)
            }
            if (byClass.isEmpty()) return emptySet()

            val matched = HashSet<Int>()
            for ((classTypeIdx, nameIndices) in byClass) {
                var idx = lowerBoundMethodByClass(classTypeIdx)
                while (idx < methodIdsSize) {
                    val off = methodIdsOff + idx * 8
                    if (off + 8 > buf.size) break
                    val cls = u16(off)
                    if (cls != classTypeIdx) break
                    val nameIdx = u32(off + 4)
                    if (nameIdx in nameIndices) {
                        matched.add(idx)
                    }
                    idx++
                }
            }
            return matched
        }

        fun findCallers(targetMethodIndices: Set<Int>): List<CallerMethod> {
            val callers = LinkedHashSet<CallerMethod>()
            for (classIndex in 0 until classDefsSize) {
                val defOff = classDefsOff + classIndex * 32
                if (defOff + 32 > buf.size) break
                val classDataOff = u32(defOff + 24)
                if (classDataOff <= 0 || classDataOff >= buf.size) continue

                val cursor = Cursor(classDataOff)
                val staticFieldsSize = readUleb128(cursor)
                val instanceFieldsSize = readUleb128(cursor)
                val directMethodsSize = readUleb128(cursor)
                val virtualMethodsSize = readUleb128(cursor)

                // Skip static and instance fields (2 ULEB128 values each)
                repeat(staticFieldsSize + instanceFieldsSize) {
                    readUleb128(cursor)
                    readUleb128(cursor)
                }

                val classTypeIdx = u32(defOff)
                var className: String? = null
                fun scanMethodList(count: Int) {
                    var methodIdx = 0
                    repeat(count) {
                        methodIdx += readUleb128(cursor)
                        readUleb128(cursor) // access_flags
                        val codeOff = readUleb128(cursor)
                        if (codeOff > 0 && codeOff + 16 <= buf.size &&
                            codeReferencesTarget(codeOff, targetMethodIndices)
                        ) {
                            val caller = decodeCaller(methodIdx, className ?: typeName(classTypeIdx).also { className = it })
                            if (caller != null && caller.methodName != "<clinit>") {
                                callers.add(caller)
                            }
                        }
                    }
                }
                scanMethodList(directMethodsSize)
                scanMethodList(virtualMethodsSize)
            }
            return callers.toList()
        }

        private fun codeReferencesTarget(codeOff: Int, targets: Set<Int>): Boolean {
            val insnsSize = u32(codeOff + 12)
            if (insnsSize <= 0) return false
            val insnsStart = codeOff + 16
            if (insnsStart + insnsSize * 2 > buf.size) return false

            var pc = 0
            while (pc < insnsSize) {
                val unit = u16(insnsStart + pc * 2)
                val opcode = unit and 0xff
                if (opcode == 0x00) {
                    val step = when (unit) {
                        0x0000 -> 1
                        0x0100 -> { // packed-switch-payload
                            if (pc + 1 >= insnsSize) return false
                            4 + 2 * u16(insnsStart + (pc + 1) * 2)
                        }
                        0x0200 -> { // sparse-switch-payload
                            if (pc + 1 >= insnsSize) return false
                            2 + 4 * u16(insnsStart + (pc + 1) * 2)
                        }
                        0x0300 -> { // fill-array-data-payload
                            if (pc + 3 >= insnsSize) return false
                            val elemWidth = u16(insnsStart + (pc + 1) * 2)
                            val size = u32(insnsStart + (pc + 2) * 2)
                            if (size < 0) return false
                            4 + ((size.toLong() * elemWidth.toLong() + 1L) / 2L).toInt()
                        }
                        else -> 1
                    }
                    if (step <= 0) return false
                    pc += step
                    continue
                }
                val width = OPCODE_WIDTH[opcode]
                if (width <= 0 || pc + width > insnsSize) return false
                // invoke-virtual (0x6e)..invoke-interface (0x72), invoke-virtual/range (0x74)..invoke-interface/range (0x78)
                if ((opcode in 0x6e..0x72) || (opcode in 0x74..0x78)) {
                    val invokedMethodIdx = u16(insnsStart + (pc + 1) * 2)
                    if (invokedMethodIdx in targets) return true
                }
                pc += width
            }
            return false
        }

        private fun decodeCaller(methodIdx: Int, fallbackClassName: String): CallerMethod? {
            if (methodIdx < 0 || methodIdx >= methodIdsSize) return null
            val off = methodIdsOff + methodIdx * 8
            if (off + 8 > buf.size) return null
            val classIdx = u16(off)
            val protoIdx = u16(off + 2)
            val nameIdx = u32(off + 4)
            val className = typeName(classIdx).ifEmpty { fallbackClassName }
            val methodName = stringAt(nameIdx)
            if (className.isEmpty() || methodName.isEmpty()) return null
            val params = protoParameters(protoIdx)
            return CallerMethod(className, methodName, params)
        }

        private fun protoParameters(protoIdx: Int): List<String> {
            if (protoIdx < 0 || protoIdx >= protoIdsSize) return emptyList()
            val off = protoIdsOff + protoIdx * 12
            if (off + 12 > buf.size) return emptyList()
            val paramsOff = u32(off + 8)
            if (paramsOff <= 0 || paramsOff + 4 > buf.size) return emptyList()
            val count = u32(paramsOff)
            if (count <= 0 || paramsOff + 4 + count * 2 > buf.size) return emptyList()
            val result = ArrayList<String>(count)
            for (i in 0 until count) {
                val typeIdx = u16(paramsOff + 4 + i * 2)
                result.add(typeName(typeIdx))
            }
            return result
        }

        private fun typeName(typeIdx: Int): String {
            if (typeIdx < 0 || typeIdx >= typeIdsSize) return ""
            val strIdx = u32(typeIdsOff + typeIdx * 4)
            return descriptorToClassName(stringAt(strIdx))
        }

        private fun findStringIndex(target: String): Int {
            var low = 0
            var high = stringIdsSize - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val cmp = stringAt(mid).compareTo(target)
                when {
                    cmp < 0 -> low = mid + 1
                    cmp > 0 -> high = mid - 1
                    else -> return mid
                }
            }
            return -1
        }

        private fun findTypeIndex(stringIdx: Int): Int {
            var low = 0
            var high = typeIdsSize - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val cur = u32(typeIdsOff + mid * 4)
                when {
                    cur < stringIdx -> low = mid + 1
                    cur > stringIdx -> high = mid - 1
                    else -> return mid
                }
            }
            return -1
        }

        private fun lowerBoundMethodByClass(classTypeIdx: Int): Int {
            var low = 0
            var high = methodIdsSize
            while (low < high) {
                val mid = (low + high) ushr 1
                val cls = u16(methodIdsOff + mid * 8)
                if (cls < classTypeIdx) low = mid + 1 else high = mid
            }
            return low
        }

        private fun stringAt(index: Int): String {
            if (index < 0 || index >= stringIdsSize) return ""
            val dataOff = u32(stringIdsOff + index * 4)
            if (dataOff <= 0 || dataOff >= buf.size) return ""
            val cursor = Cursor(dataOff)
            readUleb128(cursor) // utf16_size
            val start = cursor.pos
            var end = start
            while (end < buf.size && buf[end] != 0.toByte()) end++
            return decodeMutf8(buf, start, end)
        }

        private fun readUleb128(cursor: Cursor): Int {
            var result = 0
            var shift = 0
            while (cursor.pos < buf.size) {
                val b = buf[cursor.pos++].toInt() and 0xff
                result = result or ((b and 0x7f) shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
                if (shift > 28) break
            }
            return result
        }

        private fun u16(offset: Int): Int =
            (buf[offset].toInt() and 0xff) or ((buf[offset + 1].toInt() and 0xff) shl 8)

        private fun u32(offset: Int): Int =
            (buf[offset].toInt() and 0xff) or
                ((buf[offset + 1].toInt() and 0xff) shl 8) or
                ((buf[offset + 2].toInt() and 0xff) shl 16) or
                ((buf[offset + 3].toInt() and 0xff) shl 24)

        private class Cursor(var pos: Int)
    }

    private fun decodeMutf8(buf: ByteArray, start: Int, end: Int): String {
        var asciiOnly = true
        for (i in start until end) {
            if ((buf[i].toInt() and 0x80) != 0) {
                asciiOnly = false
                break
            }
        }
        if (asciiOnly) return String(buf, start, end - start, Charsets.ISO_8859_1)
        val out = StringBuilder(end - start)
        var pos = start
        while (pos < end) {
            val a = buf[pos++].toInt() and 0xff
            when {
                a < 0x80 -> out.append(a.toChar())
                (a and 0xe0) == 0xc0 && pos < end -> {
                    val b = buf[pos++].toInt() and 0x3f
                    out.append((((a and 0x1f) shl 6) or b).toChar())
                }
                (a and 0xf0) == 0xe0 && pos + 1 < end -> {
                    val b = buf[pos++].toInt() and 0x3f
                    val c = buf[pos++].toInt() and 0x3f
                    out.append((((a and 0x0f) shl 12) or (b shl 6) or c).toChar())
                }
                else -> out.append('\uFFFD')
            }
        }
        return out.toString()
    }

    /**
     * Dalvik 字节码 256 个操作码的固定长度（以 16-bit code unit 计）。
     *
     * 依据 Android Dalvik Bytecode 官方规范（含 Android 8+ 新增的 0xfa..0xff）。
     * 未定义槽位填 0 并在扫描循环中安全中止。
     */
    private val OPCODE_WIDTH = intArrayOf(
        // 0x00..0x0f: nop, move*, move-result*, move-exception, return*
        1, 1, 2, 3, 1, 2, 3, 1, 2, 3, 1, 1, 1, 1, 1, 1,
        // 0x10..0x1f: return-wide/object, const*, const-string*, const-class, monitor*, check-cast
        1, 1, 1, 2, 3, 2, 2, 3, 5, 2, 2, 3, 2, 1, 1, 2,
        // 0x20..0x2f: instance-of, array-length, new-instance, new-array, filled-new-array*, fill-array-data, throw, goto*, switch*, cmp*
        2, 1, 2, 2, 3, 3, 3, 1, 1, 2, 3, 3, 3, 2, 2, 2,
        // 0x30..0x3f: cmp*, if-*, if-*z
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 0, 0,
        // 0x40..0x4f: unused(0x40..0x43), aget*, aput*
        0, 0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        // 0x50..0x5f: aput*, iget*, iput*
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        // 0x60..0x6f: sget*, sput*, invoke-virtual, invoke-super
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3,
        // 0x70..0x7f: invoke-direct/static/interface, unused(0x73), invoke-*/range, unused(0x79..0x7a), unop(0x7b..0x7f)
        3, 3, 3, 0, 3, 3, 3, 3, 3, 0, 0, 1, 1, 1, 1, 1,
        // 0x80..0x8f: unop (12x)
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        // 0x90..0x9f: binop (23x)
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        // 0xa0..0xaf: binop (23x)
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        // 0xb0..0xbf: binop/2addr (12x)
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        // 0xc0..0xcf: binop/2addr (12x)
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        // 0xd0..0xdf: binop/lit16 (22s: 0xd0..0xd7), binop/lit8 (22b: 0xd8..0xdf)
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        // 0xe0..0xef: binop/lit8 (0xe0..0xe2), unused(0xe3..0xef)
        2, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        // 0xf0..0xff: unused(0xf0..0xf9), invoke-polymorphic(4), invoke-polymorphic/range(4),
        //             invoke-custom(3), invoke-custom/range(3), const-method-handle(2), const-method-type(2)
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 4, 3, 3, 2, 2,
    )
}
