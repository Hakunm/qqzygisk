package com.qm.qqzygisk.hook.app.hooker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.extension.hookAll
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.injectModuleAppResources
import com.qm.qqzygisk.hook.utils.startModuleSettings
import java.io.ByteArrayOutputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object ChatMenuHooker : BaseHooker() {
    private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
    private const val MAX_PREVIEW_SIZE = 1280

    override val key = "chat_menu_entry"
    override val name = "聊天长按菜单入口"
    override val description = "仅在含图片的消息长按菜单中添加 QQ Zygisk"
    override val defaultEnabled = false

    private val enabled get() = HookSettings.isEnabled(key, defaultEnabled)
    private val menuItemId = View.generateViewId()
    private val listFields = ConcurrentHashMap<Class<*>, Field>()
    private val messageMethods = ConcurrentHashMap<Class<*>, Method>()
    private val msgRecordMethods = ConcurrentHashMap<Class<*>, Method>()
    private val elementsMethods = ConcurrentHashMap<Class<*>, Method>()
    private val elementGetterMethods = ConcurrentHashMap<ElementGetterKey, Method>()

    override fun initOnce() {
        val picElementType = "com.tencent.qqnt.kernel.nativeinterface.PicElement".toAppClass()
        val layoutClass = listOf(
            "com.tencent.qqnt.aio.menu.ui.QQCustomMenuExpandableLayout",
            "com.tencent.qqnt.aio.menu.ui.QQCustomMenuNoIconLayout",
        ).firstNotNullOfOrNull { className ->
            runCatching { Class.forName(className, false, appClassLoader) }.getOrNull()
        } ?: error("QQ chat menu layout class not found")

        val setMenuMethods = layoutClass.resolve().method {
            name = "setMenu"
        }
        check(setMenuMethods.isNotEmpty()) { "QQ chat menu setMenu method not found" }

        setMenuMethods.hookAll {
            before {
                if (!enabled) return@before
                runCatching {
                    addMenuItem(
                        layout = instance as View,
                        menuContainer = args[0] ?: return@runCatching,
                        elementType = picElementType,
                    )
                }.onFailure {
                    Log.error("Add QQ Zygisk chat menu item failed", it)
                }
            }
        }
        runCatching {
            NtImageRkeyProvider.installHook()
        }.onFailure {
            Log.error("Install NT image rkey hook failed", it)
        }
    }

    private fun addMenuItem(
        layout: View,
        menuContainer: Any,
        elementType: Class<*>,
    ) {
        val listField = listFields[menuContainer.javaClass] ?: findListField(menuContainer.javaClass)
            .also { listFields[menuContainer.javaClass] = it }
        @Suppress("UNCHECKED_CAST")
        val items = listField.get(menuContainer) as? MutableList<Any> ?: return
        if (items.isEmpty() || items.any(ChatMenuItemFactory::isGenerated)) return

        val template = items.first()
        val baseClass = findMenuItemBaseClass(template.javaClass)
        val messageMethod = messageMethods[baseClass] ?: baseClass.declaredMethods.first {
            it.parameterCount == 0 &&
                it.returnType.name == "com.tencent.mobileqq.aio.msg.AIOMsgItem"
        }.apply {
            isAccessible = true
            messageMethods[baseClass] = this
        }
        val message = messageMethod.invoke(template) ?: return
        val messageElement = findMessageElement(message, elementType) ?: return
        val methods = baseClass.declaredMethods.filter {
            it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers)
        }
        val stringMethods = methods.filter { it.returnType == String::class.java }
        val intMethods = methods.filter { it.returnType == Int::class.javaPrimitiveType }
        val clickMethods = methods.filter {
            it.returnType == Void.TYPE && Modifier.isAbstract(it.modifiers)
        }

        check(stringMethods.isNotEmpty()) { "Menu item title methods not found: ${baseClass.name}" }
        check(intMethods.size in 1..2) { "Unexpected menu item int methods: ${baseClass.name}" }
        check(clickMethods.size == 1) { "Unexpected menu item click methods: ${baseClass.name}" }

        val iconMethod = if (intMethods.size == 2) {
            findIconMethod(layout.context, items, intMethods) ?: intMethods.first()
        } else {
            null
        }
        val idMethod = intMethods.first { it != iconMethod }
        val iconId = layout.context.resources.getIdentifier(
            "qui_tuning",
            "drawable",
            layout.context.packageName,
        ).takeIf { it != 0 } ?: android.R.drawable.ic_menu_manage

        val item = ChatMenuItemFactory.create(
            baseClass = baseClass,
            message = message,
            title = "QQ Zygisk",
            icon = iconId,
            id = menuItemId,
            stringMethods = stringMethods,
            iconMethod = iconMethod,
            idMethod = idMethod,
            clickMethod = clickMethods.single(),
            callback = Runnable { showImageDialog(layout.context, messageElement) },
        )
        items.add(item)
    }

    private fun findMessageElement(message: Any, elementType: Class<*>): Any? {
        val getMsgRecord = msgRecordMethods[message.javaClass]
            ?: message.javaClass.getMethod("getMsgRecord").also {
                msgRecordMethods[message.javaClass] = it
            }
        val msgRecord = getMsgRecord.invoke(message) ?: return null
        val getElements = elementsMethods[msgRecord.javaClass]
            ?: msgRecord.javaClass.getMethod("getElements").also {
                elementsMethods[msgRecord.javaClass] = it
            }
        val elements = getElements.invoke(msgRecord) as? Iterable<*> ?: return null
        elements.forEach { element ->
            element ?: return@forEach
            val getterKey = ElementGetterKey(element.javaClass, elementType)
            val getElement = elementGetterMethods[getterKey]
                ?: element.javaClass.methods.first {
                    it.parameterCount == 0 && elementType.isAssignableFrom(it.returnType)
                }.also {
                    elementGetterMethods[getterKey] = it
                }
            getElement.invoke(element)?.let { return it }
        }
        return null
    }

    private data class ElementGetterKey(
        val elementClass: Class<*>,
        val elementType: Class<*>,
    )

    private fun findListField(containerClass: Class<*>): Field {
        return generateSequence(containerClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .first { List::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }
    }

    private fun findMenuItemBaseClass(itemClass: Class<*>): Class<*> {
        var candidate = superClassOf(itemClass)
        while (candidate != null) {
            val hasExpectedConstructor = candidate.declaredConstructors.any {
                it.parameterCount == 1 &&
                    it.parameterTypes[0].name == "com.tencent.mobileqq.aio.msg.AIOMsgItem"
            }
            if (hasExpectedConstructor && candidate.declaredMethods.any { Modifier.isAbstract(it.modifiers) }) {
                return candidate
            }
            candidate = superClassOf(candidate)
        }
        error("QQ menu item base class not found: ${itemClass.name}")
    }

    private fun superClassOf(type: Class<*>): Class<*>? = type.superclass

    private fun findIconMethod(
        context: Context,
        items: List<Any>,
        methods: List<Method>,
    ): Method? {
        methods.forEach { method ->
            method.isAccessible = true
            items.forEach { item ->
                val value = runCatching { method.invoke(item) as Int }.getOrNull() ?: return@forEach
                val type = runCatching { context.resources.getResourceTypeName(value) }.getOrNull()
                if (type == "drawable" || type == "mipmap") return method
            }
        }
        return null
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
            Log.error("Show image action dialog failed", it)
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
            val result = runCatching { downloadPreview(imageUrls) }
            imageView.post {
                progress.visibility = View.GONE
                result.getOrNull()?.let(imageView::setImageBitmap)
                    ?: imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                result.exceptionOrNull()?.let {
                    Log.error("Load chat image preview failed (${imageUrls.size} candidates)", it)
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

    private fun downloadPreview(imageUrls: List<String>): Bitmap {
        val failure = IllegalStateException("All image preview requests failed")
        imageUrls.forEach { imageUrl ->
            runCatching { return downloadPreview(imageUrl) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
        }
        throw failure
    }

    private fun downloadPreview(imageUrl: String): Bitmap {
        val connection = URL(imageUrl).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 QQZygisk")
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Image request failed: HTTP ${connection.responseCode}"
            }
            val contentLength = connection.contentLengthLong
            check(contentLength < 0 || contentLength <= MAX_IMAGE_BYTES) {
                "Image is too large: $contentLength bytes"
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= MAX_IMAGE_BYTES) { "Image exceeds $MAX_IMAGE_BYTES bytes" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            decodePreview(bytes) ?: error("Image data could not be decoded")
        } finally {
            connection.disconnect()
        }
    }

    private fun decodePreview(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MAX_PREVIEW_SIZE ||
            bounds.outHeight / sampleSize > MAX_PREVIEW_SIZE
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

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun openSettings(context: Context) {
        runCatching {
            context.startModuleSettings()
        }.onFailure {
            Log.error("Open QQ Zygisk settings from chat menu failed", it)
            Toast.makeText(context, "无法打开 QQ Zygisk 设置", Toast.LENGTH_SHORT).show()
        }
    }
}
