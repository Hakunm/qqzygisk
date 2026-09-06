package com.qm.qqzygisk.hook.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class NtPicResolverTest {
    @Test
    fun localOriginUrlIsNotPrefixedWithGchat() {
        val dir = createTempDirectory("qq-pic").toFile()
        val file = File(dir, "origin.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) }
        val sources = NtPicResolver.expand(
            originUrl = file.absolutePath,
            sourcePath = null,
            thumbPaths = emptyList(),
            md5 = "abc",
            emojiWebUrl = null,
            snapshot = null,
        )
        assertEquals(listOf(file.absolutePath), sources)
        assertTrue(sources.none { it.contains("gchat.qpic.cn") })
    }

    @Test
    fun sourcePathComesBeforeRemoteDownload() {
        val dir = createTempDirectory("qq-pic").toFile()
        val file = File(dir, "cached.webp").apply { writeText("img") }
        val snapshot = RkeySnapshot(
            byType = mapOf(NtImageRkey.TYPE_GROUP to "&rkey=grp"),
            expiresAtMillis = Long.MAX_VALUE,
        )
        val sources = NtPicResolver.expand(
            originUrl = "/download?appid=1407&fileid=xyz",
            sourcePath = file.absolutePath,
            thumbPaths = emptyList(),
            md5 = null,
            emojiWebUrl = null,
            snapshot = snapshot,
        )
        assertEquals(file.absolutePath, sources.first())
        assertTrue(sources.any { it.startsWith("https://multimedia.nt.qq.com.cn/download") && it.contains("rkey=grp") })
    }

    @Test
    fun httpsDownloadGetsRkey() {
        val snapshot = RkeySnapshot(
            byType = mapOf(NtImageRkey.TYPE_PRIVATE to "&rkey=priv"),
            expiresAtMillis = Long.MAX_VALUE,
        )
        val sources = NtPicResolver.expand(
            originUrl = "https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc",
            sourcePath = null,
            thumbPaths = emptyList(),
            md5 = null,
            emojiWebUrl = null,
            snapshot = snapshot,
        )
        assertTrue(sources.any { it.endsWith("rkey=priv") })
        assertTrue(sources.any { it == "https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc" })
    }

    @Test
    fun dataPathIsNotTurnedIntoGchatUrl() {
        val sources = NtPicResolver.expand(
            originUrl = "/data/user/0/com.tencent.mobileqq/files/nt_pic/a.jpg",
            sourcePath = null,
            thumbPaths = emptyList(),
            md5 = null,
            emojiWebUrl = null,
            snapshot = null,
            fileExists = { false },
        )
        assertTrue(sources.none { it.contains("gchat.qpic.cn") })
        assertTrue(sources.none { it.contains("multimedia.nt.qq.com.cn") })
    }

    @Test
    fun looksLikeLocalPathRejectsNtDownload() {
        assertTrue(NtPicResolver.looksLikeLocalPath("/data/user/0/com.tencent.mobileqq/files/nt_pic/a.jpg"))
        assertTrue(NtPicResolver.looksLikeLocalPath("/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/nt_data/Pic/a"))
        assertFalse(NtPicResolver.looksLikeLocalPath("/download?appid=1406&fileid=abc"))
        assertFalse(NtPicResolver.looksLikeLocalPath("/gchatpic_new/0/0-0-ABC/0"))
    }

    @Test
    fun missingLocalUsesMd5OnlyWhenNoRemoteUrl() {
        val sources = NtPicResolver.expand(
            originUrl = null,
            sourcePath = "/missing/not-here.jpg",
            thumbPaths = emptyList(),
            md5 = "deadbeef",
            emojiWebUrl = null,
            snapshot = null,
            fileExists = { false },
        )
        assertEquals(listOf("https://gchat.qpic.cn/gchatpic_new/0/0-0-DEADBEEF/0"), sources)
    }

    @Test
    fun extraLocalsComeFirst() {
        val sources = NtPicResolver.expand(
            originUrl = "/download?appid=1407&fileid=xyz",
            sourcePath = null,
            thumbPaths = emptyList(),
            md5 = null,
            emojiWebUrl = null,
            snapshot = null,
            fileExists = { it == "/cache/from-kernel.webp" },
            extraLocals = listOf("/cache/from-kernel.webp"),
        )
        assertEquals("/cache/from-kernel.webp", sources.first())
    }

    @Test
    fun fileUuidBecomesNtDownload() {
        val snapshot = RkeySnapshot(
            byType = mapOf(NtImageRkey.TYPE_PRIVATE to "&rkey=priv"),
            expiresAtMillis = Long.MAX_VALUE,
        )
        val sources = NtPicResolver.expand(
            originUrl = null,
            sourcePath = null,
            thumbPaths = emptyList(),
            md5 = null,
            emojiWebUrl = null,
            snapshot = snapshot,
            fileUuid = "FILEID123",
        )
        assertTrue(
            sources.any {
                it.startsWith("https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=FILEID123") &&
                    it.contains("rkey=priv")
            },
        )
        assertTrue(sources.any { it.contains("spec=0") })
    }

    @Test
    fun schemeLessNtHostIsNormalized() {
        val sources = NtPicResolver.normalizeRemote(
            "multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc",
        )
        assertEquals(
            listOf("https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc"),
            sources,
        )
    }

    @Test
    fun specVariantsAddOriginalSize() {
        assertEquals(
            listOf(
                "https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc",
                "https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc&spec=0",
            ),
            NtPicResolver.specVariants("https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc"),
        )
        assertEquals(
            listOf("https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc&spec=0"),
            NtPicResolver.specVariants("https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc&spec=0"),
        )
    }

    @Test
    fun extraBytesYieldDownloadAndRkey() {
        val packed = "xx/download?appid=1407&fileid=ZZZ rkey=TOKEN99".toByteArray()
        assertTrue(NtPicResolver.extractUrls(packed).any { it.startsWith("/download?appid=1407") })
        assertEquals(listOf("rkey=TOKEN99"), NtPicResolver.extractRkeys(packed))
    }
}
