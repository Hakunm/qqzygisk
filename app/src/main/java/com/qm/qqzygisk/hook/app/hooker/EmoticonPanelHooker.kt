package com.qm.qqzygisk.hook.app.hooker

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.RelativeLayout
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.chat.ChatImageSender
import com.qm.qqzygisk.hook.app.chat.ImageFolderStore
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.extension.MethodCall
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.extension.hookAll
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.get
import com.qm.qqzygisk.hook.utils.set
import org.lsposed.lsparanoid.Obfuscate
import java.io.File
import java.net.URL
import kotlin.properties.Delegates

object EmoticonPanelHooker : BaseHooker() {
    override val key = "emoticon_panel"
    override val name = "表情面板内置"

    private val enabled get() = HookSettings.isEnabled(key, defaultEnabled)

    val EmoticonPackage = "com.tencent.mobileqq.data.EmoticonPackage".toAppClass()
    val EmotionPanelInfo = "com.tencent.mobileqq.emoticonview.EmotionPanelInfo".toAppClass()
    val EmoticonPanelController = "com.tencent.mobileqq.emoticonview.EmoticonPanelController".toAppClass()
    val EmotionPanelViewPagerAdapter = "com.tencent.mobileqq.emoticonview.EmotionPanelViewPagerAdapter".toAppClass()
    val FavoriteEmoticonInfo = "com.tencent.mobileqq.emoticonview.FavoriteEmoticonInfo".toAppClass()
    val EmoticonTabAdapter = "com.tencent.mobileqq.emoticonview.EmoticonTabAdapter".toAppClass()

    val AbsBigEmotionUpdateAdapter = "com.tencent.mobileqq.emoticonview.AbsBigEmotionUpdateAdapter".toAppClass()

    var providers by Delegates.notNull<List<ExtraEmoticonProvider>>()
    var lastPanelDataSize = -1

    data class QZEpId (var providerId: String = "",var panelId: String)
    fun parseQZEpId(epId: String): QZEpId? {
        if (!epId.startsWith("qz:")) return null
        val data = epId.substring(3)
        val providerId = data.substring(0, data.indexOf(":"))
        val panelId = data.substring(data.indexOf(":") + 1)
        return QZEpId(providerId, panelId)
    }

    private fun generateTabUrl(method: MethodCall) {
        val epId = method.args[0] as? String? ?: return
        if (!epId.startsWith("qz:")) return
        val id = parseQZEpId(epId)
        if(id != null) {
            val provider = providers.find { it.uniqueId() == id.providerId }
            if(provider != null) {
                val panel = provider.extraEmoticonList().find { it.uniqueId() == id.panelId }
                if(panel != null) {
                    val url = panel.emoticonPanelIconURL()
                    method.result = url?.let(::URL)
                }
            }
        }
    }

    private fun getPanelDataList(method: MethodCall) {
        @Suppress("UNCHECKED_CAST")
        val result = method.result as? MutableList<Any>? ?: return

        val typeWhiteList = mutableSetOf(
//            13, // 表情商城,
            18, // 搜索表情,
            7, // Emoji 表情,
            4, // 收藏表情,
//            6, // 商店表情
//            12, // GIF
//            17, // QQ秀专属表情
            19, // 超级表情
//            20, // AI 表情
        )

        val iterator = result.iterator()
        val existingIds = mutableSetOf<String>()

        while(iterator.hasNext()) {
            val element = iterator.next()

            if(!typeWhiteList.contains(element.get<Int>("type")!!)) {
                if (element.get<Int>("type")!! == 6) {
                    val id: String = element.get<Any>("emotionPkg")!!.get("epId")!!
                    if(!id.startsWith("qz:")) iterator.remove()
                    existingIds.add(id)
                } else {
                    iterator.remove()
                }
            }
            else {
                if (element.get<Int>("type")!! == 6)
                    existingIds.add(element.get<Any>("emotionPkg")!!.get<String>("epId")!!)
            }
        }

        val infoObj = EmotionPanelInfo.resolve().firstConstructor{
            parameterCount = 3
            parameters(Int::class.java, Int::class.java, EmoticonPackage)
        }

        val packObj = EmoticonPackage.resolve().firstConstructor()

        for(provider in providers) {
            for(panel in provider.extraEmoticonList()) {
                val epId = "qz:${provider.uniqueId()}:${panel.uniqueId()}"
                if(existingIds.contains(epId)) continue
                val pack = packObj.create()
                pack.set("epId", epId)
                pack.set("name", "ExtraSticker")
                pack.set("type", 3)
                pack.set("ipJumpUrl", "")
                pack.set("ipDetail", "QZ")
                pack.set("valid", true)
                pack.set("status", 2)
                pack.set("latestVersion", 1488377358)
                pack.set("aio", true)
                val info = infoObj.create(6, 4, pack)
                result.add(info)
            }
        }
        val getPageAdapter = method.instance.asResolver().firstMethod { name = "getPageAdapter" }
        val adapter = getPageAdapter.invoke()
        if (adapter != null && lastPanelDataSize != result.size) {
            lastPanelDataSize = result.size
            val adapterResolver = adapter.asResolver()
            val notifyDataSetChanged = adapterResolver.firstMethod {
                name = "notifyDataSetChanged"
                superclass()
            }
            notifyDataSetChanged.invoke()
        }
        method.result = result
    }

