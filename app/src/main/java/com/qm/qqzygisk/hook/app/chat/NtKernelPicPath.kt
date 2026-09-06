package com.qm.qqzygisk.hook.app.chat

import com.qm.qqzygisk.hook.utils.Log
import java.io.File

/**
 * 用 QQ 内核 [assembleMobileQQRichMediaFilePath] 把 PicElement 还原成本地缓存路径。
 */
internal object NtKernelPicPath {
    private const val ELEMENT_PIC = 2
    private const val INFO_CLASS = "com.tencent.qqnt.kernel.nativeinterface.RichMediaFilePathInfo"

    fun assemble(picElement: Any): List<String> {
        val service = NtMsgAccess.kernelMsgService() ?: run {
            Log.info("内核消息服务不可用，跳过 assembleMobileQQRichMediaFilePath")
            return emptyList()
        }
        val infoType = NtMsgAccess.loadClass(INFO_CLASS) ?: run {
            Log.info("没有 RichMediaFilePathInfo，跳过内核路径")
            return emptyList()
        }
        val methods = service.javaClass.methods.filter { method ->
            method.parameterCount == 1 &&
                method.parameterTypes[0].name == INFO_CLASS &&
                method.returnType == String::class.java
        }
        if (methods.isEmpty()) {
            Log.info("内核没有返回路径的 RichMediaFilePathInfo 方法")
            return emptyList()
        }
        val fileName = stringOf(picElement, "fileName", "getFileName")
        val fileUuid = stringOf(picElement, "fileUuid", "getFileUuid")
        val md5 = stringOf(picElement, "md5HexStr", "getMd5HexStr")
            ?: stringOf(picElement, "originImageMd5", "getOriginImageMd5")
        val contextBytes = bytesOf(picElement, "importRichMediaContext", "getImportRichMediaContext")
        val inApp = boolOf(picElement, "isInApplicationDataPath", "getIsInApplicationDataPath")
        val subType = intOf(picElement, "picSubType", "getPicSubType") ?: 0
        val found = linkedSetOf<String>()
        val missing = linkedSetOf<String>()
        for (downloadType in intArrayOf(0, 1, 2)) {
            for (thumbSize in intArrayOf(0, 198, 720)) {
                if (downloadType == 0 && thumbSize != 0) continue
                if (downloadType != 0 && thumbSize == 0 && downloadType == 1) continue
                val info = runCatching { infoType.getDeclaredConstructor().newInstance() }.getOrNull()
                    ?: return emptyList()
                setInt(info, "elementType", ELEMENT_PIC)
                setInt(info, "elementSubType", subType)
                setInt(info, "downloadType", downloadType)
                setInt(info, "thumbSize", thumbSize)
                setValue(info, "fileName", fileName)
                setValue(info, "fileUuid", fileUuid)
                setValue(info, "md5HexStr", md5)
                setValue(info, "importRichMediaContext", contextBytes)
                setValue(info, "isInApplicationDataPath", inApp)
                setBoolean(info, "needCreate", false)
                methods.forEach { method ->
                    method.isAccessible = true
                    val path = runCatching { method.invoke(service, info) as? String }.getOrNull()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: return@forEach
                    if (File(path).isFile) {
                        found += path
                    } else {
                        missing += path
                    }
                }
            }
        }
        Log.info(
            "内核拼路径 existing=${found.size} missing=${missing.size} " +
                "hit=${found.take(3)} miss=${missing.take(3)}",
        )
        return found.toList()
    }

    private fun stringOf(instance: Any, vararg names: String): String? =
        names.firstNotNullOfOrNull { name ->
            NtMsgAccess.asString(NtMsgAccess.read(instance, name))?.takeIf { it.isNotBlank() }
        }

    private fun intOf(instance: Any, vararg names: String): Int? =
        names.firstNotNullOfOrNull { name -> NtMsgAccess.asInt(NtMsgAccess.read(instance, name)) }

    private fun boolOf(instance: Any, vararg names: String): Boolean? =
        names.firstNotNullOfOrNull { name ->
            when (val value = NtMsgAccess.read(instance, name)) {
                is Boolean -> value
                else -> null
            }
        }

    private fun bytesOf(instance: Any, vararg names: String): ByteArray? =
        names.firstNotNullOfOrNull { name -> NtMsgAccess.read(instance, name) as? ByteArray }

    private fun setValue(target: Any, name: String, value: Any?) {
        if (value == null) return
        runCatching {
            generateSequence(target.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { it.name == name }
                ?.apply { isAccessible = true }
                ?.set(target, value)
        }
    }

    private fun setInt(target: Any, name: String, value: Int) = setValue(target, name, value)

    private fun setBoolean(target: Any, name: String, value: Boolean) = setValue(target, name, value)
}
