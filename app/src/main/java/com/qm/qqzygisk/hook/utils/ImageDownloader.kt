package com.qm.qqzygisk.hook.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * 按 URL 下载图片并解码为 Bitmap，可供任意功能复用。
 */
object ImageDownloader {
    const val DEFAULT_MAX_BYTES = 20 * 1024 * 1024
    const val DEFAULT_MAX_SIZE = 1280

    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"

    data class DownloadedImage(
        val bytes: ByteArray,
        val extension: String,
    )

    fun download(
        urls: List<String>,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        maxSize: Int = DEFAULT_MAX_SIZE,
    ): Bitmap {
        return decode(fetch(urls, maxBytes).bytes, maxSize) ?: error("图片数据无法解码")
    }

    fun download(
        url: String,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        maxSize: Int = DEFAULT_MAX_SIZE,
    ): Bitmap {
        return decode(fetch(url, maxBytes).bytes, maxSize) ?: error("图片数据无法解码")
    }

    fun fetch(
        urls: List<String>,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): DownloadedImage {
        val failure = IllegalStateException("所有图片请求都失败了")
        urls.forEach { url ->
            runCatching { return fetch(url, maxBytes) }
                .onFailure { error ->
                    failure.addSuppressed(error)
                    Log.warn("图片来源失败: ${error.message} url=${url.take(180)}")
                }
        }
        Log.warn("全部 ${urls.size} 个图片来源都失败了")
        throw failure
    }

    fun fetch(
        url: String,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): DownloadedImage {
        localFile(url)?.let { file ->
            check(file.isFile && file.canRead()) { "本地图片不可读: ${file.absolutePath}" }
            check(file.length() <= maxBytes) { "图片超过 $maxBytes 字节" }
            val bytes = file.readBytes()
            check(ImagePayload.isImage(bytes)) { "本地文件不是图片: ${file.absolutePath}" }
            return DownloadedImage(bytes, extensionOf(file.name, null))
        }
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
            check(ImagePayload.isImage(bytes)) {
                val head = bytes.decodeToString(0, minOf(bytes.size, 72))
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                "图片响应不是图像数据: ${url.take(80)} head=$head"
            }
            DownloadedImage(bytes, extensionOf(url, connection.contentType))
        } finally {
            connection.disconnect()
        }
    }

    private fun localFile(url: String): File? {
        if (url.startsWith("file:")) {
            return runCatching { File(URI(url)) }.getOrNull()
        }
        if (url.startsWith("http://") || url.startsWith("https://")) return null
        val file = File(url)
        return file.takeIf { it.isAbsolute && it.isFile }
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

    private fun extensionOf(url: String, contentType: String?): String {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        return when (mime) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> {
                val path = url.substringBefore('?').lowercase()
                when {
                    path.endsWith(".png") -> "png"
                    path.endsWith(".gif") -> "gif"
                    path.endsWith(".webp") -> "webp"
                    else -> "jpg"
                }
            }
        }
    }
}
