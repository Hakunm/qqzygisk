package com.qm.qqzygisk.hook.app.chat

import android.content.Context
import android.os.Looper
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 微信 8.0.79（code3182、3200）及 8.0.80（code3220）原生表情发送入口。
 * 注册表、服务接口、导入/回读/入队方法和缓存路径均由 [WeChatEmojiVariant] 指定；
 * 只接受已核对的完整反射协议，未适配结构在发送前失败。
 */
internal object WeChatEmoticonSender {
    /**
     * 返回值表示启动、协议或尺寸检查结果：超限静态 JPEG/PNG 生成临时发送副本，
     * 超限 GIF/WEBP 直接失败。[complete] 只在原生上传与消息发送进入终态
     * （或拒绝、异常、超时）后恰好回调一次；启动失败时不会调用 [complete]。
     */
    fun send(
        file: File,
        talker: String?,
        currentTalker: () -> String,
        complete: (Result<Unit>) -> Unit,
    ): Result<Unit> = runCatching {
        check(Looper.myLooper() === Looper.getMainLooper()) { "表情发送必须在主线程启动" }
        val context = checkNotNull(AppParasitics.appContext) { "宿主 Application 尚未初始化" }
        val protocol = WeChatEmojiProtocol.resolve(appClassLoader)
        val completion = WeChatEmoticonCompletion.forLoader(appClassLoader, protocol.lookup).getOrThrow()
        val source = WeChatEmoticonFile.inspect(file)
        // 超限静态图生成临时 PNG 副本；原生导入后副本即可删除，原文件始终不动。
        WeChatEmoticonSendImage.prepare(
            source,
            protocol.readSizeLimit(),
            WeChatEmoticonSendImage.directory(context),
        ).use { prepared ->
            WeChatEmoticonSubmission(NativeApi(context, protocol, completion))
                .send(prepared.source, talker, currentTalker, complete).getOrThrow()
        }
    }.recoverCatching { error ->
        throw if (error is InvocationTargetException) error.targetException else error
    }

    private class NativeApi(
        private val context: Context,
        private val protocol: WeChatEmojiProtocol,
        private val completion: WeChatEmoticonCompletion,
    ) : WeChatEmoticonApi {
        /** 协议解析不碰注册表实例；到这里才向注册表要服务，微信没就绪时原样失败。 */
        private val manager: Any by lazy {
            checkNotNull(protocol.lookup.invoke(null, protocol.apiClass)) { "微信原生表情服务尚未就绪" }
                .also { check(protocol.managerClass.isInstance(it)) { "微信原生表情服务实现已变化" } }
        }

        override fun importFile(file: WeChatEmoticonFile): ImportedWeChatEmoticon? {
            // 原生导入将原字节写入表情缓存，按文件头登记动态或静态类型。
            val md5 = protocol.importMethod.invoke(manager, context, file.file.absolutePath) as? String
            if (md5.isNullOrBlank()) return null
            val emoji = protocol.lookupMethod.invoke(manager, md5) ?: return null
            if (!(protocol.emojiMd5.invoke(emoji) as? String).equals(md5, ignoreCase = true)) return null
            val path = protocol.emojiPathGetter.invoke(emoji) as? String ?: return null
            return ImportedWeChatEmoticon(
                md5 = md5,
                size = (protocol.vfsLength.invoke(null, path) as Number).toLong(),
                nativeType = (protocol.emojiType.invoke(emoji) as Number).toInt(),
                readable = protocol.vfsExists.invoke(null, path) == true,
            )
        }

        override fun submit(talker: String, md5: String, complete: (Result<Unit>) -> Unit): Result<Unit> =
            // 原生发送只提交一次；完成由上传终态观察器回调。
            completion.submit(talker, md5, complete) {
                protocol.sendMethod.invoke(manager, context, talker, md5) == true
            }
    }
}

/**
 * 已核对的微信表情协议，按注册表全名唯一选择；不拼接候选或回退。
 */
