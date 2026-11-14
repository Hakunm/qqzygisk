package com.qm.qqzygisk.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qm.qqzygisk.hook.utils.ModuleDownload
import com.qm.qqzygisk.hook.utils.ModuleDownloadProgress
import com.qm.qqzygisk.hook.utils.ModuleDownloadStatus
import com.qm.qqzygisk.hook.utils.ModuleRelease

/**
 * 模块 ZIP 下载的底部区域：上方是进度条与右侧百分比，下方是操作按钮。
 *
 * 只有下载任务活动、正在创建或正在读取状态时才显示进度条；总量未知时百分比显示“—”，
 * 不伪造 0%。下载完成隐藏进度条，只有提供打开操作的更新弹窗才显示“打开 ZIP”；失败隐藏进度条，
 * 只显示真实错误与重新下载。状态未知（查询异常）提供“刷新进度”，不当作任务丢失。
 */
@Composable
internal fun ModuleDownloadControls(
    release: ModuleRelease?,
    download: ModuleDownload?,
    progress: ModuleDownloadProgress?,
    starting: Boolean,
    readFailed: Boolean,
    error: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetryRead: () -> Unit,
    onOpenZip: ((ModuleDownload) -> Unit)?,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val status = progress?.status
    val active = progress?.active == true
    val complete = progress?.takeIf { it.status == ModuleDownloadStatus.Complete }?.download
    val failed = status == ModuleDownloadStatus.Failed || status == ModuleDownloadStatus.Missing
    val showBar = starting || (download != null && !readFailed && (progress == null || active))
    val percent = if (showBar && !starting) progress?.percentOrNull() else null
    val statusMessage = progress?.takeIf { it.status == ModuleDownloadStatus.Failed || it.status == ModuleDownloadStatus.Pending }?.message
        ?.takeIf { it.isNotBlank() }
        ?.withoutRedownloadHint()
    val message = (error ?: statusMessage)?.withoutRedownloadHint()

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (showBar) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = percent?.let { "$it%" } ?: "—",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 40.dp),
                )
            }
        }
        if (message != null) {
            if (showBar) Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null || failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DownloadActions(onClose) {
            when {
                active -> TextButton(onClick = onCancel) {
                    Text(text = "取消下载")
                }

                complete != null && onOpenZip != null && release?.versionCode == complete.versionCode -> {
                    Button(onClick = { onOpenZip(complete) }) {
                        Text(text = "打开 ZIP")
                    }
                }

                failed && release != null -> Button(onClick = onDownload) {
                    Text(text = "重新下载")
                }

                readFailed -> TextButton(onClick = onRetryRead) {
                    Text(text = "刷新进度")
                }

                !starting && release != null && (download == null || complete != null) -> Button(onClick = onDownload) {
                    Text(text = "下载更新")
                }
            }
        }
    }
}

@Composable
private fun DownloadActions(onClose: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onClose != null) {
            TextButton(onClick = onClose) { Text(text = "关闭") }
        }
        content()
    }
}

private fun ModuleDownloadProgress.percentOrNull(): Int? {
    if (totalBytes <= 0) return null
    val received = receivedBytes.coerceIn(0, totalBytes)
    return ((received * 100) / totalBytes).toInt().coerceIn(0, 100)
}

/** 后端错误可能附带“请重新下载”一类操作提示，界面只保留真实错误本身。 */
private val redownloadHint = Regex("[，,。]\\s*请重新下载[。.！!]?\\s*$")

private fun String.withoutRedownloadHint(): String = replace(redownloadHint, "").trim()
