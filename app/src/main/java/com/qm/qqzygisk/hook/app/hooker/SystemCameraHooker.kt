package com.qm.qqzygisk.hook.app.hooker

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Parcelable
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.base.IStartActivityHookDecorator
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.extension.MethodCall
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

object SystemCameraHooker : BaseHooker() {
    override val key = "system_camera"
    override val name = "使用系统相机"
    override val description = "仅替换拍照界面，不支持视频或 GIF"
    override val defaultEnabled = false

    private const val CAMERA_ACTIVITY =
        "com.tencent.aelight.camera.aebase.QIMCameraCaptureActivity"

    // QQ 当前版本里的混淆短名，来自能用的 classes.dex，不是模块自己的变量名。
    private const val EDITOR_ENTRANCE_CLASS = "ad.a"
    private const val EDITOR_ENTRANCE_BUILDER_CLASS = "ad.b\$a"
    private const val EDITOR_JUMP_CLASS = "xc.a"
    private const val PHOTO_CAPTURE_RESULT_CLASS =
        "com.tencent.aelight.camera.struct.camera.AEPhotoCaptureResult"

    private const val AECAMERA_MODE = "AECAMERA_MODE"
    private const val PHOTO_MODE = 200
    private const val REQUEST_CODE = 0x515A
    private const val EDITOR_REQUEST_CODE = 1012

    private const val DEFAULT_EDIT_VIDEO_TYPE = 10002
    private const val DEFAULT_TAKE_PHOTO_BUSINESS = 14
    private const val DEFAULT_CAMERA_PREFER_ID = 2
    private const val ENTRANCE_SECOND_ARG = 120
    private const val KEY_EDIT_VIDEO_TYPE = "edit_video_type"
    private const val KEY_TAKE_PHOTO_BUSINESS = "take_photo_business"
    private const val KEY_CAMERA_PREFER_ID = "key_camera_prefer_id"
    private const val KEY_AIO_CLASS = "ARG_AIO_CLASS"
    private const val KEY_SESSION_INFO = "ARG_SESSION_INFO"
    private const val KEY_QQ_SUB_BUSINESS_ID = "qq_sub_business_id"
    private const val PHOTO_SINGLE_PATH = "PhotoConst.SINGLE_PHOTO_PATH"
    private const val INPUT_FULL_SCREEN_MODE = "input_full_screen_mode"
    private const val INPUT_FULL_SCREEN_RESULT = "input_full_screen_mode_result"

    private const val CACHE_DIR = "qqzygisk/system-camera"
    private const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    // QQ declares .fileprovider twice (support + AndroidX). The external alias
    // resolves unambiguously to the AndroidX provider used by current QQ.
    private const val FILE_PROVIDER_SUFFIX = ".external.fileprovider"

    @Volatile
    private var editorApi: EditorApi? = null

    private val bypassSessions = Collections.synchronizedMap(
        WeakHashMap<Activity, BypassSession>(),
    )
    private val hookedResultMethods = Collections.synchronizedSet(mutableSetOf<String>())

    private enum class BypassPhase {
        CAMERA,
        EDITOR,
    }

    private data class EditorApi(
        val entranceClass: Class<*>,
        val entranceBuilderClass: Class<*>,
        val photoResultConstructor: Constructor<*>,
        val jumpToEditorMethod: Method,
    )

    private data class BypassSession(
        val launchIntent: Intent,
        val callerRequestCode: Int,
        val outputFile: File,
        val outputUri: Uri?,
        val editorApi: EditorApi,
        var phase: BypassPhase = BypassPhase.CAMERA,
    )

    internal val startActivityDecorator = object : IStartActivityHookDecorator() {
        override val key = "system_camera_start_dispatch"
        override val name = "SystemCameraStartDispatcher"
        override val isShow = false

        override fun onStartActivityIntent(intent: Intent, method: MethodCall): Boolean {
            return bypassQimCameraLaunch(intent, method)
        }
    }

    override fun initOnce() = Unit

