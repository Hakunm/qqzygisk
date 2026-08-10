package com.qm.qqzygisk.hook.app.base

import android.content.Intent
import com.qm.qqzygisk.hook.extension.MethodCall

abstract class IStartActivityHookDecorator: BaseSetting() {
    abstract fun onStartActivityIntent(intent: Intent, method: MethodCall): Boolean
}