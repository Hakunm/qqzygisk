package com.qm.qqzygisk.ui.activity

import androidx.activity.compose.setContent
import com.qm.qqzygisk.ui.setting.SettingView
import com.qm.qqzygisk.ui.theme.AppTheme

class SettingActivity : BaseActivity() {
    override fun onCreate() {
        setContent {
            AppTheme(dynamicColor = false) {
                SettingView()
            }
        }
    }
}