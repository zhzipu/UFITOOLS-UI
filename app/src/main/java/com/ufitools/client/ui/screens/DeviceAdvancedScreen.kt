package com.ufitools.client.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.ErrorBanner
import com.ufitools.client.ui.components.KeyValueRow
import com.ufitools.client.ui.components.LabeledField
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.SubPageScaffold
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 设备高级设置（API 文档 §3 / §12）。
 *
 * 收拢三块彼此独立、但不值得各占一个页面的设置：
 * - **资源服务器**：设备拉取插件/资源时用的上游地址；
 * - **后台管理密码**：设备 Web 控制台的管理密码（与"控制台口令"不是同一把）；
 * - **自定义头部**：设备 Web 端页面顶部的展示文本。
 *
 * 弱口令检测放在密码卡片里：改密码前后都值得看一眼。
 */
@Composable
fun DeviceAdvancedScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loading = "resServer" in vm.pageLoading || "customHead" in vm.pageLoading || "weakToken" in vm.pageLoading
    val error = vm.pageError["resServer"] ?: vm.pageError["customHead"] ?: vm.pageError["weakToken"]

    var resServer by remember { mutableStateOf("") }
    var customHead by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var confirmPwd by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        vm.refreshResServer()
        vm.refreshCustomHead()
        vm.refreshWeakToken()
    }
    LaunchedEffect(vm.resServer) { resServer = vm.resServer }
    LaunchedEffect(vm.customHead) { customHead = vm.customHead }

    fun toast(msg: String) {
        Toast.makeText(context, if (msg == "success") "已保存" else msg, Toast.LENGTH_SHORT).show()
    }

    SubPageScaffold(
        title = "设备高级设置",
        onBack = { nav.popBackStack() },
        loading = loading,
        onRefresh = {
            scope.launch {
                vm.refreshResServer()
                vm.refreshCustomHead()
                vm.refreshWeakToken()
            }
        }
    ) {
        ErrorBanner(error)

        // ---------------------------------------------------------- 弱口令检测
        AppCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("安全检测")
                TextButton(onClick = { scope.launch { vm.refreshWeakToken() } }, enabled = !loading) {
                    Text("重新检测", color = AppTheme.accent)
                }
            }
            Spacer(Modifier.height(6.dp))
            val weak = vm.weakToken
            val text = when (weak) {
                true -> "检测到弱口令：当前控制台口令强度不足，建议尽快更换"
                false -> "未检测到弱口令"
                null -> "无法确定（设备未返回检测结果）"
            }
            val color = when (weak) {
                true -> Color(0xFFEF4444)
                false -> Color(0xFF22C55E)
                null -> AppTheme.textSecondary
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }

        // ---------------------------------------------------------- 资源服务器
        AppCard {
            SectionTitle("资源服务器")
            Spacer(Modifier.height(4.dp))
            Text(
                "设备下载插件与资源时使用的上游地址，留空表示使用内置默认值。",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.textSecondary
            )
            Spacer(Modifier.height(10.dp))
            LabeledField("地址", resServer, { resServer = it }, placeholder = "https://...")
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        scope.launch {
                            toast(vm.saveResServer(resServer.trim()))
                        }
                    },
                    enabled = !loading
                ) { Text("保存", color = AppTheme.accent) }
            }
        }

        // ---------------------------------------------------------- 自定义头部
        AppCard {
            SectionTitle("自定义头部")
            Spacer(Modifier.height(4.dp))
            Text(
                "显示在设备 Web 控制台顶部的自定义文本。",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.textSecondary
            )
            Spacer(Modifier.height(10.dp))
            LabeledField("文本", customHead, { customHead = it }, singleLine = false)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { scope.launch { toast(vm.saveCustomHead(customHead)) } },
                    enabled = !loading
                ) { Text("保存", color = AppTheme.accent) }
            }
        }

        // ---------------------------------------------------------- 后台管理密码
        AppCard {
            SectionTitle("后台管理密码")
            Spacer(Modifier.height(4.dp))
            Text(
                "这是设备 Web 控制台的管理密码，与「连接」页里的「控制台口令」不是同一把。\n" +
                    "修改后请同步更新「连接」页的后台密码，否则设备设置类操作会失败。",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.textSecondary
            )
            Spacer(Modifier.height(10.dp))
            LabeledField("新密码", newPwd, { newPwd = it })
            Spacer(Modifier.height(8.dp))
            LabeledField("确认新密码", confirmPwd, { confirmPwd = it })
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                val ok = newPwd.length >= 4 && newPwd == confirmPwd
                TextButton(
                    onClick = {
                        scope.launch {
                            val r = vm.updateAdminPwd(newPwd)
                            toast(r)
                            if (r == "success") {
                                newPwd = ""
                                confirmPwd = ""
                            }
                        }
                    },
                    enabled = !loading && ok
                ) { Text("修改密码", color = AppTheme.accent) }
            }
            if (newPwd.isNotEmpty() && newPwd != confirmPwd) {
                Text(
                    "两次输入不一致",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFEF4444)
                )
            }
        }

        AppCard {
            SectionTitle("当前设备标识")
            Spacer(Modifier.height(4.dp))
            KeyValueRow("设备地址", vm.config.host)
            KeyValueRow("控制端口", vm.config.port.toString())
        }
    }
}
