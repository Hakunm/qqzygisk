package com.qm.qqzygisk.hook.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import java.io.File
import java.nio.ByteBuffer
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Decodes static and animated images while keeping their largest side bounded. */
object AnimatedImageLoader {
    private val bindings = WeakHashMap<ImageView, AnimationBinding>()

    fun decode(file: File, maxSize: Int): Drawable? =
        decode(ImageDecoder.createSource(file), maxSize)

    fun decode(bytes: ByteArray, maxSize: Int): Drawable? =
        decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxSize)

    /** Grid thumbs use a still frame so GIFs do not restart on every bind. */
    fun stillBitmap(drawable: Drawable): Bitmap? {
        (drawable as? BitmapDrawable)?.bitmap?.let { return it }
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        return runCatching {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            bitmap
        }.getOrNull()
    }

    fun bind(view: ImageView, drawable: Drawable) {
        clear(view)
        view.setImageDrawable(drawable)

        val animation = drawable as? Animatable ?: return
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                animation.start()
            }

            override fun onViewDetachedFromWindow(view: View) {
                animation.stop()
            }
        }
        synchronized(bindings) {
            bindings[view] = AnimationBinding(animation, listener)
        }
        view.addOnAttachStateChangeListener(listener)
        if (view.isAttachedToWindow) animation.start()
    }

    fun clear(view: ImageView) {
        val binding = synchronized(bindings) { bindings.remove(view) }
        if (binding != null) {
            view.removeOnAttachStateChangeListener(binding.listener)
            binding.animation.stop()
        } else {
            (view.drawable as? Animatable)?.stop()
        }
        view.setImageDrawable(null)
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

    private data class AnimationBinding(
        val animation: Animatable,
        val listener: View.OnAttachStateChangeListener,
    )
}
