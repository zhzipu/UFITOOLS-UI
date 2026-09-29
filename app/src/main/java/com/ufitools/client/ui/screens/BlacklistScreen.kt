package com.ufitools.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufitools.client.model.normalizeMac
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.LabeledField
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.SubPageScaffold
import com.ufitools.client.ui.components.ThinDivider
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 设备接入黑名单（MAC 黑名单）。
 *
 * 作用是「禁止这些设备连上本 WiFi」—— 与「设置 → 短信转发」里那个
 * **短信转发黑名单**（拦截哪些短信不转发）完全是两回事，不要混淆。
 *
 * ## 设备契约（实测，详见 model/ClientDevice.kt:AclState）
 * - 列表在设备端是**分号分隔的字符串**，不是 JSON 数组；
 * - 设备会**自行重排 MAC 顺序**，且**会丢弃名称**（中文名实测写进去就没了）；
 * - 因此本页**只以 MAC 为主键**，名称仅作辅助显示，且写完后一律以设备回读结果为准。
 *
 * ## 为什么读之前要登录
 * 读取所需的 `cmd` 组合里必须带 `queryDeviceAccessControlList` 这个**触发器**，
 * 且**需要登录 Cookie**，否则设备回 `{}`（详见 GoformClient.deviceAccessControlList）。
 */
@Composable
fun BlacklistScreen(vm: MainViewModel, nav: NavHostController) {
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    // 新增条目的输入
    var showAdd by remember { mutableStateOf(false) }
    var newMac by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    // 待确认移除的 MAC
    var removeTarget by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    // 顶部一闪而过的结果提示
    var msg by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            vm.refreshBlacklist()
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    // 提示 4 秒后自动消失
    val tip = msg
    if (tip != null) {
        LaunchedEffect(tip) {
            kotlinx.coroutines.delay(4_000)
            msg = null
        }
    }

    SubPageScaffold(
        title = "黑名单",
        onBack = { nav.popBackStack() },
        loading = loading || working,
        onRefresh = { reload() }
    ) {
        val err = vm.aclError
        val state = vm.aclState

        // ── 说明 + 新增按钮 ──
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("禁止连接 WiFi 的设备")
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        newMac = ""
                        newName = ""
                        addError = null
                        showAdd = true
                    },
                    enabled = !working
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "添加黑名单",
                        tint = AppTheme.accent
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "加入黑名单的设备将无法连上本随身 WiFi。" +
                    "设备端会对列表重新排序、且不保证保留名称，因此这里以 MAC 为准。",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textSecondary
            )
            if (tip != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    tip,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.accent
                )
            }
        }

        // ── 读取失败提示 ──
        if (err != null) {
            AppCard {
                Text(
                    err,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFF4D4F)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "读取黑名单需要登录设备后台。请到「设置 → 修改口令」确认填的是" +
                        "设备 Web 控制台的管理密码。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.textSecondary
                )
            }
        }

        // ── 黑名单列表 ──
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("黑名单条目")
                Spacer(Modifier.weight(1f))
                if (state != null) {
                    Text(
                        "${state.blackMacs.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textSecondary
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            when {
                state == null -> Text(
                    if (loading) "读取中…" else "暂无数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.textMuted,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                state.blackMacs.isEmpty() -> EmptyHint("黑名单为空")
                else -> state.entries.forEachIndexed { i, (mac, name) ->
                    if (i > 0) ThinDivider()
                    BlacklistRow(
                        mac = mac,
                        name = name,
                        enabled = !working,
                        onRemove = { removeTarget = mac }
                    )
                }
            }
        }

        // 底部操作：一键清空（等价于关掉接入控制）
        if (state != null && state.blackMacs.isNotEmpty()) {
            TextButton(
                enabled = !working,
                onClick = {
                    scope.launch {
                        working = true
                        val e = vm.saveBlacklist(emptyList(), emptyList())
                        working = false
                        msg = e ?: "已清空黑名单"
                    }
                }
            ) {
                Text("清空全部黑名单", color = Color(0xFFFF4D4F))
            }
        }
    }

    // ── 新增弹窗 ──
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { if (!working) showAdd = false },
            title = { Text("添加黑名单") },
            text = {
                Column {
                    LabeledField(
                        label = "MAC 地址",
                        value = newMac,
                        onValueChange = {
                            newMac = it
                            addError = null
                        },
                        placeholder = "如 AA:BB:CC:DD:EE:FF"
                    )
                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "备注（可选）",
                        value = newName,
                        onValueChange = { newName = it },
                        supportingText = "设备端可能不保留备注，可留空"
                    )
                    val e = addError
                    if (e != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            e,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFF4D4F)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !working,
                    onClick = {
                        val mac = normalizeMac(newMac)
                        if (mac == null) {
                            addError = "MAC 格式不对。正确形式：AA:BB:CC:DD:EE:FF（也接受连字符）"
                            return@TextButton
                        }
                        val cur = vm.aclState
                        if (cur?.containsMac(mac) == true) {
                            addError = "这个 MAC 已经在黑名单里了"
                            return@TextButton
                        }
                        scope.launch {
                            working = true
                            val macs = (cur?.blackMacs ?: emptyList()) + mac
                            val names = (cur?.blackNames ?: emptyList()) + newName.trim()
                            val e = vm.saveBlacklist(macs, names)
                            working = false
                            if (e == null) {
                                showAdd = false
                                msg = "已添加 $mac"
                            } else {
                                addError = e
                            }
                        }
                    }
                ) { Text(if (working) "处理中…" else "添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("取消") }
            }
        )
    }

    // ── 移除确认 ──
    val rm = removeTarget
    if (rm != null) {
        AlertDialog(
            onDismissRequest = { if (!working) removeTarget = null },
            title = { Text("移出黑名单") },
            text = { Text("解除后「$rm」可以重新连上本 WiFi。") },
            confirmButton = {
                TextButton(
                    enabled = !working,
                    onClick = {
                        scope.launch {
                            working = true
                            val e = vm.unblockMac(rm)
                            working = false
                            removeTarget = null
                            msg = e ?: "已移出黑名单"
                        }
                    }
                ) { Text(if (working) "处理中…" else "解除") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 黑名单里的一行。
 *
 * ⚠️ **只显示 MAC 作为主标识，名称最多作极小字体的附加信息**：
 * 设备会重排列表并丢弃名称，若把名称当标题，会出现「名字和 MAC 对不上」的错误观感。
 */
@Composable
private fun BlacklistRow(
    mac: String,
    name: String,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFFF4D4F).copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = null,
                tint = Color(0xFFFF4D4F),
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                mac,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (name.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRemove, enabled = enabled) {
            Text("解除", color = AppTheme.accent)
        }
    }
}
