package com.qm.qqzygisk.hook.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePayloadTest {
    @Test
    fun acceptsCommonHeaders() {
        assertTrue(ImagePayload.isImage(jpeg()))
        assertTrue(ImagePayload.isImage(png()))
        assertTrue(ImagePayload.isImage(gif()))
        assertTrue(ImagePayload.isImage(webp()))
    }

    @Test
    fun rejectsRkeyJson() {
        val json = """{"retcode":-5503010,"retmsg":"invalid rkey","retryflag":1}"""
            .toByteArray()
        assertFalse(ImagePayload.isImage(json))
        assertFalse(ImagePayload.isImage(ByteArray(4)))
    }

    private fun jpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(14)
    private fun png() = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(12)
    private fun gif() = byteArrayOf(0x47, 0x49, 0x46) + ByteArray(13)
    private fun webp() = byteArrayOf(
        0x52, 0x49, 0x46, 0x46,
        0, 0, 0, 0,
        0x57, 0x45, 0x42, 0x50,
    )
}
