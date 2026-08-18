package com.qm.qqzygisk.hook.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 按 URL 下载图片并解码为 Bitmap，可供任意功能复用。
 */
object ImageDownloader {
    const val DEFAULT_MAX_BYTES = 20 * 1024 * 1024
    const val DEFAULT_MAX_SIZE = 1280

    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"

    fun download(
        urls: List<String>,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        maxSize: Int = DEFAULT_MAX_SIZE,
    ): Bitmap {
        val failure = IllegalStateException("所有图片请求都失败了")
        urls.forEach { url ->
            runCatching { return download(url, maxBytes, maxSize) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
        }
        throw failure
    }

    fun download(
        url: String,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        maxSize: Int = DEFAULT_MAX_SIZE,
    ): Bitmap {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connect()
            check(connection.responseCode in 200..299) {
                "图片请求失败: HTTP ${connection.responseCode}"
            }
            val contentLength = connection.contentLengthLong
            check(contentLength < 0 || contentLength <= maxBytes) {
                "图片过大: $contentLength 字节"
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= maxBytes) { "图片超过 $maxBytes 字节" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            decode(bytes, maxSize) ?: error("图片数据无法解码")
        } finally {
            connection.disconnect()
        }
    }

    fun decode(
        bytes: ByteArray,
        maxSize: Int = DEFAULT_MAX_SIZE,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > maxSize ||
            bounds.outHeight / sampleSize > maxSize
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }
}