    @Synchronized
    private fun requireEditorApi(): EditorApi {
        editorApi?.let { return it }

        val entranceClass = EDITOR_ENTRANCE_CLASS.toAppClass()
        val entranceBuilderClass = EDITOR_ENTRANCE_BUILDER_CLASS.toAppClass()
        val photoResultClass = PHOTO_CAPTURE_RESULT_CLASS.toAppClass()
        val intClass = Int::class.javaPrimitiveType!!
        val photoResultConstructor = photoResultClass.getDeclaredConstructor(
            intClass,
            intClass,
            String::class.java,
            Bitmap::class.java,
            Long::class.javaPrimitiveType!!,
            intClass,
        ).apply { isAccessible = true }

        val jumpToEditorMethod = EDITOR_JUMP_CLASS.toAppClass().declaredMethods.firstOrNull { method ->
            method.name == "c" &&
                method.parameterTypes.size == 5 &&
                method.parameterTypes[0] == Activity::class.java &&
                method.parameterTypes[1] == photoResultClass &&
                method.parameterTypes[2] == entranceClass
        }?.apply { isAccessible = true }
            ?: error("$EDITOR_JUMP_CLASS.c(Activity, AEPhotoCaptureResult, entrance, ...) was not found")

        return EditorApi(
            entranceClass = entranceClass,
            entranceBuilderClass = entranceBuilderClass,
            photoResultConstructor = photoResultConstructor,
            jumpToEditorMethod = jumpToEditorMethod,
        ).also {
            editorApi = it
            Log.info(
                "QQ image editor API resolved: result=${photoResultClass.name}, " +
                    "entrance=${entranceClass.name}, builder=${entranceBuilderClass.name}, " +
                    "jump=${jumpToEditorMethod.toGenericString()}",
            )
        }
    }

    private fun buildPhotoResult(api: EditorApi, file: File): Any {
        return api.photoResultConstructor.newInstance(
            0,
            0,
            file.absolutePath,
            null,
            System.currentTimeMillis(),
            readOrientation(file),
        )
    }

    private fun buildEntrance(api: EditorApi, activity: Activity, launchIntent: Intent): Any {
        val editVideoType = launchIntent.getIntExtra(KEY_EDIT_VIDEO_TYPE, DEFAULT_EDIT_VIDEO_TYPE)
        val takePhotoBusiness = launchIntent.getIntExtra(
            KEY_TAKE_PHOTO_BUSINESS,
            DEFAULT_TAKE_PHOTO_BUSINESS,
        )
        val preferId = launchIntent.getIntExtra(KEY_CAMERA_PREFER_ID, DEFAULT_CAMERA_PREFER_ID)
        val aioClass = launchIntent.getStringExtra(KEY_AIO_CLASS)
            ?.takeIf { it.isNotEmpty() }
            ?: activity.javaClass.name
        @Suppress("DEPRECATION")
        val sessionInfo = launchIntent.getParcelableExtra<Parcelable>(KEY_SESSION_INFO)

        val entrance = api.entranceClass.getDeclaredConstructor(
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
            .newInstance(editVideoType, ENTRANCE_SECOND_ARG, takePhotoBusiness)

        val builder = api.entranceBuilderClass.getDeclaredConstructor(
            Int::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
            .newInstance(preferId)

        invokeOneArg(builder, "j", sessionInfo)
        invokeOneArg(builder, "h", aioClass)
        invokeOneArg(builder, "i", 1)
        invokeOneArg(builder, "k", launchIntent.getIntExtra(KEY_QQ_SUB_BUSINESS_ID, 0))
        invokeOneArg(entrance, "c", invokeNoArg(builder, "g"))
        return entrance
    }

    private fun invokeNoArg(target: Any, name: String): Any? {
        val method = target.javaClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == name && candidate.parameterTypes.isEmpty()
        } ?: error("${target.javaClass.name}.$name() was not found")
        method.isAccessible = true
        return method.invoke(target)
    }

    private fun invokeOneArg(target: Any, name: String, value: Any?) {
        val method = target.javaClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == name && candidate.parameterTypes.size == 1
        } ?: error("${target.javaClass.name}.$name(...) was not found")
        method.isAccessible = true
        method.invoke(target, value)
    }

