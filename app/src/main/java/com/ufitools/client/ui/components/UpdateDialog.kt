package com.ufitools.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufitools.client.model.UpdateInfo
import com.ufitools.client.ui.theme.AppTheme

/**
 * 发现新版本时的提示弹窗。
 *
 * 卡片色 + 圆角矩形，与整体视觉一致。
 *
 * @param currentVersion 本机已安装版本，用于展示「A → B」
 * @param onDownload 点「前往下载」：优先给匹配本机 ABI 的 APK 直链，没有就开 Release 页
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = AppTheme.cardBg,
        title = { Text("发现新版本 ${info.version}", color = AppTheme.textPrimary) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "当前版本 $currentVersion → 最新 ${info.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.textSecondary
                )
                if (info.apkName != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "将下载 ${info.apkName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textMuted
                    )
                }
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        info.notes.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.textSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("稍后", color = AppTheme.textSecondary)
                }
                TextButton(onClick = onDownload) {
                    Text("前往下载", color = AppTheme.accent)
                }
            }
        }
    )
}
