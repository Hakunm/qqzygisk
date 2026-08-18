package com.qm.qqzygisk.hook.app.chat

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.utils.ImageDownloader
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.injectModuleAppResources
import java.io.File

/**
 * 自定义图片面板：浏览本地文件夹，或从聊天保存图片。
 */
class SaveImagePanel private constructor(
    private val context: Context,
    private val imageUrls: List<String>,
) {
    private val colors = PanelColors.from(context)
    private val pending = arrayOfNulls<ImageDownloader.DownloadedImage>(1)
    private val selected = arrayOfNulls<File>(1)
    private lateinit var folderRow: LinearLayout
    private var imageGrid: LinearLayout? = null
    private var emptyHint: TextView? = null
    private var previewView: ImageView? = null
    private var previewProgress: ProgressBar? = null
    private val browseOnly get() = imageUrls.isEmpty()

    fun show() {
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(buildContent(dialog))
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.4f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        dialog.show()
        bindFolders()
        if (!browseOnly) loadPreview()
    }

    private fun buildContent(dialog: Dialog): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(colors.surface, topRadius = context.dp(24).toFloat())
            val pad = context.dp(20)
            setPadding(pad, context.dp(10), pad, context.dp(24))
        }

        root.addView(
            View(context).apply {
                background = rounded(colors.handle, context.dp(2).toFloat())
            },
            LinearLayout.LayoutParams(context.dp(36), context.dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = context.dp(16)
            },
        )

        root.addView(
            TextView(context).apply {
                text = if (browseOnly) "图片面板" else "保存图片"
                setTextColor(colors.onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = context.dp(16) },
        )

        if (!browseOnly) {
            previewView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(colors.preview, context.dp(20).toFloat())
                clipToOutline = true
                outlineProvider = roundedOutline(context.dp(20).toFloat())
            }
            previewProgress = ProgressBar(context)
            val previewBox = FrameLayout(context).apply {
                addView(
                    previewView,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(200)),
                )
                addView(
                    previewProgress,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
            }
            root.addView(
                previewBox,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(200),
                ).apply { bottomMargin = context.dp(20) },
            )
        }

        folderRow = LinearLayout(context).apply {
            orientation = if (browseOnly) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (browseOnly) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
        }

        if (browseOnly) {
            emptyHint = TextView(context).apply {
                text = "这个文件夹还没有图片"
                setTextColor(colors.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                visibility = View.GONE
                setPadding(0, context.dp(24), 0, context.dp(24))
            }
            imageGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val gridBox = FrameLayout(context).apply {
                addView(
                    imageGrid,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    emptyHint,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
            }
            val split = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            split.addView(
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    addView(
                        folderRow,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
                LinearLayout.LayoutParams(context.dp(56), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = context.dp(8)
                },
            )
            split.addView(
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    addView(
                        gridBox,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
            root.addView(
                split,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ).apply { bottomMargin = context.dp(16) },
            )
        } else {
            root.addView(
                TextView(context).apply {
                    text = "选择文件夹"
                    setTextColor(colors.muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = context.dp(10) },
            )
            root.addView(
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    clipToPadding = false
                    addView(
                        folderRow,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = context.dp(20) },
            )
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(
            filledButton("新建", colors.secondary, colors.onSecondary) {
                showCreateFolder()
            },
            LinearLayout.LayoutParams(0, context.dp(48), 1f).apply {
                marginEnd = if (browseOnly) 0 else context.dp(12)
            },
        )
        if (!browseOnly) {
            actions.addView(
                filledButton("保存", colors.primary, colors.onPrimary) {
                    saveCurrent()
                },
                LinearLayout.LayoutParams(0, context.dp(48), 1f),
            )
        }
        root.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val panelHeight = if (browseOnly) {
            (context.resources.displayMetrics.heightPixels * 0.72f).toInt()
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        return FrameLayout(context).apply {
            setOnClickListener { dialog.dismiss() }
            addView(
                root.apply { setOnClickListener { } },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    panelHeight,
                    Gravity.BOTTOM,
                ),
            )
        }
    }

    private fun bindFolders() {
        folderRow.removeAllViews()
        val folders = ImageFolderStore.folders(includeExternal = browseOnly)
        if (folders.isEmpty()) {
            selected[0] = null
            folderRow.addView(
                TextView(context).apply {
                    text = "还没有文件夹，点新建"
                    setTextColor(colors.muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(context.dp(4), context.dp(12), context.dp(4), context.dp(12))
                },
            )
            bindImages(null)
            return
        }
        if (selected[0] == null || folders.none { it.absolutePath == selected[0]?.absolutePath }) {
            selected[0] = ImageFolderStore.lastFolder(includeExternal = browseOnly) ?: folders.first()
        }
        folders.forEach { folder ->
            folderRow.addView(folderCard(folder))
        }
        bindImages(selected[0])
    }

    private fun folderCard(folder: File): View {
        val checked = folder.absolutePath == selected[0]?.absolutePath
        val size = if (browseOnly) context.dp(40) else context.dp(56)
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(colors.preview, context.dp(16).toFloat())
            clipToOutline = true
            outlineProvider = roundedOutline(context.dp(16).toFloat())
            val cover = ImageFolderStore.coverFile(folder)
            val thumb = cover?.let { decodeThumb(it, size) }
            if (thumb != null) {
                setImageBitmap(thumb)
            } else {
                setImageResource(R.drawable.ic_save)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(colors.muted)
                setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
            }
        }
        val name = TextView(context).apply {
            text = folder.name
            setTextColor(if (checked) colors.primary else colors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = if (checked) {
                rounded(colors.selected, context.dp(18).toFloat(), colors.primary, context.dp(2))
            } else {
                Color.TRANSPARENT.toDrawable()
            }
            val padH = context.dp(8)
            val padV = context.dp(6)
            setPadding(padH, padV, padH, padV)
            addView(image, LinearLayout.LayoutParams(size, size))
            addView(
                name,
                LinearLayout.LayoutParams(
                    if (browseOnly) ViewGroup.LayoutParams.MATCH_PARENT else size + context.dp(8),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = context.dp(6)
                },
            )
            if (browseOnly) {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = context.dp(8) }
            }
            setOnClickListener {
                selected[0] = folder
                ImageFolderStore.remember(folder)
                bindFolders()
            }
        }
    }

    private fun bindImages(folder: File?) {
        val grid = imageGrid ?: return
        val hint = emptyHint ?: return
        grid.removeAllViews()
        val files = folder?.let(ImageFolderStore::images).orEmpty()
        hint.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        if (files.isEmpty()) return

        val host = (grid.parent as? View) ?: grid
        val fill = {
            grid.removeAllViews()
            val gap = context.dp(8)
            val columns = 5
            val paneWidth = listOf(host.width, grid.width)
                .firstOrNull { it > context.dp(120) }
                ?: (context.resources.displayMetrics.widthPixels - context.dp(104))
            val cell = ((paneWidth - gap * (columns - 1)) / columns).coerceAtLeast(context.dp(48))
            files.chunked(columns).forEach { rowFiles ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                rowFiles.forEachIndexed { index, file ->
                    val thumb = ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        background = rounded(colors.preview, context.dp(12).toFloat())
                        clipToOutline = true
                        outlineProvider = roundedOutline(context.dp(12).toFloat())
                        setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
                        setImageBitmap(decodeThumb(file, cell))
                    }
                    row.addView(
                        thumb,
                        LinearLayout.LayoutParams(cell, cell).apply {
                            if (index < columns - 1) marginEnd = gap
                        },
                    )
                }
                grid.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = gap },
                )
            }
        }
        if (host.width > context.dp(120)) fill() else host.post { fill() }
    }

    private fun loadPreview() {
        val preview = previewView ?: return
        val progress = previewProgress ?: return
        if (imageUrls.isEmpty()) {
            progress.visibility = View.GONE
            return
        }
        Thread({
            val result = runCatching { ImageDownloader.fetch(imageUrls) }
            preview.post {
                progress.visibility = View.GONE
                val downloaded = result.getOrNull()
                val bitmap = downloaded?.let { ImageDownloader.decode(it.bytes, maxSize = 720) }
                if (bitmap != null) {
                    pending[0] = downloaded
                    preview.setImageBitmap(bitmap)
                } else {
                    preview.setImageResource(android.R.drawable.ic_menu_report_image)
                }
                result.exceptionOrNull()?.let {
                    Log.error("加载聊天图片预览失败（${imageUrls.size} 个候选地址）", it)
                }
            }
        }, "QQZygisk-ImagePreview").start()
    }

    private fun showCreateFolder() {
        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val inputLayout = TextInputLayout(
            context,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle,
        ).apply {
            hint = "文件夹名称"
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val box = FrameLayout(context).apply {
            setPadding(context.dp(24), context.dp(8), context.dp(24), 0)
            addView(
                inputLayout,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("新建文件夹")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建", null)
            .create()
        input.doAfterTextChanged {
            inputLayout.error = null
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    selected[0] = ImageFolderStore.createFolder(input.text.toString())
                    bindFolders()
                    dialog.dismiss()
                }.onFailure {
                    Log.error("创建保存文件夹失败", it)
                    inputLayout.error = it.message ?: "无法创建文件夹"
                }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun saveCurrent() {
        val image = pending[0]
        val folder = selected[0]
        if (image == null) {
            Toast.makeText(context, "图片还在加载，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        if (folder == null) {
            Toast.makeText(context, "请先选择或新建文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            ImageFolderStore.saveImage(folder, image.bytes, image.extension)
        }.onSuccess {
            Toast.makeText(context, "已保存到「${folder.name}」", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Log.error("保存聊天图片失败", it)
            Toast.makeText(context, it.message ?: "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun filledButton(
        label: String,
        background: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            this.background = rounded(background, context.dp(16).toFloat())
            setOnClickListener { onClick() }
        }
    }

    private fun decodeThumb(file: File, sizePx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > sizePx || bounds.outHeight / sampleSize > sizePx) {
            sampleSize *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }.getOrNull()

    private fun rounded(
        color: Int,
        radius: Float = 0f,
        stroke: Int? = null,
        strokeWidth: Int = 0,
        topRadius: Float = radius,
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadii = floatArrayOf(
            topRadius, topRadius, topRadius, topRadius,
            radius, radius, radius, radius,
        )
        if (stroke != null && strokeWidth > 0) {
            setStroke(strokeWidth, stroke)
        }
    }

    private fun Int.toDrawable() = GradientDrawable().apply { setColor(this@toDrawable) }

    private fun roundedOutline(radius: Float) = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    private data class PanelColors(
        val surface: Int,
        val preview: Int,
        val selected: Int,
        val primary: Int,
        val onPrimary: Int,
        val secondary: Int,
        val onSecondary: Int,
        val onSurface: Int,
        val muted: Int,
        val handle: Int,
    ) {
        companion object {
            fun from(context: Context): PanelColors {
                val night = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                return if (night) {
                    PanelColors(
                        surface = 0xFF1C1412.toInt(),
                        preview = 0xFF2A211F.toInt(),
                        selected = 0x332A211F,
                        primary = 0xFFFFB5A0.toInt(),
                        onPrimary = 0xFF3F160A.toInt(),
                        secondary = 0xFF3A2E2B.toInt(),
                        onSecondary = 0xFFF1DFDA.toInt(),
                        onSurface = 0xFFF6E8E4.toInt(),
                        muted = 0xFFC9B4AE.toInt(),
                        handle = 0x55C9B4AE,
                    )
                } else {
                    PanelColors(
                        surface = 0xFFFFFBFA.toInt(),
                        preview = 0xFFF4E8E4.toInt(),
                        selected = 0x14C06A4E,
                        primary = 0xFF8F4C38.toInt(),
                        onPrimary = 0xFFFFFFFF.toInt(),
                        secondary = 0xFFF0E2DD.toInt(),
                        onSecondary = 0xFF4A3833.toInt(),
                        onSurface = 0xFF1F1614.toInt(),
                        muted = 0xFF7A6863.toInt(),
                        handle = 0x447A6863,
                    )
                }
            }
        }
    }

    companion object {
        fun show(host: Context, imageUrls: List<String> = emptyList()) {
            host.injectModuleAppResources()
            val moduleLoader = SaveImagePanel::class.java.classLoader ?: host.classLoader
            val themed = object : ContextThemeWrapper(host, R.style.Theme_QQZygisk_MaterialDialog) {
                override fun getClassLoader(): ClassLoader = moduleLoader
            }
            SaveImagePanel(themed, imageUrls).show()
        }
    }
}

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
