package com.ufitools.client.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.ErrorBanner
import com.ufitools.client.ui.components.KeyValueRow
import com.ufitools.client.ui.components.LabeledField
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.SubPageScaffold
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 网页终端 / ADB 调试入口（API 文档 §15）。
 *
 * 两部分：
 * - **ttyd**：设备上跑一个网页终端，启动后点"打开"用系统浏览器访问；
 *   注意 ttyd 默认没有加密，只适合在设备局域网内用。
 * - **ADB**：查看当前 ADB 模式/端口，可在 debug / normal 之间切换。
 */
@Composable
fun TerminalServiceScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loading = "ttyd" in vm.pageLoading || "adb" in vm.pageLoading
    val error = vm.pageError["ttyd"] ?: vm.pageError["adb"]

    var port by remember { mutableStateOf("1146") }
    var showModeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshTtyd()
        vm.refreshAdbInfo()
    }

    fun toast(msg: String) {
        Toast.makeText(context, if (msg == "success") "完成" else msg, Toast.LENGTH_SHORT).show()
    }

    SubPageScaffold(
        title = "终端与调试",
        onBack = { nav.popBackStack() },
        loading = loading,
        onRefresh = {
            scope.launch {
                vm.refreshTtyd()
                vm.refreshAdbInfo()
            }
        }
    ) {
        ErrorBanner(error)

        // ---------------------------------------------------------- ttyd
        AppCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("网页终端 (ttyd)")
                Text(
                    if (vm.ttydRunning) "运行中" else "已停止",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (vm.ttydRunning) AppTheme.accent else AppTheme.textSecondary
                )
            }
            Spacer(Modifier.height(8.dp))
            KeyValueRow("监听地址", vm.ttydAddress)
            Spacer(Modifier.height(4.dp))
            LabeledField(
                "端口",
                port,
                { port = it.filter { c -> c.isDigit() }.take(5) },
                keyboardType = KeyboardType.Number
            )
            Spacer(Modifier.height(10.dp))
            Row {
                if (vm.ttydRunning) {
                    OptionChip("停止服务", false, enabled = !loading) {
                        scope.launch { toast(vm.stopTtyd()) }
                    }
                    OptionChip("打开终端", false, enabled = !loading) {
                        openUrl(context, vm.ttydUrl(port.toIntOrNull() ?: 1146))
                    }
                } else {
                    OptionChip("启动服务", false, enabled = !loading) {
                        scope.launch { toast(vm.startTtyd(port.toIntOrNull() ?: 1146)) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "提示：ttyd 无加密，仅在设备局域网内使用；用完建议及时停止。",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.textSecondary
            )
        }

        // ---------------------------------------------------------- ADB
        AppCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("ADB 调试")
                TextButton(onClick = { showModeDialog = true }, enabled = !loading) {
                    Text("切换模式", color = AppTheme.accent)
                }
            }
            Spacer(Modifier.height(8.dp))
            val adb = vm.adbInfo
            KeyValueRow(
                "状态",
                adb?.get("alive")?.let { if (it.isJsonNull) "" else it.asString }
                    ?.let { if (it == "true" || it == "1") "已开启" else "未开启" } ?: "-"
            )
            KeyValueRow("模式", adb?.get("mode")?.let { if (it.isJsonNull) "" else it.asString } ?: "-")
            KeyValueRow("端口", adb?.get("port")?.let { if (it.isJsonNull) "" else it.asString } ?: "-")
        }

        AppCard {
            SectionTitle("说明")
            Spacer(Modifier.height(6.dp))
            Text(
                "「网页终端」在设备上起一个浏览器可访问的 shell；\n" +
                    "「ADB 调试」用于切换设备的调试模式，切换后可能需要重新插拔 USB。",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.textSecondary
            )
        }
    }

    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("切换 ADB 模式") },
            text = {
                Column {
                    Text(
                        "选择要切换到的模式：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.textPrimary
                    )
                    Spacer(Modifier.height(10.dp))
                    ADB_MODES.forEach { (value, label, desc) ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OptionChip(label, false) {
                                    showModeDialog = false
                                    scope.launch { toast(vm.setAdbMode(value)) }
                                }
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                desc,
                                style = MaterialTheme.typography.labelSmall,
                                color = AppTheme.textSecondary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModeDialog = false }) {
                    Text("取消", color = AppTheme.textSecondary)
                }
            }
        )
    }
}

private val ADB_MODES = listOf(
    Triple("debug", "调试模式", "开放 ADB 调试，供开发/排障使用"),
    Triple("normal", "普通模式", "关闭 ADB 调试，日常使用更安全"),
)

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(context, "没有可打开该链接的应用", Toast.LENGTH_SHORT).show()
    }
}
