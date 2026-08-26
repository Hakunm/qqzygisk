package com.qm.qqzygisk.ui.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.qm.qqzygisk.hook.app.QQEntry.settings
import com.qm.qqzygisk.hook.app.chat.ImageFolderStore
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.ui.component.setting.SettingSwitch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingView() {
    val context = LocalContext.current
    remember(context) { HookSettings.initialize(context) }
    var showPathSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showPathSettings) { showPathSettings = false }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text(if (showPathSettings) "表情目录" else "设置")
                },
                navigationIcon = {
                    if (showPathSettings) {
                        IconButton(onClick = { showPathSettings = false }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
            )
        }
    ) { innerPadding ->
        if (showPathSettings) {
            PathSettingsPage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            MainSettings(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onOpenPathSettings = { showPathSettings = true },
            )
        }
    }
}

@Composable
private fun MainSettings(
    modifier: Modifier = Modifier,
    onOpenPathSettings: () -> Unit,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
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
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPathSettings)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "表情目录",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "FunBox / TG 导入到本地可写收藏夹",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PathSettingsPage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        PathSettings()
    }
}

@Composable
private fun ColumnScope.PathSettings() {
    var qqZygiskPath by remember {
        mutableStateOf(
            HookSettings.getString(
                ImageFolderStore.QQ_ZYGISK_PATH_KEY,
                ImageFolderStore.DEFAULT_QQ_ZYGISK_PATH,
            ),
        )
    }
    var funBoxPath by remember {
        mutableStateOf(
            HookSettings.getString(
                ImageFolderStore.FUNBOX_PATH_KEY,
                ImageFolderStore.DEFAULT_FUNBOX_PATH,
            ),
        )
    }
    var tgStickersPath by remember {
        mutableStateOf(
            HookSettings.getString(
                ImageFolderStore.TG_STICKERS_PATH_KEY,
                ImageFolderStore.DEFAULT_TG_STICKERS_PATH,
            ),
        )
    }
    var savedQqZygiskPath by remember { mutableStateOf(qqZygiskPath) }
    var savedFunBoxPath by remember { mutableStateOf(funBoxPath) }
    var savedTgStickersPath by remember { mutableStateOf(tgStickersPath) }
    var importHint by remember { mutableStateOf("") }
    val hasChanges = qqZygiskPath != savedQqZygiskPath ||
        funBoxPath != savedFunBoxPath ||
        tgStickersPath != savedTgStickersPath

    fun persistPaths() {
        qqZygiskPath = qqZygiskPath.trim()
            .ifEmpty { ImageFolderStore.DEFAULT_QQ_ZYGISK_PATH }
        funBoxPath = funBoxPath.trim().ifEmpty { ImageFolderStore.DEFAULT_FUNBOX_PATH }
        tgStickersPath = tgStickersPath.trim()
            .ifEmpty { ImageFolderStore.DEFAULT_TG_STICKERS_PATH }
        HookSettings.setString(ImageFolderStore.QQ_ZYGISK_PATH_KEY, qqZygiskPath)
        HookSettings.setString(ImageFolderStore.FUNBOX_PATH_KEY, funBoxPath)
        HookSettings.setString(ImageFolderStore.TG_STICKERS_PATH_KEY, tgStickersPath)
        savedQqZygiskPath = qqZygiskPath
        savedFunBoxPath = funBoxPath
        savedTgStickersPath = tgStickersPath
    }

    Spacer(modifier = Modifier.height(20.dp))
    OutlinedTextField(
        value = qqZygiskPath,
        onValueChange = { qqZygiskPath = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("本地图片目录") },
        minLines = 2,
        maxLines = 3,
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = funBoxPath,
        onValueChange = { funBoxPath = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("FunBox 表情目录") },
        minLines = 2,
        maxLines = 3,
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = tgStickersPath,
        onValueChange = { tgStickersPath = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("TGStickersExported 表情目录") },
        minLines = 2,
        maxLines = 3,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "FunBox 和 TG 会导入成本地可写收藏夹，不再分成只读和可写两套目录。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Button(
        onClick = { persistPaths() },
        modifier = Modifier.align(Alignment.End),
        enabled = hasChanges,
    ) {
        Text("保存路径")
    }
    Spacer(modifier = Modifier.height(12.dp))
    Button(
        onClick = {
            persistPaths()
            val copied = ImageFolderStore.importExternalIfNeeded(force = true)
            importHint = if (copied > 0) "已导入 $copied 张表情" else "没有新的表情需要导入"
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("导入到可写收藏夹")
    }
    if (importHint.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = importHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(modifier = Modifier.height(24.dp))
}
