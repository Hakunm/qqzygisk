package com.qm.qqzygisk.hook.parasitic.activity.config

import android.app.Activity
import android.content.Intent

/**
 * 当前代理的 [Activity] 参数配置类
 */
internal object ActivityProxyConfig {

    /**
     * 用于代理的 [Intent] 名称
     */
    internal var proxyIntentName = ""

    /**
     * In-process token extra. Safer than embedding the real Intent on newer Android.
     */
    internal var proxyTokenName = ""

    /**
     * 需要代理的 [Activity] 类名
     */
    internal var proxyClassName = ""
}