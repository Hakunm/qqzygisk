package com.qm.qqzygisk.ui.activity

import androidx.activity.compose.setContent
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.ui.setting.SettingView
import com.qm.qqzygisk.ui.theme.AppTheme

class SettingActivity : BaseActivity() {
    override fun onCreate() {
        runCatching {
            setContent {
                AppTheme(dynamicColor = false) {
                    SettingView()
                }
            }
        }.onFailure {
            Log.error("settings content", it)
        }
    }
}