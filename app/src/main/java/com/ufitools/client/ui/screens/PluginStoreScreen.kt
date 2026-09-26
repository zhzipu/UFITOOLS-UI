package com.ufitools.client.ui.screens

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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufitools.client.model.StorePlugin
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.ErrorBanner
import com.ufitools.client.ui.components.LabeledField
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.SubPageScaffold
import com.ufitools.client.ui.components.ThinDivider
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 插件商店（API 文档 §14）。
 *
 * 插件是跑在设备上的 Lua 小程序。本页只做「看列表 + 装/卸 + 看通知」，
 * 上传/打包插件这类操作留给设备自带的 Web 端。
 */
@Composable
fun PluginStoreScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loading = "plugins" in vm.pageLoading
    val error = vm.pageError["plugins"]

    var search by remember { mutableStateOf("") }
    var pendingUninstall by remember { mutableStateOf<StorePlugin?>(null) }
    var changelog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refreshPlugins() }

    fun toast(msg: String) {
        Toast.makeText(context, if (msg == "success") "完成" else msg, Toast.LENGTH_SHORT).show()
    }

    // 搜索只在本地过滤，避免每敲一个字就打一次设备
    val filtered = remember(vm.storePlugins, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) vm.storePlugins
        else vm.storePlugins.filter { p ->
            p.publicName.lowercase().contains(q) ||
                p.displayName.lowercase().contains(q) ||
                p.description.lowercase().contains(q)
        }
    }

    SubPageScaffold(
        title = "插件商店",
        onBack = { nav.popBackStack() },
        loading = loading,
        onRefresh = { scope.launch { vm.refreshPlugins() } }
    ) {
        ErrorBanner(error)

        if (vm.pluginNotifications.isNotEmpty()) {
            AppCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("通知（${vm.pluginNotifications.size}）")
                    TextButton(onClick = { scope.launch { vm.readPluginNotifications() } }) {
                        Text("全部已读", color = AppTheme.accent)
                    }
                }
                Spacer(Modifier.height(6.dp))
                vm.pluginNotifications.take(10).forEach { n ->
                    Text(
                        "· $n",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.textSecondary,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }

        AppCard {
            LabeledField("搜索", search, { search = it }, placeholder = "按名称或说明筛选")
        }

        AppCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("商店（${filtered.size}）")
                TextButton(onClick = {
                    scope.launch {
                        val o = vm.pluginUpdateCheck()
                        changelog = o.toString()
                    }
                }, enabled = !loading) { Text("检查更新", color = AppTheme.accent) }
            }
            Spacer(Modifier.height(6.dp))
            if (filtered.isEmpty()) {
                EmptyHint(if (vm.storePlugins.isEmpty()) "未读到插件列表" else "没有匹配的插件")
            } else {
                filtered.forEachIndexed { i, p ->
                    PluginRow(
                        p = p,
                        loading = loading,
                        onInstall = {
                            scope.launch { toast(vm.installPlugin(p.publicName)) }
                        },
                        onUninstall = { pendingUninstall = p }
                    )
                    if (i != filtered.lastIndex) ThinDivider(Modifier.padding(vertical = 4.dp))
                }
            }
        }

        AppCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("变更日志")
                TextButton(onClick = {
                    scope.launch { changelog = vm.pluginChangelog().toString() }
                }, enabled = !loading) { Text("查看", color = AppTheme.accent) }
            }
            changelog?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it.take(2000),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.textSecondary
                )
            }
        }
    }

    pendingUninstall?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("卸载插件") },
            text = { Text("确定卸载「${p.displayName}」？相关数据可能一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    val name = p.installName.ifBlank { p.publicName }
                    pendingUninstall = null
                    scope.launch { toast(vm.uninstallPlugin(name)) }
                }) { Text("卸载", color = AppTheme.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text("取消", color = AppTheme.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun PluginRow(
    p: StorePlugin,
    loading: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTheme.textPrimary,
                        maxLines = 1
                    )
                    if (p.version.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "v${p.version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTheme.textSecondary
                        )
                    }
                }
                if (p.author.isNotBlank()) {
                    Text(
                        "作者：${p.author}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textSecondary
                    )
                }
            }
            if (p.installed) {
                TextButton(onClick = onUninstall, enabled = !loading) {
                    Text("卸载", color = AppTheme.textSecondary)
                }
            } else {
                TextButton(onClick = onInstall, enabled = !loading) {
                    Text("安装", color = AppTheme.accent)
                }
            }
        }
        if (p.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                p.description,
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.textSecondary,
                maxLines = 3
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            p.publicName,
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.textSecondary.copy(alpha = 0.6f)
        )
    }
}
