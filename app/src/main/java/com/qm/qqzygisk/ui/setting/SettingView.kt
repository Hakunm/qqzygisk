package com.qm.qqzygisk.ui.setting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.qm.qqzygisk.hook.app.QQEntry.settings
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.ui.component.setting.SettingSwitch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingView() {
    val context = LocalContext.current
    remember(context) { HookSettings.initialize(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text("设置")
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Column {
                settings.forEach { setting ->
                    var isEnabled by remember(setting.key) {
                        mutableStateOf(
                            HookSettings.isEnabled(setting.key, setting.defaultEnabled)
                        )
                    }
                    SettingSwitch(
                        title = setting.name,
                        description = setting.description,
                        checked = isEnabled,
                        onCheckedChange = { enabled ->
                            HookSettings.setEnabled(setting.key, enabled)
                            isEnabled = enabled
                        }
                    )
                }
            }
        }
    }
}
