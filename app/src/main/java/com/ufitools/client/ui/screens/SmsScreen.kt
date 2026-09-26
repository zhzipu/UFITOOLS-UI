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

    LaunchedEffect(Unit) {
        vm.refreshSms()
    }

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
                "短信列表（未读 ${vm.smsUnread}）",
                Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
        }

        if (vm.sms.isEmpty()) {
            item {
                AppCard {
                    Text("暂无短信", color = AppTheme.textSecondary)
                }
            }
        }

        items(vm.sms) { msg ->
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