internal class WeChatEmojiVariant private constructor(
    val registryClass: String,
    val serviceType: String,
    val pathGetter: String,
    val messageLookupClass: String,
    val messageLookupMethod: String,
    val limitClass: String,
    val apiClass: String = "com.tencent.mm.feature.emoji.api.z5",
    val importMethod: String = "zj",
    val lookupMethod: String = "Bj",
    val sendMethod: String = "Fj",
    val messageClass: String = "com.tencent.mm.storage.e9",
    val statusMethod: String = "M0",
    val terminalMethod: String = "Gj",
    val enqueueMethod: String? = null,
    val sendTask: Boolean = false,
) {
    companion object {
        const val LOOKUP_METHOD = "c"
        const val LIMIT_METHOD = "c"

        private val ALL = listOf(
            WeChatEmojiVariant(
                registryClass = "kj5.n0",
                serviceType = "kj5.m",
                pathGetter = "b1",
                messageLookupClass = "oy0.k0",
                messageLookupMethod = "Zi",
                limitClass = "nq.c",
            ),
            WeChatEmojiVariant(
                registryClass = "ni5.n0",
                serviceType = "ni5.m",
                pathGetter = "d1",
                messageLookupClass = "ox0.k0",
                messageLookupMethod = "Vi",
                limitClass = "op.c",
            ),
            WeChatEmojiVariant(
                registryClass = "fi5.n0",
                serviceType = "fi5.m",
                pathGetter = "b1",
                messageLookupClass = "yx0.k0",
                messageLookupMethod = "cj",
                limitClass = "rp.c",
                apiClass = "com.tencent.mm.feature.emoji.api.e6",
                importMethod = "Fj",
                lookupMethod = "Hj",
                sendMethod = "Lj",
                messageClass = "com.tencent.mm.storage.f9",
                statusMethod = "N0",
                terminalMethod = "Mj",
                enqueueMethod = "Vj",
                sendTask = true,
            ),
        )

        fun require(registry: Class<*>): WeChatEmojiVariant =
            ALL.singleOrNull { it.registryClass == registry.name }
                ?: error("未适配的微信表情服务注册表 ${registry.name}，已取消发送")

        fun verifyLookup(lookup: Method, variant: WeChatEmojiVariant) {
            check(
                Modifier.isStatic(lookup.modifiers) &&
                    lookup.name == LOOKUP_METHOD &&
                    lookup.parameterTypes.size == 1 &&
                    lookup.parameterTypes[0] == Class::class.java,
            ) {
                "微信表情服务注册表 ${lookup.declaringClass.name}.${lookup.name} 签名已变化"
            }
            check(lookup.returnType.name == variant.serviceType) {
                "微信表情服务注册表 ${lookup.declaringClass.name}.${lookup.name} " +
                    "返回 ${lookup.returnType.name}，期望 ${variant.serviceType}"
            }
        }
    }
}

/**
 * 微信原生表情服务的反射协议（只解析，不含实例）。
 *
 * 注册表由稳定标记接口的直接父接口推导；具体表情服务接口由变体指定。
 */
