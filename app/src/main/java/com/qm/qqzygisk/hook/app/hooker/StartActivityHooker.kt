package com.qm.qqzygisk.hook.app.hooker

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.base.IStartActivityHookDecorator
import com.qm.qqzygisk.hook.extension.hookAll
import com.qm.qqzygisk.hook.utils.Log

object StartActivityHooker : BaseHooker() {
    override val key: String = "start_activity_dispatch"
    override val name: String = "StartActivityHooker"
    override val isShow: Boolean = false

    val decorators = arrayOf<IStartActivityHookDecorator>(
        SystemCameraHooker.startActivityDecorator,
        FxxkQQBrowserHooker,
    )

    override fun initOnce() {
        arrayOf(
            ContextWrapper::class,
            Activity::class
        ).forEach { cls ->
            cls
                .resolve()
                .method {
                    name {
                        it == "startActivity" || it == "startActivityForResult"
                    }
                }.hookAll {
                    before {
                        val intent: Intent = if (this.args[0] is Intent) {
                            this.args[0] as Intent
                        } else {
                            this.args[1] as Intent
                        }
                        decorators.forEach {
                            try {
                                if (it.onStartActivityIntent(intent, this)) return@before
                            } catch (e: Throwable) {
                                Log.error("${it.javaClass.simpleName} onStartActivityIntent error", e)
                            }
                        }
                    }
                }
        }
    }
}