    private fun bypassQimCameraLaunch(intent: Intent, method: MethodCall): Boolean {
        val caller = runCatching { method.instance as? Activity }.getOrNull() ?: return false
        val targetClass = intent.component?.className
            ?: intent.resolveActivity(caller.packageManager)?.className
        if (targetClass != CAMERA_ACTIVITY ||
            !HookSettings.isEnabled(key, defaultEnabled) ||
            intent.getIntExtra(AECAMERA_MODE, PHOTO_MODE) != PHOTO_MODE
        ) {
            return false
        }

        val intentIndex = method.args.indexOfFirst { argument -> argument === intent }
        val callerRequestCode = method.args
            .drop(intentIndex + 1)
            .firstOrNull { argument -> argument is Int } as? Int
            ?: run {
                Log.warn("QIM camera launch has no request code; refusing to create QIM activity")
                method.result = null
                return true
            }

        if (bypassSessions.containsKey(caller)) {
            Log.warn("Ignoring duplicate QIM camera launch from ${caller.javaClass.name}")
            method.result = null
            return true
        }

        return runCatching {
            val editorApi = requireEditorApi()
            hookActivityResult(caller)
            pruneCache(caller)
            val outputFile = newOutputFile(caller)
            val outputUri = createCameraOutput(caller, outputFile)
            val captureIntent = createCaptureIntent(outputUri)
            grantCameraOutputPermission(caller, outputUri, captureIntent)
            bypassSessions[caller] = BypassSession(
                launchIntent = Intent(intent),
                callerRequestCode = callerRequestCode,
                outputFile = outputFile,
                outputUri = outputUri,
                editorApi = editorApi,
            )

            Log.info(
                "Bypassing $CAMERA_ACTIVITY: caller=${caller.javaClass.name}, " +
                    "requestCode=$callerRequestCode",
            )
            Log.info(
                "Launching system camera without QIM activity: " +
                    "uri=${outputUri ?: "<thumbnail>"}, path=${outputFile.absolutePath}",
            )
            try {
                caller.startActivityForResult(captureIntent, REQUEST_CODE)
            } catch (t: Throwable) {
                bypassSessions.remove(caller)
                throw t
            }
            method.result = null
            true
        }.onFailure {
            Log.error("Failed to bypass QIM camera launch; QIM activity will not be created", it)
            Toast.makeText(caller, "无法启动系统相机", Toast.LENGTH_SHORT).show()
            method.result = null
        }.getOrElse { true }
    }

    private fun hookActivityResult(activity: Activity) {
        val resultMethod = findActivityResultMethod(activity.javaClass)
            ?: error("onActivityResult was not found for ${activity.javaClass.name}")
        val hookKey = resultMethod.toGenericString()
        if (!hookedResultMethods.add(hookKey)) return

        try {
            resultMethod.declaringClass.resolve()
                .firstMethod {
                    name = "onActivityResult"
                    parameters(
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        Intent::class.java,
                    )
                }
                .hook {
                    before {
                        val caller = instance as? Activity ?: return@before
                        handleBypassActivityResult(caller, this)
                    }
                }
            Log.debug("Hooked camera result host: $hookKey")
        } catch (t: Throwable) {
            hookedResultMethods.remove(hookKey)
            throw t
        }
    }

    private fun findActivityResultMethod(activityClass: Class<*>): Method? {
        var currentClass: Class<*>? = activityClass
        while (currentClass != null) {
            currentClass.declaredMethods.firstOrNull { method ->
                method.name == "onActivityResult" &&
                    method.parameterTypes.contentEquals(
                        arrayOf(
                            Int::class.javaPrimitiveType!!,
                            Int::class.javaPrimitiveType!!,
                            Intent::class.java,
                        ),
                    )
            }?.let { return it }
            currentClass = currentClass.superclass
        }
        return null
    }

