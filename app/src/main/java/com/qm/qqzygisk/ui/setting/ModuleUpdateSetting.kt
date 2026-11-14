package com.qm.qqzygisk.ui.setting

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.qm.qqzygisk.BuildConfig
import com.qm.qqzygisk.hook.utils.ModuleDownload
import com.qm.qqzygisk.hook.utils.ModuleDownloadProgress
import com.qm.qqzygisk.hook.utils.ModuleDownloads
import com.qm.qqzygisk.hook.utils.ModuleRelease
import com.qm.qqzygisk.hook.utils.ModuleUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模块更新入口：默认不联网，只有点击“检查更新”后才请求更新源。
 *
 * 检查期间按钮显示转圈并禁止重复点击；只有更新源版本高于当前安装版本时才弹出
 * 更新说明，已是最新、当前版本更高或检查失败都只用 Toast 提示。更新说明在检查
 * 期间预先取得，获取失败不影响弹窗与新版本下载。
 *
 * 弹窗正文只渲染更新说明（Markdown），底部是下载进度与操作区：进度条固定在底部，
 * 右侧显示真实百分比（总量未知时显示“—”），下载完成后只提供“打开 ZIP”，
 * 不会自动安装。失败的下载只显示错误与重新下载。
 *
 * ZIP 由宿主进程流式下载：关闭弹窗隐藏全部下载状态、错误和操作，不取消下载；只有“取消下载”才删除任务。
 * 宿主被强停后，重新打开设置会恢复未完成任务。下载完成后保存到公共“下载”目录，
 * “打开 ZIP”仅在当前更新弹窗内提供，且仍需完整文件已成功发布。
 */
