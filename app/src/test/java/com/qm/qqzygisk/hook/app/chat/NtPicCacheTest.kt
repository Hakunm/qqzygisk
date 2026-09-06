package com.qm.qqzygisk.hook.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class NtPicCacheTest {
    @Test
    fun cachePathUsesCrcSubfolder() {
        val relative = NtPicCache.cacheRelativePath("chatimg", "DEADBEEFDEADBEEFDEADBEEFDEADBEEF")
        assertTrue(relative.startsWith("chatimg/"))
        val name = relative.substringAfterLast('/')
        assertTrue(name.startsWith("Cache_"))
        assertEquals(name.takeLast(3), relative.substringAfter('/').substringBefore('/'))
    }

    @Test
    fun findReturnsExistingPredictedFile() {
        val dir = createTempDirectory("qq-cache").toFile()
        val relative = NtPicCache.cacheRelativePath("chatraw", "AABBCCDDEEFF00112233445566778899")
        val file = File(dir, relative).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(32))
        }
        val found = NtPicCache.find(
            md5 = "AABBCCDDEEFF00112233445566778899",
            fileName = null,
            fileUuid = null,
            extraRoots = listOf(dir.absolutePath),
        )
        assertTrue(found.contains(file.absolutePath))
    }
}