    private fun getEmotionPanelData(method: MethodCall) {
        val emotionPanelInfo = method.args[2]!!
        @Suppress("UNCHECKED_CAST")
        val list = method.result as MutableList<Any>
        val pkg = emotionPanelInfo.get<Any>("emotionPkg") ?: return
        val epId = pkg.get<String>("epId") ?: return
        val id = parseQZEpId(epId)
        if(id != null) {
            val provider = providers.find { it.uniqueId() == id.providerId }
            if(provider != null) {
                val panel = provider.extraEmoticonList().find { it.uniqueId() == id.panelId }
                if(panel != null) {
                    val emoticons = panel.emoticons()
                    for(emoticon in emoticons) {
                        list.add(emoticon.QQEmoticonObject())
                    }
                }
            }
            method.result = list
        }
    }

    private fun handleIPSite(method: MethodCall) {
        val emotionPanelInfo = method.args[0]
        if(emotionPanelInfo != null && parseQZEpId(emotionPanelInfo.get<String>("epId")!!) != null) {
            method.result = null
            return
        }
    }

    private fun getDrawable(method: MethodCall) {
        val getZoomDrawable = method.instance.asResolver().firstMethod { name = "getZoomDrawable" }
        method.result = getZoomDrawable.invoke<Drawable>(method.args[0], method.args[1], 170, 170)
    }

    private fun updateBigEmotionContentViewData(method: MethodCall) {
        val view = method.args[0] as RelativeLayout
        val imageView = view.getChildAt(1) as ImageView
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    override fun initOnce() {
        val provider = LocalDocumentEmoticonProvider()
        providers = listOf(provider)
        ImageFolderStore.addListener {
            provider.invalidateCache()
            lastPanelDataSize = -1
        }

        EmoticonPanelController
            .resolve()
            .firstMethod { name = "getPanelDataList" }
            .hook {
                after {
                    if (enabled) getPanelDataList(this)
                }
            }

        EmotionPanelViewPagerAdapter
            .resolve()
            .firstMethod { name = "getEmotionPanelData" }
            .hook {
                after {
                    if (enabled) getEmotionPanelData(this)
                }
            }

        EmotionPanelViewPagerAdapter
            .resolve()
            .firstMethod { name = "handleIPSite" }
            .hook {
                before {
                    if (enabled) handleIPSite(this)
                }
            }

        FavoriteEmoticonInfo
            .resolve()
            .firstMethod { name = "getDrawable" }
            .hook {
                before {
                    if (enabled) getDrawable(this)
                }
            }

        EmoticonTabAdapter
            .resolve()
            .firstMethod { name = "generateTabUrl" }
            .hook {
                before {
                    if (enabled) generateTabUrl(this)
                }
            }

        AbsBigEmotionUpdateAdapter
            .resolve()
            .firstMethod { name = "updateBigEmotionContentViewData" }
            .hook {
                after {
                    if (enabled) updateBigEmotionContentViewData(this)
                }
            }

        FavoriteEmoticonInfo
            .resolve()
            .method { name = "send" }
            .hookAll {
                before {
                    if (!enabled) return@before
                    val path = instance.get<String>("path") ?: return@before
                    val file = File(path)
                    if (!ImageFolderStore.isLocalEmoticon(file)) return@before
                    args.forEach { arg ->
                        ChatImageSender.captureFrom(arg)
                        if (arg is Context) ChatImageSender.captureFromContext(arg)
                    }
                    ChatImageSender.captureFrom(instance)
                    val sent = ChatImageSender.sendImage(file, ChatImageSender.SendType.EMOTICON)
                    if (sent.isSuccess) {
                        ImageFolderStore.recordUsage(file)
                        result = null
                    } else {
                        Log.warn("内置表情改走本地发送失败，回退 QQ 原发送", sent.exceptionOrNull())
                    }
                }
                after {
                    if (!enabled) return@after
                    val path = instance.get<String>("path") ?: return@after
                    ImageFolderStore.recordUsage(File(path))
                }
            }
    }
}