    private fun handleBypassActivityResult(activity: Activity, method: MethodCall) {
        val session = bypassSessions[activity] ?: return
        val requestCode = method.args.getOrNull(0) as? Int ?: return
        when {
            session.phase == BypassPhase.CAMERA && requestCode == REQUEST_CODE -> {
                val resultCode = method.args.getOrNull(1) as? Int ?: Activity.RESULT_CANCELED
                val data = method.args.getOrNull(2) as? Intent
                if (handleBypassCameraResult(activity, session, resultCode, data)) {
                    method.result = null
                } else {
                    forwardResultToCaller(method, session, Activity.RESULT_CANCELED, data)
                }
            }

            session.phase == BypassPhase.EDITOR && requestCode == EDITOR_REQUEST_CODE -> {
                val resultCode = method.args.getOrNull(1) as? Int ?: Activity.RESULT_CANCELED
                val data = method.args.getOrNull(2) as? Intent
                val convertedData = if (resultCode == Activity.RESULT_OK) {
                    convertEditorResult(data)
                } else {
                    data
                }
                forwardResultToCaller(method, session, resultCode, convertedData)
            }
        }
    }

    /** Returns true when the module consumed the camera result by opening QQ's editor. */
    private fun handleBypassCameraResult(
        activity: Activity,
        session: BypassSession,
        resultCode: Int,
        data: Intent?,
    ): Boolean {
        Log.info(
            "System camera result without QIM: resultCode=$resultCode, " +
                "dataUri=${data?.data}, outputUri=${session.outputUri}, " +
                "outputPath=${session.outputFile.absolutePath}",
        )
        return runCatching {
            val hasImage = materializeResult(
                activity,
                session.outputUri,
                data,
                session.outputFile,
            )
            if (!hasImage) {
                if (resultCode != Activity.RESULT_OK) {
                    Log.info("System camera canceled without a usable image")
                    bypassSessions.remove(activity)
                    return false
                }
                error("System camera returned no image")
            }
            if (resultCode != Activity.RESULT_OK) {
                Log.warn(
                    "System camera returned resultCode=$resultCode but wrote a valid image; " +
                        "continuing with the image",
                )
            }

            val photoResult = buildPhotoResult(session.editorApi, session.outputFile)
            val entrance = buildEntrance(session.editorApi, activity, session.launchIntent)
            session.phase = BypassPhase.EDITOR
            Log.info("Opening QQ image editor without QIM activity: ${session.outputFile}")
            session.editorApi.jumpToEditorMethod.invoke(
                null,
                activity,
                photoResult,
                entrance,
                null,
                0,
            )
            true
        }.onFailure {
            bypassSessions.remove(activity)
            Log.error("Failed to open QQ image editor without QIM activity", it)
            Toast.makeText(activity, "无法打开 QQ 图片编辑器", Toast.LENGTH_SHORT).show()
        }.getOrDefault(false)
    }

    private fun convertEditorResult(data: Intent?): Intent {
        val result = data ?: Intent()
        val path = result.getStringExtra(PHOTO_SINGLE_PATH).orEmpty()
        val fullScreenMode = result.getBooleanExtra(INPUT_FULL_SCREEN_MODE, false)
        result.putExtra(INPUT_FULL_SCREEN_RESULT, path)
        result.putExtra(INPUT_FULL_SCREEN_MODE, fullScreenMode)
        return result
    }

    private fun forwardResultToCaller(
        method: MethodCall,
        session: BypassSession,
        resultCode: Int,
        data: Intent?,
    ) {
        val activity = runCatching { method.instance as? Activity }.getOrNull()
        if (activity != null) bypassSessions.remove(activity)
        method.args[0] = session.callerRequestCode
        method.args[1] = resultCode
        method.args[2] = data
        Log.info(
            "Forwarding camera flow result to QQ: requestCode=${session.callerRequestCode}, " +
                "resultCode=$resultCode",
        )
    }

