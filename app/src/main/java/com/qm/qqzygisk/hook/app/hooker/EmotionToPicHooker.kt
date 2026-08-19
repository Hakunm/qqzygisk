package com.qm.qqzygisk.hook.app.hooker

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log

/**
 * 点击收藏/动画表情时走普通看图器，而不是表情预览。
 */
object EmotionToPicHooker : BaseHooker() {
    override val key = "emotion_to_pic"
    override val name = "以图片方式打开表情"
    override val description = "点击收藏表情时用看图器打开，方便查看和保存"
    override val defaultEnabled = true

    private val enabled get() = HookSettings.isEnabled(key, defaultEnabled)

    override fun initOnce() {
        val api = "com.tencent.qqnt.aio.adapter.api.impl.RichMediaBrowserApiImpl".toAppClass()
        val resolver = api.resolve()

        val hookedFavPreview = runCatching {
            resolver.firstMethod { name = "checkIsFavPicAndShowPreview" }
                .hook {
                    before {
                        if (enabled) result = false
                    }
                }
        }.onFailure {
            Log.warn("挂钩 checkIsFavPicAndShowPreview 失败", it)
        }.isSuccess

        val hookedEnterPreview = runCatching {
            resolver.firstMethod {
                name = "enterImagePreview"
                parameterCount = 9
            }.hook {
                before {
                    if (enabled && args.size >= 9 && args[8] == true) {
                        args[8] = false
                    }
                }
            }
        }.onFailure {
            Log.warn("挂钩 enterImagePreview 失败", it)
        }.isSuccess

        check(hookedFavPreview || hookedEnterPreview) {
            "未找到以图片方式打开表情的接口"
        }
        Log.info("已挂钩以图片方式打开表情")
    }
}
