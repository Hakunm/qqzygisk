package com.qm.qqzygisk.hook.utils

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Decodes static and animated images while keeping their largest side bounded. */
object AnimatedImageLoader {
    fun decode(file: File, maxSize: Int): Drawable? =
        decode(ImageDecoder.createSource(file), maxSize)

    fun decode(bytes: ByteArray, maxSize: Int): Drawable? =
        decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxSize)

    fun bind(view: ImageView, drawable: Drawable) {
        (view.drawable as? Animatable)?.stop()
        view.setImageDrawable(drawable)

        val animation = drawable as? Animatable ?: return
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                animation.start()
            }

            override fun onViewDetachedFromWindow(view: View) {
                animation.stop()
            }
        })
        if (view.isAttachedToWindow) animation.start()
    }

    private fun decode(source: ImageDecoder.Source, maxSize: Int): Drawable? = runCatching {
        ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val longestSide = maxOf(width, height)
            if (maxSize > 0 && longestSide > maxSize) {
                val scale = maxSize.toFloat() / longestSide
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }.getOrNull()
}