    private fun createCaptureIntent(outputUri: Uri?): Intent {
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            if (outputUri != null) {
                putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
                clipData = ClipData.newRawUri("qqzygisk-system-camera", outputUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }

    private fun materializeResult(
        activity: Activity,
        outputUri: Uri?,
        data: Intent?,
        outputFile: File,
    ): Boolean {
        if (isHostFileProviderUri(activity, outputUri)) {
            return waitForUsableImage(outputFile)
        }
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val resultUris = listOfNotNull(outputUri, data?.data).distinct()
        for (uri in resultUris) {
            Log.debug("Trying to read system camera uri: $uri")
            val copied = runCatching {
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    outputFile.outputStream().use { output -> input.copyTo(output) }
                }
                isUsableImage(outputFile)
            }.onFailure {
                Log.warn("Failed to copy system camera output: $uri", it)
            }.getOrDefault(false)
            if (copied) return true
            outputFile.delete()
        }

        @Suppress("DEPRECATION")
        val thumbnail = data?.extras?.getParcelable<Bitmap>("data") ?: return false
        outputFile.outputStream().use { output ->
            if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, 95, output)) return false
        }
        return isUsableImage(outputFile)
    }

    private fun isUsableImage(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    private fun waitForUsableImage(file: File): Boolean {
        val deadline = System.currentTimeMillis() + 2_000L
        do {
            if (isUsableImage(file)) return true
            if (System.currentTimeMillis() >= deadline) break
            runCatching { Thread.sleep(100L) }
        } while (true)
        return false
    }

    private fun createCameraOutput(activity: Activity, outputFile: File): Uri? {
        return runCatching {
            FileProvider.getUriForFile(
                activity,
                "${activity.packageName}$FILE_PROVIDER_SUFFIX",
                outputFile,
            )
        }.onSuccess {
            Log.info("Using QQ FileProvider output: $it")
        }.onFailure {
            Log.error("QQ FileProvider is unavailable; system camera output is disabled", it)
        }.getOrNull()
    }

    private fun grantCameraOutputPermission(
        activity: Activity,
        outputUri: Uri?,
        captureIntent: Intent,
    ) {
        if (!isHostFileProviderUri(activity, outputUri)) {
            return
        }
        val cameraPackage = captureIntent.resolveActivity(activity.packageManager)?.packageName
            ?: return
        runCatching {
            activity.grantUriPermission(
                cameraPackage,
                outputUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            Log.debug("Granted camera output URI to $cameraPackage")
        }.onFailure {
            Log.warn("Failed to grant camera output URI from QQ", it)
        }
    }

    private fun isHostFileProviderUri(activity: Activity, uri: Uri?): Boolean {
        return uri?.authority == "${activity.packageName}$FILE_PROVIDER_SUFFIX"
    }

    private fun newOutputFile(activity: Activity): File {
        val baseDirectory = activity.getExternalFilesDir(null) ?: activity.cacheDir
        val directory = File(baseDirectory, CACHE_DIR).apply { mkdirs() }
        return File(directory, "capture_${System.currentTimeMillis()}.jpg")
    }

    private fun pruneCache(activity: Activity) {
        val now = System.currentTimeMillis()
        val directories = listOfNotNull(
            File(activity.cacheDir, CACHE_DIR),
            activity.getExternalFilesDir(null)?.let { File(it, CACHE_DIR) },
        )
        directories.forEach { directory ->
            directory.listFiles()?.forEach { file ->
                if (now - file.lastModified() > CACHE_MAX_AGE_MS) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    private fun readOrientation(file: File): Int {
        return runCatching {
            when (
                ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_TRANSPOSE -> 90

                ExifInterface.ORIENTATION_ROTATE_180,
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180

                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSVERSE -> 270

                else -> 0
            }
        }.onFailure {
            Log.warn("Failed to read photo orientation: ${file.absolutePath}", it)
        }.getOrDefault(0)
    }
}
