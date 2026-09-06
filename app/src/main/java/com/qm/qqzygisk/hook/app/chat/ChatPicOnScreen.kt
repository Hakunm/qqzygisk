package com.qm.qqzygisk.hook.app.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.qm.qqzygisk.hook.utils.ImagePayload
import com.qm.qqzygisk.hook.utils.Log
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * 长按保存时聊天图已经画在界面上。URL / 缓存都失败时，把当前 ImageView 截下来。
 */
internal object ChatPicOnScreen {
    private const val OUT_PATH = "/data/user/0/com.tencent.mobileqq/files/qhook_onscreen.jpg"
    private const val MIN_EDGE = 120

    fun capture(context: Context, picElement: Any?): String? {
        val activity = context.findActivity() ?: run {
            Log.info("保存图片时没有 Activity，无法截取界面上的图")
            return null
        }
        val picW = intOf(picElement, "picWidth", "getPicWidth")
        val picH = intOf(picElement, "picHeight", "getPicHeight")
        val menu = ChatMenu.lastMenuLayout
        val ranked = mutableListOf<ScoredView>()
        walk(activity.window?.decorView) { view ->
            val image = view as? ImageView ?: return@walk
            if (menu != null && image.isDescendantOf(menu)) return@walk
            if (image.width < MIN_EDGE || image.height < MIN_EDGE) return@walk
            val drawable = image.drawable ?: return@walk
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: image.width
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: image.height
            if (width < 80 || height < 80) return@walk
            if (picW != null && picH != null && picW > 0 && picH > 0) {
                val diff = abs(width.toFloat() / height - picW.toFloat() / picH)
                if (diff > 0.28f) return@walk
            }
            ranked += ScoredView(image, width * height)
        }
        val best = ranked.maxByOrNull { it.area }?.view ?: run {
            Log.info("界面上没有足够大的聊天 ImageView pic=${picW}x$picH views=0")
            return null
        }
        val bytes = bitmapBytes(best) ?: run {
            Log.info("截取聊天 ImageView 失败 size=${best.width}x${best.height}")
            return null
        }
        if (!ImagePayload.isImage(bytes)) return null
        val out = File(OUT_PATH)
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        Log.info("已截取聊天界面图片 ${bytes.size} 字节 ${best.width}x${best.height} -> ${out.absolutePath}")
        return out.absolutePath
    }

    private fun bitmapBytes(image: ImageView): ByteArray? {
        val drawable = image.drawable
        val bitmap = (drawable as? BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled }
            ?: snapshot(image)
            ?: return null
        val output = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) return null
        return output.toByteArray()
    }

    private fun snapshot(view: ImageView): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        return runCatching {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            bitmap
        }.getOrNull()
    }

    private fun walk(root: View?, visit: (View) -> Unit) {
        if (root == null) return
        visit(root)
        val group = root as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            walk(group.getChildAt(index), visit)
        }
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun intOf(instance: Any?, vararg names: String): Int? {
        if (instance == null) return null
        return names.firstNotNullOfOrNull { name ->
            NtMsgAccess.asInt(NtMsgAccess.read(instance, name))
        }
    }

    private data class ScoredView(
        val view: ImageView,
        val area: Int,
    )
}
