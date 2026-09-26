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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.ErrorBanner
import com.ufitools.client.ui.components.LabeledField
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.SubPageScaffold
import com.ufitools.client.ui.components.ThinDivider
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 定时任务（API 文档 §9）。
 *
 * 设备侧 `action` 是一段自由 JSON，本页只暴露最常用的**计划重启**，
 * 其余形态的任务仍会在列表里原样显示（动作摘要 + 原始 JSON 折叠展示），
 * 删除任意一条都走同一个接口。
 *
 * 另外提供 goform 的 `RESTART_SCHEDULE_SETTING`——那是设备自带的
 * "每天定点重启"开关，比自定义任务更简单直接。
 */
@Composable
fun ScheduledTaskScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loading = "tasks" in vm.pageLoading
    val error = vm.pageError["tasks"]

    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var showSchedule by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshTasks() }

    fun toast(msg: String) {
        Toast.makeText(context, if (msg == "success") "已保存" else msg, Toast.LENGTH_SHORT).show()
    }

    SubPageScaffold(
        title = "定时任务",
        onBack = { nav.popBackStack() },
        loading = loading,
        onRefresh = { scope.launch { vm.refreshTasks() } }
    ) {
        ErrorBanner(error)

        AppCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("任务列表")
                TextButton(onClick = { showAdd = true }, enabled = !loading) {
                    Text("+ 新建", color = AppTheme.accent)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (vm.tasks.isEmpty()) {
                EmptyHint("还没有定时任务")
            } else {
                vm.tasks.forEachIndexed { i, t ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    t.time.ifBlank { "--:--" },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AppTheme.textPrimary
                                )
                                Text(
                                    t.actionLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTheme.textSecondary
                                )
                                if (t.repeatDaily) {
                                    Text(
                                        "每天重复",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppTheme.accent
                                    )
                                }
                            }
                            TextButton(
                                onClick = { pendingDelete = t.id },
                                enabled = !loading
                            ) { Text("删除", color = AppTheme.textSecondary) }
                        }
                        if (t.rawAction.isNotBlank() && t.rawAction != "{}") {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                t.rawAction.take(120),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppTheme.textSecondary.copy(alpha = 0.7f)
                            )
                        }
                    }
                    if (i != vm.tasks.lastIndex) ThinDivider()
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        scope.launch {
                            val r = vm.clearTasks()
                            toast(r)
                        }
                    }, enabled = !loading) { Text("清空全部", color = AppTheme.textSecondary) }
                }
            }
        }

        // ---------------------------------------------------------- 设备自带计划重启
        AppCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("计划重启")
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "设备自带功能，到点自动重启。比自定义任务更省事。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.textSecondary
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { showSchedule = true }, enabled = !loading) {
                    Text("设置", color = AppTheme.accent)
                }
            }
        }

        Text(
            "说明：定时任务由设备侧执行，App 不在线也会生效。",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.textSecondary,
            modifier = Modifier.padding(start = 4.dp)
        )
    }

    if (showAdd) {
        AddTaskDialog(
            onDismiss = { showAdd = false },
            onSave = { time, repeat, actionJson ->
                scope.launch {
                    val r = vm.addTask(time, repeat, actionJson)
                    toast(r)
                    showAdd = false
                }
            }
        )
    }

    if (showSchedule) {
        RestartScheduleDialog(
            onDismiss = { showSchedule = false },
            onSave = { enabled, time ->
                scope.launch {
                    val r = vm.setRestartSchedule(enabled, time)
                    toast(r)
                    showSchedule = false
                }
            }
        )
    }

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除任务") },
            text = { Text("确定删除这个定时任务？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val r = vm.removeTask(id)
                        toast(r)
                    }
                }) { Text("删除", color = AppTheme.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = AppTheme.textSecondary)
                }
            }
        )
    }
}

/** 常用动作模板 → 提交给设备的 action JSON */
private val ACTION_TEMPLATES = listOf(
    "重启设备" to """{"action":"reboot"}""",
    "关闭 WiFi" to """{"action":"wifi","value":"0"}""",
    "开启 WiFi" to """{"action":"wifi","value":"1"}""",
)

@Composable
private fun AddTaskDialog(
    onDismiss: () -> Unit,
    onSave: (String, Boolean, String) -> Unit
) {
    var hour by remember { mutableStateOf("03") }
    var minute by remember { mutableStateOf("00") }
    var repeat by remember { mutableStateOf(true) }
    var actionIdx by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建定时任务") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        LabeledField("时（00-23）", hour, { hour = it.filter { c -> c.isDigit() }.take(2) })
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        LabeledField("分（00-59）", minute, { minute = it.filter { c -> c.isDigit() }.take(2) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("动作", style = MaterialTheme.typography.labelMedium, color = AppTheme.textSecondary)
                Spacer(Modifier.height(6.dp))
                Column {
                    ACTION_TEMPLATES.forEachIndexed { i, (label, _) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OptionChip(label, actionIdx == i) { actionIdx = i }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = repeat,
                        onCheckedChange = { repeat = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AppTheme.btnBg,
                            checkedTrackColor = AppTheme.accent
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("每天重复", style = MaterialTheme.typography.bodyMedium, color = AppTheme.textPrimary)
                }
            }
        },
        confirmButton = {
            val h = hour.toIntOrNull()
            val m = minute.toIntOrNull()
            val valid = h != null && h in 0..23 && m != null && m in 0..59
            TextButton(
                onClick = {
                    val time = "%02d:%02d".format(h ?: 0, m ?: 0)
                    onSave(time, repeat, ACTION_TEMPLATES[actionIdx].second)
                },
                enabled = valid
            ) { Text("保存", color = AppTheme.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppTheme.textSecondary) }
        }
    )
}

@Composable
private fun RestartScheduleDialog(
    onDismiss: () -> Unit,
    onSave: (Boolean, String) -> Unit
) {
    var enabled by remember { mutableStateOf(true) }
    var hour by remember { mutableStateOf("03") }
    var minute by remember { mutableStateOf("00") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("计划重启") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AppTheme.btnBg,
                            checkedTrackColor = AppTheme.accent
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("启用", style = MaterialTheme.typography.bodyMedium, color = AppTheme.textPrimary)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        LabeledField("时", hour, { hour = it.filter { c -> c.isDigit() }.take(2) })
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        LabeledField("分", minute, { minute = it.filter { c -> c.isDigit() }.take(2) })
                    }
                }
            }
        },
        confirmButton = {
            val h = hour.toIntOrNull()
            val m = minute.toIntOrNull()
            TextButton(
                onClick = { onSave(enabled, "%02d:%02d".format(h ?: 3, m ?: 0)) },
                enabled = h != null && h in 0..23 && m != null && m in 0..59
            ) { Text("保存", color = AppTheme.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppTheme.textSecondary) }
        }
    )
}
