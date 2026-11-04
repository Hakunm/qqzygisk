package com.qm.qqzygisk.hook.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class HostTargetCacheFlowTest {
    class Target(@JvmField var text: String) {
        private fun render(prefix: String, values: ByteArray, times: Int): String =
            "$prefix${text.repeat(times)}:${values.sum()}"
    }

    private class Binding(
        val type: Class<*>,
        val constructor: Constructor<*>,
        val field: Field,
        val method: Method,
    ) {
        fun render(): String {
            val target = constructor.newInstance("original")
            assertTrue(type.isInstance(target))
            field.set(target, "updated")
            return method.invoke(target, ">", byteArrayOf(1, 2, 3), 2) as String
        }
    }

    @Test
    fun persistsTargetsRestoresWithoutDiscoveryAndOnlyVersionChangesRediscover() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "target-cache-").toFile()
        var discoveries = 0
        fun resolve(code: Long, name: String): Binding {
            // 换 ClassLoader 实例，确保每次从文件恢复而非命中进程内对象。
            val loader = object : ClassLoader(Target::class.java.classLoader) {}
            return HostTargetCache.resolve(directory, code, name, "flow", loader) { targets ->
                val type = targets.type("type") { discoveries++; Target::class.java }
                Binding(
                    type,
                    targets.constructor("constructor") {
                        discoveries++
                        type.getDeclaredConstructor(String::class.java)
                    },
                    targets.field("field") { discoveries++; type.getDeclaredField("text") },
                    targets.method("method") {
                        discoveries++
                        type.getDeclaredMethod("render", String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType!!)
                    },
                )
            }
        }
        try {
            assertEquals(">updatedupdated:6", resolve(100, "9.1").render())
            assertEquals(4, discoveries)
            assertEquals(">updatedupdated:6", resolve(100, "9.1").render())
            assertEquals(4, discoveries)

            assertEquals(">updatedupdated:6", resolve(100, "9.2").render())
            assertEquals(8, discoveries)
            assertEquals(">updatedupdated:6", resolve(101, "9.2").render())
            assertEquals(12, discoveries)

            val file = File(directory, "qqzygisk/targets/flow.json")
            val document = JSONObject(file.readText())
            document.getJSONObject("targets").getJSONObject("type").put("className", "missing.CachedTarget")
            file.writeText(document.toString(2))
            val missingClass = file.readText()
            assertThrows(ClassNotFoundException::class.java) { resolve(101, "9.2") }
            assertEquals(12, discoveries)
            assertEquals(missingClass, file.readText())

            document.getJSONObject("targets").getJSONObject("type").put("className", Target::class.java.name)
            document.getJSONObject("targets").getJSONObject("method").put("name", "missingMethod")
            file.writeText(document.toString(2))
            val missingMethod = file.readText()
            assertThrows(NoSuchMethodException::class.java) { resolve(101, "9.2") }
            assertEquals(12, discoveries)
            assertEquals(missingMethod, file.readText())

            assertEquals(">updatedupdated:6", resolve(102, "9.2").render())
            assertEquals(16, discoveries)
            assertEquals(listOf(file), file.parentFile!!.listFiles()!!.toList())
        } finally {
            directory.deleteRecursively()
        }
    }
}