internal class WeChatEmojiProtocol private constructor(
    val apiClass: Class<*>,
    val lookup: Method,
    val managerClass: Class<*>,
    val importMethod: Method,
    val lookupMethod: Method,
    val sendMethod: Method,
    val emojiMd5: Method,
    val emojiType: Method,
    val emojiPathGetter: Method,
    val vfsExists: Method,
    val vfsLength: Method,
    val sizeLimit: Method,
) {
    /**
     * 读取宿主 `InputLimitSendEmotionWidth`（宽高同一上限）。默认值不能由模块假设，
     * 只在发送前向宿主查询；非正数视为协议不可用。
     */
    fun readSizeLimit(): Int {
        val value = (sizeLimit.invoke(null) as Number).toInt()
        check(value > 0) { "微信表情尺寸上限无效：$value" }
        return value
    }

    companion object {
        private const val MARKER_CLASS = "com.tencent.mm.feature.emoji.api.z5"
        private const val MANAGER_CLASS = "com.tencent.mm.feature.emoji.s0"
        private const val REGISTRY_NAME = "n0"
        private const val EMOJI_API_CLASS = "com.tencent.mm.api.IEmojiInfo"
        private const val VFS_CLASS = "com.tencent.mm.vfs.z6"

        fun resolve(loader: ClassLoader): WeChatEmojiProtocol {
            val markerClass = Class.forName(MARKER_CLASS, false, loader)
            val serviceType = markerClass.interfaces.singleOrNull()
                ?: error("微信 ${markerClass.name} 没有唯一的标记父接口")
            val packageName = serviceType.name.substringBeforeLast('.', "")
            check(packageName.isNotEmpty()) { "微信 ${serviceType.name} 没有包名" }

            val registry = Class.forName("$packageName.$REGISTRY_NAME", false, loader)
            val variant = WeChatEmojiVariant.require(registry)
            check(serviceType.name == variant.serviceType) {
                "微信 ${markerClass.name} 的父接口 ${serviceType.name} 与注册表 ${registry.name} 不匹配"
            }
            val lookup = registry.getMethod(WeChatEmojiVariant.LOOKUP_METHOD, Class::class.java)
            WeChatEmojiVariant.verifyLookup(lookup, variant)

            val apiClass = Class.forName(variant.apiClass, false, loader)
            check(apiClass.isInterface && apiClass.interfaces.singleOrNull() == serviceType) {
                "微信表情服务接口 ${apiClass.name} 的标记父接口已变化"
            }
            val managerClass = Class.forName(MANAGER_CLASS, false, loader)
            check(apiClass.isAssignableFrom(managerClass)) { "微信表情服务实现 ${managerClass.name} 已变化" }
            val emojiApi = Class.forName(EMOJI_API_CLASS, false, loader)
            val importMethod = managerClass.getMethod(variant.importMethod, Context::class.java, String::class.java)
            val lookupMethod = managerClass.getMethod(variant.lookupMethod, String::class.java)
            val sendMethod = managerClass.getMethod(
                variant.sendMethod, Context::class.java, String::class.java, String::class.java,
            )
            check(!Modifier.isStatic(importMethod.modifiers) && importMethod.returnType == String::class.java &&
                !Modifier.isStatic(lookupMethod.modifiers) && lookupMethod.returnType == emojiApi &&
                !Modifier.isStatic(sendMethod.modifiers) && sendMethod.returnType == java.lang.Boolean.TYPE) {
                "微信原生表情服务导入、回读或发送签名已变化"
            }

            val limitClass = Class.forName(variant.limitClass, false, loader)
            val sizeLimit = limitClass.getMethod(WeChatEmojiVariant.LIMIT_METHOD)
            check(
                Modifier.isStatic(sizeLimit.modifiers) &&
                    sizeLimit.parameterTypes.isEmpty() &&
                    sizeLimit.returnType == java.lang.Integer.TYPE,
            ) {
                "微信 ${variant.limitClass}.${sizeLimit.name} 签名已变化，期望 public static int"
            }

            val emojiMd5 = emojiApi.getMethod("getMd5")
            check(!Modifier.isStatic(emojiMd5.modifiers) && emojiMd5.returnType == String::class.java) {
                "微信 ${EMOJI_API_CLASS}.getMd5 签名已变化"
            }
            val emojiType = emojiApi.getMethod("getType")
            check(!Modifier.isStatic(emojiType.modifiers) && emojiType.returnType == java.lang.Integer.TYPE) {
                "微信 ${EMOJI_API_CLASS}.getType 签名已变化: ${emojiType.returnType.name}"
            }
            val emojiPathGetter = emojiApi.getMethod(variant.pathGetter)
            check(!Modifier.isStatic(emojiPathGetter.modifiers) && emojiPathGetter.parameterTypes.isEmpty() && emojiPathGetter.returnType == String::class.java) {
                "微信 ${EMOJI_API_CLASS}.${variant.pathGetter}() 形状已变化"
            }

            val vfs = Class.forName(VFS_CLASS, false, loader)
            val exists = vfs.getMethod("j", String::class.java)
            val length = vfs.getMethod("k", String::class.java)
            check(Modifier.isStatic(exists.modifiers) && Modifier.isStatic(length.modifiers) &&
                exists.returnType == java.lang.Boolean.TYPE && length.returnType == java.lang.Long.TYPE) {
                "微信 ${VFS_CLASS} 的 j/k 签名已变化"
            }

            return WeChatEmojiProtocol(
                apiClass = apiClass,
                lookup = lookup,
                managerClass = managerClass,
                importMethod = importMethod,
                lookupMethod = lookupMethod,
                sendMethod = sendMethod,
                emojiMd5 = emojiMd5,
                emojiType = emojiType,
                emojiPathGetter = emojiPathGetter,
                vfsExists = exists,
                vfsLength = length,
                sizeLimit = sizeLimit,
            )
        }
    }
}
