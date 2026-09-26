package com.ufitools.client.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.ufitools.client.model.SmsMessage
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.ui.theme.StatusBad
import com.ufitools.client.ui.theme.StatusGood
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun SmsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 过滤与批量操作状态。
    // 短信一多，平铺列表根本翻不动，所以加了「只看未读 / 按号码筛选 / 批量清零」这组能力。
    var unreadOnly by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var deletingAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshSms()
    }

    // 本地过滤：设备侧一条短信一次请求，批量删要逐条发，所以筛选放在本地做。
    val shown = remember(vm.sms, unreadOnly, query) {
        val q = query.trim()
        vm.sms.filter { m ->
            (!unreadOnly || m.isUnread) &&
                (q.isEmpty() || m.number.contains(q) || m.decodedContent.contains(q, ignoreCase = true))
        }
    }
    val unreadCount = vm.sms.count { it.isUnread }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(20.dp))
            Text(
                "短信",
                style = MaterialTheme.typography.headlineMedium,
                color = AppTheme.textPrimary
            )
            Spacer(Modifier.height(12.dp))
            SectionTitle("发送短信", Modifier.padding(start = 4.dp, bottom = 8.dp))
            SendSmsCard { number, content ->
                scope.launch {
                    val r = vm.sendSms(number, content)
                    toast(context, if (r == "success") "发送成功" else r)
                    if (r == "success") vm.refreshSms()
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            SectionTitle(
                "短信列表（共 ${vm.sms.size} · 未读 $unreadCount）",
                Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            AppCard {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索号码或内容") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.accent,
                        unfocusedBorderColor = AppTheme.textPrimary.copy(alpha = 0.15f),
                        focusedLabelColor = AppTheme.accent,
                        unfocusedLabelColor = AppTheme.textSecondary,
                        cursorColor = AppTheme.accent,
                        focusedTextColor = AppTheme.textPrimary,
                        unfocusedTextColor = AppTheme.textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OptionChip("只看未读", unreadOnly) { unreadOnly = !unreadOnly }
                    if (query.isNotBlank()) {
                        OptionChip("清除搜索", false) { query = "" }
                    }
                }
                if (shown.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { confirmDeleteAll = true },
                            enabled = !deletingAll
                        ) {
                            Text(
                                if (deletingAll) "删除中…" else "删除当前 ${shown.size} 条",
                                color = StatusBad
                            )
                        }
                    }
                }
            }
        }

        if (shown.isEmpty()) {
            item {
                AppCard {
                    Text(
                        when {
                            vm.sms.isEmpty() -> "暂无短信"
                            else -> "没有匹配的短信"
                        },
                        color = AppTheme.textSecondary
                    )
                }
            }
        }

        items(shown, key = { it.id }) { msg ->
            SmsItem(
                msg,
                onDelete = {
                    scope.launch {
                        val r = vm.deleteSms(msg.id)
                        toast(context, if (r == "success") "已删除" else r)
                        if (r == "success") vm.refreshSms()
                    }
                },
                onMarkRead = {
                    scope.launch {
                        val r = vm.markSmsRead(msg.id)
                        toast(context, if (r == "success") "已标记已读" else r)
                        if (r == "success") vm.refreshSms()
                    }
                }
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmDeleteAll) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("批量删除") },
            text = { Text("将删除当前筛选出的 ${shown.size} 条短信，该操作不可撤销。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDeleteAll = false
                    deletingAll = true
                    scope.launch {
                        // 设备没有批量删除接口，逐条发；失败的记下来，最后如实汇报
                        var ok = 0
                        var fail = 0
                        shown.forEach { m ->
                            if (vm.deleteSms(m.id) == "success") ok++ else fail++
                        }
                        deletingAll = false
                        vm.refreshSms()
                        toast(
                            context,
                            if (fail == 0) "已删除 $ok 条" else "删除完成：成功 $ok，失败 $fail"
                        )
                    }
                }) { Text("删除", color = StatusBad) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDeleteAll = false }) {
                    Text("取消", color = AppTheme.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun SendSmsCard(onSend: (String, String) -> Unit) {
    var number by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AppTheme.accent,
        unfocusedBorderColor = AppTheme.textPrimary.copy(alpha = 0.15f),
        focusedLabelColor = AppTheme.accent,
        unfocusedLabelColor = AppTheme.textSecondary,
        cursorColor = AppTheme.accent,
        focusedTextColor = AppTheme.textPrimary,
        unfocusedTextColor = AppTheme.textPrimary
    )

    AppCard {
        OutlinedTextField(
            value = number,
            onValueChange = { number = it.filter { c -> c.isDigit() || c == '+' } },
            label = { Text("号码") },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text("内容") },
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = { if (number.isNotBlank() && content.isNotBlank()) onSend(number.trim(), content.trim()) },
            enabled = number.isNotBlank() && content.isNotBlank(),
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = AppTheme.accent)
            Spacer(Modifier.width(4.dp))
            Text("发送", color = AppTheme.accent)
        }
    }
}

@Composable
private fun SmsItem(
    msg: SmsMessage,
    onDelete: () -> Unit,
    onMarkRead: () -> Unit
) {
    AppCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                msg.number.ifBlank { "未知号码" },
                style = MaterialTheme.typography.titleLarge,
                color = AppTheme.textPrimary,
                modifier = Modifier.weight(1f)
            )
            if (msg.isUnread) {
                Text("未读", color = StatusGood, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
            }
            if (msg.isSendFailed) {
                Text("发送失败", color = StatusBad, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            msg.decodedContent,
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.textPrimary
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                msg.displayDate,
                style = MaterialTheme.typography.labelMedium,
                color = AppTheme.textSecondary,
                modifier = Modifier.weight(1f)
            )
            if (msg.isUnread) {
                IconButton(onClick = onMarkRead) {
                    Icon(Icons.Filled.MarkEmailRead, contentDescription = "标记已读", tint = AppTheme.iconTint)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = StatusBad)
            }
        }
    }
}

private fun toast(context: android.content.Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
