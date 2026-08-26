package com.qm.qqzygisk.hook.app.chat

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.utils.AnimatedImageLoader
import com.qm.qqzygisk.hook.utils.ImageDownloader
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.injectModuleAppResources
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class ImagePanelAction(
    val id: String,
    val label: String,
    val iconRes: Int,
    val contentDescription: String = label,
    val perform: (File) -> Result<Unit>,
)

/**
 * 自定义图片面板：浏览本地文件夹，或从聊天保存图片。
 */
class SaveImagePanel private constructor(
    private val context: Context,
    private val imageUrls: List<String>,
    private val actions: List<ImagePanelAction>,
    initialActionId: String?,
    private val onActionSelected: ((String) -> Unit)?,
) {
    private val colors = PanelColors.from(context)
    private val pending = arrayOfNulls<ImageDownloader.DownloadedImage>(1)
    private val selected = arrayOfNulls<File>(1)
    private lateinit var titleView: TextView
    private lateinit var folderRow: LinearLayout
    private var imageGrid: GridView? = null
    private var emptyHint: TextView? = null
    private var previewView: ImageView? = null
    private var previewProgress: ProgressBar? = null
    private var panelDialog: Dialog? = null
    private var previewDialog: Dialog? = null
    private var performingAction = false
    private var selectedAction = actions.firstOrNull { it.id == initialActionId }
        ?: actions.firstOrNull()
    private var actionButton: ImageButton? = null
    private val thumbnailExecutor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "QHook-ImageThumbnail").apply { isDaemon = true }
    }
    private val folderCoverExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "QHook-FolderCover").apply { isDaemon = true }
    }
    private val thumbnailCache = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }
    private val thumbnailRequests = ConcurrentHashMap.newKeySet<String>()
    private var thumbnailRefreshPosted = false
    private var thumbnailRefreshGeneration = 0
    @Volatile
    private var imageGeneration = 0
    @Volatile
    private var closed = false
    private val browseOnly get() = imageUrls.isEmpty()

    fun show() {
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        panelDialog = dialog
        dialog.setContentView(buildContent(dialog))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            closed = true
            imageGeneration++
            thumbnailExecutor.shutdownNow()
            folderCoverExecutor.shutdownNow()
            thumbnailRequests.clear()
            synchronized(thumbnailCache) { thumbnailCache.evictAll() }
            previewDialog?.dismiss()
            previewDialog = null
            panelDialog = null
        }
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
            val pad = context.dp(PANEL_HORIZONTAL_PADDING)
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

        titleView = TextView(context).apply {
            text = if (browseOnly) "图片面板" else "保存图片"
            setTextColor(colors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                titleView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = context.dp(8)
                },
            )
            if (browseOnly && actions.size > 1) {
                addView(
                    createActionMenuButton(),
                    LinearLayout.LayoutParams(context.dp(48), context.dp(48)),
                )
            }
            addView(
                createFolderButton(),
                LinearLayout.LayoutParams(context.dp(48), context.dp(48)),
            )
        }
        root.addView(
            titleRow,
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
            imageGrid = GridView(context).apply {
                numColumns = IMAGE_COLUMNS
                stretchMode = GridView.NO_STRETCH
                gravity = Gravity.START
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                onItemClickListener = AdapterView.OnItemClickListener { parent, _, position, _ ->
                    (parent.adapter.getItem(position) as? File)?.let(::performAction)
                }
                onItemLongClickListener = AdapterView.OnItemLongClickListener { parent, _, position, _ ->
                    val file = parent.adapter.getItem(position) as? File
                        ?: return@OnItemLongClickListener false
                    showEmoticonPreview(file)
                    true
                }
            }
            val gridBox = FrameLayout(context).apply {
                addView(
                    imageGrid,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
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
                LinearLayout.LayoutParams(
                    context.dp(FOLDER_COLUMN_WIDTH),
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    marginEnd = context.dp(FOLDER_GRID_GAP)
                },
            )
            split.addView(
                gridBox,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
            root.addView(
                split,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
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

        if (!browseOnly) {
            root.addView(
                filledButton("保存", colors.primary, colors.onPrimary) {
                    saveCurrent()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(48),
                ),
            )
        }

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
            if (browseOnly) titleView.text = "图片面板"
            folderRow.addView(
                TextView(context).apply {
                    text = "还没有文件夹"
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
        if (browseOnly) {
            titleView.text = selected[0]?.let(ImageFolderStore::displayName) ?: "图片面板"
        }
        folders.forEach { folder ->
            folderRow.addView(folderCard(folder))
        }
        bindImages(selected[0])
    }

    private fun folderCard(folder: File): View {
        val checked = folder.absolutePath == selected[0]?.absolutePath
        val size = if (browseOnly) context.dp(40) else context.dp(56)
        val displayName = ImageFolderStore.displayName(folder)
        val image = ImageView(context).apply {
            contentDescription = displayName
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(colors.preview, context.dp(16).toFloat())
            clipToOutline = true
            outlineProvider = roundedOutline(context.dp(16).toFloat())
            setImageResource(
                if (ImageFolderStore.isHistoryFolder(folder)) {
                    R.drawable.ic_history
                } else {
                    R.drawable.ic_save
                },
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(colors.muted)
            setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
        }
        if (!ImageFolderStore.isHistoryFolder(folder)) {
            loadFolderCover(folder, size, image)
        }
        return LinearLayout(context).apply {
            contentDescription = displayName
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
            if (!browseOnly) {
                addView(
                    TextView(context).apply {
                        text = displayName
                        setTextColor(if (checked) colors.primary else colors.onSurface)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(
                        size + context.dp(8),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = context.dp(6)
                    },
                )
            }
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
        val generation = ++imageGeneration
        grid.adapter = null
        val files = folder?.let(ImageFolderStore::images).orEmpty()
        hint.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        if (files.isEmpty()) return

        val fill = fill@{
            if (closed || generation != imageGeneration) return@fill
            val gap = context.dp(IMAGE_GAP)
            val paneWidth = grid.width
                .takeIf { it > context.dp(120) }
                ?: (
                    context.resources.displayMetrics.widthPixels -
                        context.dp(
                            PANEL_HORIZONTAL_PADDING * 2 +
                                FOLDER_COLUMN_WIDTH +
                                FOLDER_GRID_GAP,
                        )
                    )
            val cell = ((paneWidth - gap * (IMAGE_COLUMNS - 1)) / IMAGE_COLUMNS)
                .coerceAtLeast(context.dp(48))
            grid.columnWidth = cell
            grid.horizontalSpacing = gap
            grid.verticalSpacing = gap
            grid.adapter = ImageGridAdapter(files, cell, generation)
        }
        if (grid.width > context.dp(120)) fill() else grid.post { fill() }
    }

    private inner class ImageGridAdapter(
        private val files: List<File>,
        private val cellSize: Int,
        private val generation: Int,
    ) : BaseAdapter() {
        override fun getCount(): Int = files.size

        override fun getItem(position: Int): File = files[position]

        override fun getItemId(position: Int): Long = files[position].absolutePath.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val file = getItem(position)
            val image = (convertView as? ImageView) ?: ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(colors.preview, context.dp(12).toFloat())
                clipToOutline = true
                outlineProvider = roundedOutline(context.dp(12).toFloat())
                setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
            }
            image.layoutParams = AbsListView.LayoutParams(cellSize, cellSize)
            image.contentDescription = "发送 ${file.name}"
            loadImageThumbnail(file, cellSize, image, generation)
            return image
        }
    }

    private fun loadImageThumbnail(
        file: File,
        size: Int,
        image: ImageView,
        generation: Int,
    ) {
        val key = thumbnailKey(file, size)
        image.tag = key
        synchronized(thumbnailCache) { thumbnailCache.get(key) }?.let {
            AnimatedImageLoader.clear(image)
            image.setImageBitmap(it)
            return
        }
        AnimatedImageLoader.clear(image)
        val requestKey = "$generation:$key"
        if (!thumbnailRequests.add(requestKey)) return
        runCatching {
            thumbnailExecutor.execute {
                try {
                    if (closed || generation != imageGeneration) return@execute
                    val drawable = AnimatedImageLoader.decode(file, size) ?: return@execute
                    if (closed || generation != imageGeneration) return@execute
                    val bitmap = (drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        synchronized(thumbnailCache) { thumbnailCache.put(key, bitmap) }
                    }
                    publishThumbnail(key, drawable, bitmap, generation)
                } finally {
                    thumbnailRequests.remove(requestKey)
                }
            }
        }.onFailure {
            thumbnailRequests.remove(requestKey)
        }
    }

    private fun publishThumbnail(
        key: String,
        drawable: Drawable,
        bitmap: Bitmap?,
        generation: Int,
    ) {
        val grid = imageGrid ?: return
        grid.post {
            if (closed || generation != imageGeneration) return@post
            var applied = false
            for (index in 0 until grid.childCount) {
                val image = grid.getChildAt(index) as? ImageView ?: continue
                if (image.tag == key) {
                    if (bitmap != null) {
                        AnimatedImageLoader.clear(image)
                        image.setImageBitmap(bitmap)
                    } else {
                        AnimatedImageLoader.bind(image, drawable)
                    }
                    applied = true
                }
            }
            if (!applied) requestThumbnailRefresh(grid, generation)
        }
    }

    private fun requestThumbnailRefresh(grid: GridView, generation: Int) {
        thumbnailRefreshGeneration = generation
        if (thumbnailRefreshPosted) return
        thumbnailRefreshPosted = true
        grid.postOnAnimation {
            thumbnailRefreshPosted = false
            if (!closed && thumbnailRefreshGeneration == imageGeneration) {
                grid.invalidateViews()
            }
        }
    }

    private fun loadFolderCover(folder: File, size: Int, image: ImageView) {
        val key = "folder:${folder.absolutePath}:$size"
        image.tag = key
        val target = WeakReference(image)
        runCatching {
            folderCoverExecutor.execute {
                if (closed) return@execute
                val cover = ImageFolderStore.coverFile(folder) ?: return@execute
                val coverKey = thumbnailKey(cover, size)
                val cachedBitmap = synchronized(thumbnailCache) { thumbnailCache.get(coverKey) }
                val drawable = if (cachedBitmap == null) {
                    AnimatedImageLoader.decode(cover, size)
                } else {
                    null
                }
                val bitmap = cachedBitmap ?: (drawable as? BitmapDrawable)?.bitmap?.also {
                    synchronized(thumbnailCache) { thumbnailCache.put(coverKey, it) }
                }
                if (bitmap == null && drawable == null) return@execute
                target.get()?.post {
                    val view = target.get() ?: return@post
                    if (!closed && view.tag == key) {
                        view.clearColorFilter()
                        view.setPadding(0, 0, 0, 0)
                        view.scaleType = ImageView.ScaleType.CENTER_CROP
                        if (bitmap != null) {
                            AnimatedImageLoader.clear(view)
                            view.setImageBitmap(bitmap)
                        } else {
                            AnimatedImageLoader.bind(view, drawable!!)
                        }
                    }
                }
            }
        }
    }

    private fun thumbnailKey(file: File, size: Int): String =
        "${file.absolutePath}:${file.lastModified()}:${file.length()}:$size"

    private fun performAction(file: File) {
        val action = selectedAction ?: return
        if (performingAction) return
        performingAction = true
        action.perform(file)
            .onSuccess {
                ImageFolderStore.recordUsage(file)
                panelDialog?.dismiss()
            }
            .onFailure {
                performingAction = false
                Log.error("${action.label}失败: ${file.absolutePath}", it)
                Toast.makeText(context, it.message ?: "${action.label}失败", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showEmoticonPreview(file: File) {
        val preview = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            background = rounded(colors.preview, context.dp(20).toFloat())
            clipToOutline = true
            outlineProvider = roundedOutline(context.dp(20).toFloat())
            minimumHeight = context.dp(200)
        }
        val drawable = AnimatedImageLoader.decode(file, maxSize = 720)
        if (drawable != null) {
            AnimatedImageLoader.bind(preview, drawable)
        } else {
            preview.setImageResource(android.R.drawable.ic_menu_report_image)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.dp(20)
            setPadding(pad, context.dp(8), pad, context.dp(24))
            addView(
                preview,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(280),
                ).apply { bottomMargin = context.dp(20) },
            )
            addView(
                filledButton(
                    "删除",
                    ContextCompat.getColor(context, R.color.qqz_error),
                    ContextCompat.getColor(context, R.color.qqz_on_error),
                ) { confirmDeleteEmoticon(file) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(48),
                ),
            )
        }
        previewDialog?.dismiss()
        previewDialog = MaterialAlertDialogBuilder(context)
            .setTitle("表情预览")
            .setView(content)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    AnimatedImageLoader.clear(preview)
                    if (previewDialog === dialog) previewDialog = null
                }
                dialog.show()
            }
    }

    private fun confirmDeleteEmoticon(file: File) {
        val message = TextView(context).apply {
            text = "确定删除这个表情？删除后无法恢复。"
            setTextColor(colors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(12))
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("删除表情")
            .setView(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.86f).toInt()
                    .coerceAtLeast(context.dp(280)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val delete = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            delete.setTextColor(ContextCompat.getColor(context, R.color.qqz_error))
            delete.setOnClickListener {
                runCatching {
                    ImageFolderStore.deleteImage(file)
                }.onSuccess {
                    dialog.dismiss()
                    previewDialog?.dismiss()
                    bindFolders()
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Log.error("删除表情失败: ${file.absolutePath}", it)
                    Toast.makeText(context, it.message ?: "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun createActionMenuButton(): ImageButton {
        val selectableBackground = TypedValue().also {
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                it,
                true,
            )
        }.resourceId
        return ImageButton(context).apply {
            actionButton = this
            bindActionIcon(this)
            setColorFilter(colors.primary)
            setBackgroundResource(selectableBackground)
            val padding = context.dp(12)
            setPadding(padding, padding, padding, padding)
            disableEmptyLongClick()
            setOnClickListener { showActionMenu(it) }
        }
    }

    private fun bindActionIcon(button: ImageButton) {
        val action = selectedAction ?: return
        button.setImageResource(action.iconRes)
        button.contentDescription = action.contentDescription
    }

    private fun showActionMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            actions.forEachIndexed { index, action ->
                menu.add(0, index, index, action.label).isChecked = action === selectedAction
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { item ->
                val action = actions.getOrNull(item.itemId)
                    ?: return@setOnMenuItemClickListener false
                selectedAction = action
                onActionSelected?.invoke(action.id)
                actionButton?.let(::bindActionIcon)
                true
            }
            show()
        }
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
                val drawable = downloaded?.let { AnimatedImageLoader.decode(it.bytes, maxSize = 720) }
                if (drawable != null) {
                    pending[0] = downloaded
                    AnimatedImageLoader.bind(preview, drawable)
                } else {
                    preview.setImageResource(android.R.drawable.ic_menu_report_image)
                }
                result.exceptionOrNull()?.let {
                    Log.error("加载聊天图片预览失败（${imageUrls.size} 个候选地址）", it)
                }
            }
        }, "QHook-ImagePreview").start()
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

    private fun createFolderButton(): ImageButton {
        val selectableBackground = TypedValue().also {
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                it,
                true,
            )
        }.resourceId
        return ImageButton(context).apply {
            setImageResource(R.drawable.ic_create_folder)
            setColorFilter(colors.primary)
            setBackgroundResource(selectableBackground)
            contentDescription = "新建文件夹"
            val padding = context.dp(12)
            setPadding(padding, padding, padding, padding)
            disableEmptyLongClick()
            setOnClickListener { showCreateFolder() }
        }
    }

    private fun View.disableEmptyLongClick() {
        isHapticFeedbackEnabled = false
        isLongClickable = false
        setOnLongClickListener(null)
    }

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
                fun color(resourceId: Int) = ContextCompat.getColor(context, resourceId)

                val primary = color(R.color.qqz_primary)
                val onSurfaceVariant = color(R.color.qqz_on_surface_variant)
                return PanelColors(
                    surface = color(R.color.qqz_surface),
                    preview = color(R.color.qqz_surface_container_high),
                    selected = ColorUtils.setAlphaComponent(primary, 0x1f),
                    primary = primary,
                    onPrimary = color(R.color.qqz_on_primary),
                    secondary = color(R.color.qqz_secondary_container),
                    onSecondary = color(R.color.qqz_on_secondary_container),
                    onSurface = color(R.color.qqz_on_surface),
                    muted = onSurfaceVariant,
                    handle = ColorUtils.setAlphaComponent(onSurfaceVariant, 0x55),
                )
            }
        }
    }

    companion object {
        private const val IMAGE_COLUMNS = 5
        private const val PANEL_HORIZONTAL_PADDING = 12
        private const val FOLDER_COLUMN_WIDTH = 56
        private const val FOLDER_GRID_GAP = 6
        private const val IMAGE_GAP = 4
        private const val THUMBNAIL_CACHE_KB = 16 * 1024

        fun show(
            host: Context,
            imageUrls: List<String> = emptyList(),
            actions: List<ImagePanelAction> = emptyList(),
            initialActionId: String? = null,
            onActionSelected: ((String) -> Unit)? = null,
        ) {
            require(actions.map(ImagePanelAction::id).distinct().size == actions.size) {
                "图片操作 ID 不能重复"
            }
            host.injectModuleAppResources()
            ChatImageSender.captureFromContext(host)
            val moduleLoader = SaveImagePanel::class.java.classLoader ?: host.classLoader
            val themed = object : ContextThemeWrapper(host, R.style.Theme_QHook_MaterialDialog) {
                override fun getClassLoader(): ClassLoader = moduleLoader
            }
            SaveImagePanel(
                themed,
                imageUrls,
                actions,
                initialActionId,
                onActionSelected,
            ).show()
        }
    }
}

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
