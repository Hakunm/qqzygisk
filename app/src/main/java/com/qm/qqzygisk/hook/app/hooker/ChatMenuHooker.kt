package com.qm.qqzygisk.hook.app.hooker

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.chat.ChatMenu
import com.qm.qqzygisk.hook.app.chat.ChatMenuPosition
import com.qm.qqzygisk.hook.app.chat.ChatMenuType
import com.qm.qqzygisk.hook.app.chat.NtImageRkeyProvider
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.ImageDownloader
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.injectModuleAppResources
import com.qm.qqzygisk.hook.utils.startModuleSettings

object ChatMenuHooker : BaseHooker() {
    override val key = "chat_menu_entry"
    override val name = "聊天长按保存图片"
    override val description = "在含图片的消息长按菜单中保存图片"
    override val defaultEnabled = false

    private val enabled get() = HookSettings.isEnabled(key, defaultEnabled)

    override fun initOnce() {
        ChatMenu.addMenuItem(
            title = "保存",
            type = ChatMenuType.Pic,
            icon = R.drawable.ic_save,
            visible = { enabled },
            position = ChatMenuPosition.Front,
        ) {
            context, element -> showImageDialog(context, element)
        }
        runCatching {
            NtImageRkeyProvider.installHook()
        }.onFailure {
            Log.error("安装 NT 图片 rkey hook 失败", it)
        }
    }

    private fun showImageDialog(context: Context, picElement: Any) {
        runCatching {
            context.injectModuleAppResources()
            val moduleClassLoader = javaClass.classLoader ?: context.classLoader
            val dialogContext = object : ContextThemeWrapper(
                context,
                R.style.Theme_QQZygisk_MaterialDialog,
            ) {
                override fun getClassLoader(): ClassLoader = moduleClassLoader
            }
            val imageView = ShapeableImageView(dialogContext).apply {
                contentDescription = "消息图片"
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(dialogContext.dp(16).toFloat())
                    .build()
            }
            val progress = ProgressBar(dialogContext)
            val content = FrameLayout(dialogContext).apply {
                val height = dialogContext.dp(280)
                addView(
                    imageView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        height,
                    ),
                )
                addView(
                    progress,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
            }
            val dialog = MaterialAlertDialogBuilder(dialogContext)
                .setTitle("图片操作")
                .setView(content)
                .setNegativeButton("关闭", null)
                .setPositiveButton("打开设置") { _, _ -> openSettings(context) }
                .create()
            dialog.show()
            loadImagePreview(picElement, imageView, progress)
        }.onFailure {
            Log.error("打开图片操作弹窗失败", it)
            Toast.makeText(context, "无法打开图片操作弹窗", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadImagePreview(
        picElement: Any,
        imageView: ImageView,
        progress: View,
    ) {
        val imageUrls = resolveImageUrls(picElement)
        if (imageUrls.isEmpty()) {
            progress.visibility = View.GONE
            imageView.setImageResource(android.R.drawable.ic_menu_report_image)
            return
        }
        Thread({
            val result = runCatching { ImageDownloader.download(imageUrls) }
            imageView.post {
                progress.visibility = View.GONE
                result.getOrNull()?.let(imageView::setImageBitmap)
                    ?: imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                result.exceptionOrNull()?.let {
                    Log.error("加载聊天图片预览失败（${imageUrls.size} 个候选地址）", it)
                }
            }
        }, "QQZygisk-ImagePreview").start()
    }

    private fun resolveImageUrls(picElement: Any): List<String> {
        val originUrl = invokeStringGetter(picElement, "getOriginImageUrl").orEmpty()
        val legacyUrl = invokeStringGetter(picElement, "getMd5HexStr")
            ?.takeIf(String::isNotBlank)
            ?.let { "https://gchat.qpic.cn/gchatpic_new/0/0-0-${it.uppercase()}/0" }
        val candidates = when {
            originUrl.startsWith("https://") || originUrl.startsWith("http://") -> {
                listOf(originUrl)
            }

            originUrl.startsWith("/download") -> {
                val ntUrl = "https://multimedia.nt.qq.com.cn$originUrl"
                val signedUrl = if (originUrl.contains("rkey=")) {
                    ntUrl
                } else {
                    NtImageRkeyProvider.get(originUrl)?.let { appendRkey(ntUrl, it) }
                }
                listOfNotNull(signedUrl, ntUrl)
            }

            originUrl.startsWith("/") -> listOf("https://gchat.qpic.cn$originUrl")
            else -> emptyList()
        }
        return (candidates + listOfNotNull(legacyUrl)).distinct()
    }

    private fun appendRkey(imageUrl: String, rkey: String): String {
        val separator = when {
            imageUrl.endsWith('?') || imageUrl.endsWith('&') -> ""
            imageUrl.contains('?') -> "&"
            else -> "?"
        }
        return imageUrl + separator + rkey.removePrefix("?").removePrefix("&")
    }

    private fun invokeStringGetter(instance: Any, methodName: String): String? {
        return runCatching {
            instance.javaClass.getMethod(methodName).invoke(instance) as? String
        }.getOrNull()
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun openSettings(context: Context) {
        runCatching {
            context.startModuleSettings()
        }.onFailure {
            Log.error("从聊天菜单打开 QQ Zygisk 设置失败", it)
            Toast.makeText(context, "无法打开 QQ Zygisk 设置", Toast.LENGTH_SHORT).show()
        }
    }
}
