package com.qm.qqzygisk.hook.app.hooker

import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.qm.qqzygisk.hook.app.base.IStartActivityHookDecorator
import com.qm.qqzygisk.hook.extension.MethodCall
import com.qm.qqzygisk.hook.parasitic.AppParasitics.appContext
import com.qm.qqzygisk.hook.utils.HookSettings
import java.util.regex.Pattern

object FxxkQQBrowserHooker : IStartActivityHookDecorator() {
    override val key = "external_browser"
    override val name = "移除内置浏览器"

    private const val URL_REGEX = "^(https?|ftp)://[^\\s/$.?#].[^\\s]*$|^www\\.[^.]+\\.[^.]+$|^[^.]+\\.[^.]+$"

    private fun shouldUseInternalBrowserForUrl(url: String): Boolean {
        val body = if (url.contains("://")) {
            url.substring(url.indexOf("://") + 3)
        } else {
            url
        }.dropWhile { it == '/' } // https:///ti.qq.com 前面有多个/不影响跳转，给腾讯擦屁股
        val host = if (body.contains("/")) {
            body.substring(0, body.indexOf("/"))
        } else {
            body
        }.lowercase()
        return (host.endsWith("qq.com") && !host.contains("mp.weixin.qq.com"))
                || host.endsWith("tenpay.com")
                || host.endsWith("meeting.tencent.com")
                || host == "qq-web.cdn-go.cn" // for CAPTCHA https://qq-web.cdn-go.cn/captcha_cdn-go/latest/captcha.html
    }

    override fun onStartActivityIntent(intent: Intent, method: MethodCall): Boolean {
        if (!HookSettings.isEnabled(key, defaultEnabled)) return false
        val url = intent.getStringExtra("url")
        if (!url.isNullOrBlank()
            && url.lowercase().let { Pattern.compile(URL_REGEX).matcher(it).matches() }
            && !shouldUseInternalBrowserForUrl(url)
            && intent.component?.shortClassName?.contains("QQBrowserActivity") == true
        ) {
            val customTabsIntent = CustomTabsIntent.Builder().apply {
                setShowTitle(true)
            }.build()
            customTabsIntent.intent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            customTabsIntent.launchUrl(
                appContext!!,
                (if (!url.startsWith("http://") && !url.startsWith("https://")) "http://$url" else url).toUri()
            )
            method.result = null
            return true
        }
        return false
    }
}