@Composable
internal fun ModuleUpdateSetting() {
    val context = LocalContext.current
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateRelease by remember { mutableStateOf<ModuleRelease?>(null) }
    var notes by remember { mutableStateOf<NotesState?>(null) }
    var dialogVisible by remember { mutableStateOf(false) }

    // 下载任务由后端持久化；这里只保留当前展示的快照，从不自行写任务 ID。
    var download by remember { mutableStateOf<ModuleDownload?>(null) }
    var progress by remember { mutableStateOf<ModuleDownloadProgress?>(null) }
    var downloadBusy by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    // 查询失败只代表“状态未知”，既不是任务丢失，也不能作为重新下载的依据。
    var progressReadFailed by remember { mutableStateOf(false) }
    var pendingRelease by remember { mutableStateOf<ModuleRelease?>(null) }
    var progressRefresh by remember { mutableStateOf(0) }
    // 用户已经开始或取消过任务后，迟到的恢复结果不能再覆盖当前状态。
    var downloadTouched by remember { mutableStateOf(false) }
    var pageStarted by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true
        )
    }

    DisposableEffect(lifecycleOwner) {
        val owner = lifecycleOwner
        if (owner == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, _ ->
                pageStarted = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    // 恢复后端记录的最近任务；读取失败必须可见，不能静默当成“没有任务”。
    LaunchedEffect(context) {
        try {
            val restored = withContext(Dispatchers.IO) { ModuleDownloads.load(context) }
            if (restored != null && download == null && !downloadTouched) download = restored
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            downloadError =
                "无法读取已保存的下载任务：${error.message ?: error.javaClass.simpleName}"
        }
    }

    // 页面只订阅真实字节快照；离开页面停止订阅，不影响进程级下载任务。
    LaunchedEffect(download?.id, pageStarted, progressRefresh) {
        val target = download ?: return@LaunchedEffect
        if (!pageStarted) return@LaunchedEffect
        try {
            ModuleDownloads.observe(context, target).collect { snapshot ->
                if (download?.id == target.id) {
                    progress = snapshot
                    if (progressReadFailed) {
                        progressReadFailed = false
                        downloadError = null
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (download?.id == target.id) {
                progress = null
                progressReadFailed = true
                downloadError =
                    "无法读取下载进度：${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    fun beginDownload(release: ModuleRelease) {
        if (downloadBusy) return
        downloadTouched = true
        downloadBusy = true
        downloadError = null
        progressReadFailed = false
        scope.launch {
            try {
                val started = withContext(Dispatchers.IO) {
                    ModuleDownloads.start(context, release)
                }
                // 后端返回的就是最新任务；旧任务的迟到查询结果由 id 校验丢弃。
                download = started
                progress = null
                // 即使后端复用了已完成任务，也要重新读取一次真实状态。
                progressRefresh++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                downloadError = error.message ?: "无法开始下载（${error.javaClass.simpleName}）"
            } finally {
                downloadBusy = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingRelease
        pendingRelease = null
        if (pending != null) {
            if (granted) {
                beginDownload(pending)
            } else {
                downloadError = "未获得存储权限，无法开始下载"
            }
        }
    }

    fun requestDownload(release: ModuleRelease) {
        if (downloadBusy || pendingRelease != null) return
        if (download != null && progress?.active == true) return
        downloadError = null
        val requiresPermission = try {
            ModuleDownloads.needsStoragePermission(context)
        } catch (error: Exception) {
            downloadError = "无法检查存储权限：${error.message ?: error.javaClass.simpleName}"
            return
        }
        if (!requiresPermission) {
            beginDownload(release)
            return
        }
        pendingRelease = release
        try {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } catch (error: Exception) {
            pendingRelease = null
            downloadError = "无法申请存储权限：${error.message ?: error.javaClass.simpleName}"
        }
    }

    fun cancelDownload() {
        val target = download ?: return
        downloadTouched = true
        downloadError = null
        progressReadFailed = false
        scope.launch {
            try {
                val remaining = withContext(Dispatchers.IO) {
                    ModuleDownloads.cancel(context, target)
                    ModuleDownloads.load(context)
                }
                if (download?.id == target.id) {
                    download = remaining
                    progress = null
                    progressRefresh++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                downloadError = "无法取消下载：${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    /** 状态未知时只重新读取，不把未知当成失败去重新下载。 */
    fun retryReadProgress() {
        if (download == null || downloadBusy) return
        progressReadFailed = false
        downloadError = null
        progressRefresh++
    }

    fun openDownloadedZip(target: ModuleDownload) {
        downloadError = null
        scope.launch {
            val intent = try {
                withContext(Dispatchers.IO) { ModuleDownloads.openIntent(context, target) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                downloadError = "无法打开下载的 ZIP：${error.message ?: error.javaClass.simpleName}"
                // 完成任务的订阅已结束；文件被外部删除后重新读取 Missing，恢复重新下载入口。
                progressRefresh++
                return@launch
            }
            // 宿主通常以 ContextWrapper 形式提供 Context，必须带 NEW_TASK 才能启动。
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (notFound: ActivityNotFoundException) {
                downloadError = "没有应用可以打开该 ZIP"
            } catch (error: Exception) {
                downloadError = "无法打开 ZIP：${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    fun startCheck() {
        if (checking) return
        dialogVisible = false
        checking = true
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { ModuleUpdates.check() }
                val currentCode = BuildConfig.VERSION_CODE.toLong()
                if (release.versionCode <= currentCode) {
                    Toast.makeText(
                        context,
                        if (release.versionCode < currentCode) "当前版本高于更新源" else "已是最新版本",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                // 说明在转圈期间取完：失败也照常弹窗，不阻塞下载。
                val noteState = if (release.changelogUrl == null) {
                    NotesState.Absent
                } else {
                    try {
                        val text = withContext(Dispatchers.IO) {
                            ModuleUpdates.releaseNotes(release)
                        }
                        if (text.isBlank()) NotesState.Absent else NotesState.Ready(text)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        NotesState.Failed(error.message ?: error.javaClass.simpleName)
                    }
                }
                updateRelease = release
                notes = noteState
                dialogVisible = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    error.message ?: "检查更新失败",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                checking = false
            }
        }
    }

    val activeDownload = download
    val visibleProgress = progress?.takeIf {
        activeDownload != null && it.download.id == activeDownload.id
    }
    val starting = downloadBusy || pendingRelease != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "模块更新",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "当前版本 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = { startCheck() }, enabled = !checking) {
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "检查中")
            } else {
                Text(text = "检查更新")
            }
        }
    }

    if (dialogVisible) {
        val release = updateRelease
        if (release != null) {
            val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
            Dialog(
                onDismissRequest = { dialogVisible = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth(0.94f)
                        .heightIn(max = maxDialogHeight),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = "模块更新 v${release.version}",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        val noteState = notes
                        if (noteState is NotesState.Ready || noteState is NotesState.Failed) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                when (noteState) {
                                    is NotesState.Ready -> ModuleUpdateMarkdown(
                                        text = noteState.text,
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    is NotesState.Failed -> Text(
                                        text = "更新说明加载失败：${noteState.message}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )

                                    NotesState.Absent -> Unit
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        ModuleDownloadControls(
                            release = release,
                            download = activeDownload,
                            progress = visibleProgress,
                            starting = starting,
                            readFailed = progressReadFailed,
                            error = downloadError,
                            onDownload = { requestDownload(release) },
                            onCancel = { cancelDownload() },
                            onRetryRead = { retryReadProgress() },
                            onOpenZip = { openDownloadedZip(it) },
                            onClose = { dialogVisible = false },
                        )
                    }
                }
            }
        }
    }
}

/** 从宿主提供的 ContextWrapper 链中找到页面 Activity，用于判断 STARTED 状态。 */
private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is LifecycleOwner) return current
        current = current.baseContext
    }
    return current as? LifecycleOwner
}

private sealed interface NotesState {
    data class Ready(val text: String) : NotesState
    data class Failed(val message: String) : NotesState
    data object Absent : NotesState
}
