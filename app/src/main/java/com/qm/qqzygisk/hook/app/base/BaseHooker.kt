package com.qm.qqzygisk.hook.app.base

import com.qm.qqzygisk.hook.utils.Log

abstract class BaseHooker: BaseSetting() {
    private var init = false

    abstract fun initOnce()

    fun load() {
        if (init) return
        try {
            initOnce()
        } catch (th: Throwable) {
            Log.error("initOnce $name Failed", th)
        }
        init = true
    }
}