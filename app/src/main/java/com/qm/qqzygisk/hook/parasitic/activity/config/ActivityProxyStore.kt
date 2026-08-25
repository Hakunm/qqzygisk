package com.qm.qqzygisk.hook.parasitic.activity.config

import android.content.Intent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the real module Intent in-process so we do not depend on nested Intent extras.
 * Nested extras are stripped or blocked on newer Android, which would launch the host
 * proxy activity (camera preview) and crash QQ.
 */
internal object ActivityProxyStore {
    private val pending = ConcurrentHashMap<String, Intent>()

    fun put(intent: Intent): String {
        if (pending.size > 16) pending.clear()
        val token = UUID.randomUUID().toString()
        pending[token] = intent
        return token
    }

    fun peekFrom(intent: Intent?): Intent? {
        if (intent == null) return null
        intent.getStringExtra(ActivityProxyConfig.proxyTokenName)
            ?.takeIf { it.isNotBlank() }
            ?.let { pending[it] }
            ?.let { return it }
        if (intent.hasExtra(ActivityProxyConfig.proxyIntentName)) {
            @Suppress("DEPRECATION")
            return intent.getParcelableExtra(ActivityProxyConfig.proxyIntentName)
        }
        return null
    }

    fun consume(intent: Intent?): Intent? {
        if (intent == null) return null
        val token = intent.getStringExtra(ActivityProxyConfig.proxyTokenName)
        if (!token.isNullOrBlank()) {
            pending.remove(token)?.let { return it }
        }
        return peekFrom(intent)
    }

    fun peekAny(): Intent? = pending.values.firstOrNull()
}

internal fun Intent.removeLaunchProtection() {
    runCatching {
        javaClass.methods
            .firstOrNull { it.name == "removeLaunchSecurityProtection" && it.parameterCount == 0 }
            ?.invoke(this)
    }
}
