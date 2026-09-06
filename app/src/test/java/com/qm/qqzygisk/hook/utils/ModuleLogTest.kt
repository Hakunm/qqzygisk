package com.qm.qqzygisk.hook.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class ModuleLogTest {
    @Test
    fun writesAndReadsTailWithoutLogcat() {
        val dir = createTempDirectory("qhook-log").toFile()
        val file = java.io.File(dir, "qhook.log")
        val previous = ModuleLog.pathOverride
        ModuleLog.pathOverride = listOf(file.absolutePath)
        try {
            ModuleLog.clear()
            ModuleLog.append("I", "保存图片 PicElement origin=/download", null)
            ModuleLog.append("W", "图片来源失败: invalid rkey", null)
            ModuleLog.append("E", "打开图片面板失败", NullPointerException())
            val text = ModuleLog.readTail()
            assertTrue(text.contains("保存图片 PicElement"))
            assertTrue(text.contains("invalid rkey"))
            assertTrue(text.contains("NullPointerException"))
            assertTrue(text.contains(" <- "))
            assertTrue(file.isFile)
            assertEqualsPath(file.absolutePath)
        } finally {
            ModuleLog.clear()
            ModuleLog.pathOverride = previous
        }
    }

    private fun assertEqualsPath(path: String) {
        assertFalse(path.isBlank())
        assertTrue(ModuleLog.candidatePaths().contains(path))
    }
}
