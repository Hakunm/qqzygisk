package com.qm.qqzygisk.hook.utils

import com.qm.qqzygisk.hook.parasitic.AppParasitics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 定位结果按宿主 versionCode + versionName 保存；命中后直接恢复，不校验形状或回退重新定位。 */
internal object HostTargetCache {
    private class Resolved(val loader: ClassLoader, val value: Any)
    private val resolved = mutableMapOf<String, Resolved>()

    @Synchronized
    fun <T : Any> resolve(key: String, loader: ClassLoader, discover: (Targets) -> T): T {
        val context = checkNotNull(AppParasitics.appContext) { "宿主 Application 尚未初始化" }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return resolve(context.filesDir, info.longVersionCode, info.versionName.orEmpty(), key, loader, discover)
    }

    @Synchronized
    internal fun <T : Any> resolve(
        privateFiles: File,
        versionCode: Long,
        versionName: String,
        key: String,
        loader: ClassLoader,
        discover: (Targets) -> T,
    ): T {
        val file = File(privateFiles, "qqzygisk/targets/$key.json")
        val memoryKey = "${file.absolutePath}\n$versionCode\n$versionName"
        resolved[memoryKey]?.takeIf { it.loader === loader }?.let {
            @Suppress("UNCHECKED_CAST")
            return it.value as T
        }
        val document = if (file.isFile) JSONObject(file.readText()) else null
        val hit = document != null && document.getLong("versionCode") == versionCode &&
            document.getString("versionName") == versionName
        val targets = Targets(loader, if (hit) document!!.getJSONObject("targets") else JSONObject(), hit)
        // 恢复失败直接交给调用方处理；同版本缓存不删除、不重新扫描。
        val value = discover(targets)
        if (!hit) {
            val updated = JSONObject()
                .put("versionCode", versionCode)
                .put("versionName", versionName)
                .put("targets", targets.values)
            runCatching { write(file, updated) }
                .onFailure { Log.warn("宿主目标 JSON 缓存保存失败: $key", it) }
        }
        resolved[memoryKey] = Resolved(loader, value)
        Log.info("宿主目标 JSON 缓存${if (hit) "命中" else "定位完成"}: $key")
        return value
    }

    private fun write(file: File, document: JSONObject) {
        val directory = file.parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { "无法创建目标缓存目录" }
        val pending = File.createTempFile(file.name, ".tmp", directory)
        try {
            FileOutputStream(pending).use { stream ->
                stream.write(document.toString(2).toByteArray(Charsets.UTF_8))
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

    class Targets internal constructor(
        private val loader: ClassLoader,
        internal val values: JSONObject,
        val cached: Boolean,
    ) {
        fun type(key: String, discover: () -> Class<*> = { error("未提供类定位器: $key") }): Class<*> {
            if (cached) return load(values.getJSONObject(key).getString("className"))
            return discover().also {
                values.put(key, descriptor("class", it))
            }
        }

        fun method(key: String, discover: () -> Method = { error("未提供方法定位器: $key") }): Method {
            if (cached) {
                val entry = values.getJSONObject(key)
                return load(entry.getString("className"))
                    .getDeclaredMethod(entry.getString("name"), *parameters(entry))
                    .apply { isAccessible = true }
            }
            return discover().apply { isAccessible = true }.also {
                values.put(key, descriptor("method", it.declaringClass)
                    .put("name", it.name)
                    .put("parameterTypes", names(it.parameterTypes))
                    .put("returnType", it.returnType.name))
            }
        }

        fun constructor(key: String, discover: () -> Constructor<*> = { error("未提供构造器定位器: $key") }): Constructor<*> {
            if (cached) {
                val entry = values.getJSONObject(key)
                return load(entry.getString("className")).getDeclaredConstructor(*parameters(entry))
                    .apply { isAccessible = true }
            }
            return discover().apply { isAccessible = true }.also {
                values.put(key, descriptor("constructor", it.declaringClass)
                    .put("name", "<init>")
                    .put("parameterTypes", names(it.parameterTypes)))
            }
        }

        fun field(key: String, discover: () -> Field = { error("未提供字段定位器: $key") }): Field {
            if (cached) {
                val entry = values.getJSONObject(key)
                return load(entry.getString("className")).getDeclaredField(entry.getString("name"))
                    .apply { isAccessible = true }
            }
            return discover().apply { isAccessible = true }.also {
                values.put(key, descriptor("field", it.declaringClass)
                    .put("name", it.name)
                    .put("type", it.type.name))
            }
        }

        private fun descriptor(kind: String, type: Class<*>) = JSONObject()
            .put("kind", kind).put("className", type.name)

        private fun names(types: Array<Class<*>>): JSONArray = JSONArray().apply {
            for (type in types) put(type.name)
        }

        private fun parameters(entry: JSONObject): Array<Class<*>> {
            val names = entry.getJSONArray("parameterTypes")
            return Array(names.length()) { load(names.getString(it)) }
        }

        private fun load(name: String): Class<*> = when (name) {
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
    }
}
