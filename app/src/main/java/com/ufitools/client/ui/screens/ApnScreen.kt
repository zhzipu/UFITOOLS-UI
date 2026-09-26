package com.ufitools.client.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufitools.client.model.ApnProfile
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.ErrorBanner
import com.ufitools.client.ui.components.KeyValueRow
import com.ufitools.client.ui.components.LabeledField
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.SubPageScaffold
import com.ufitools.client.ui.components.ThinDivider
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * APN 管理（API 文档 §7）。
 *
 * 设备上有两套 APN：
 * - **自动**：运营商下发，只读，切到"自动模式"时生效；
 * - **手动**：用户自建，可增删改，并指定其中一个为当前启用档位。
 *
 * 界面把「模式」放在最上面（自动/手动二选一），下面按模式展示对应列表。
 */
@Composable
fun ApnScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loading = "apn" in vm.pageLoading
    val error = vm.pageError["apn"]

    var editing by remember { mutableStateOf<ApnProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ApnProfile?>(null) }

    LaunchedEffect(Unit) { vm.refreshApn() }

    fun toast(msg: String) {
        Toast.makeText(context, if (msg == "success") "已保存" else msg, Toast.LENGTH_SHORT).show()
    }

    SubPageScaffold(
        title = "APN 管理",
        onBack = { nav.popBackStack() },
        loading = loading,
        onRefresh = { scope.launch { vm.refreshApn() } }
    ) {
        ErrorBanner(error)

        // ---------------------------------------------------------- 模式
        AppCard {
            SectionTitle("APN 模式")
            Spacer(Modifier.height(4.dp))
            Text(
                "自动模式下使用运营商下发的 APN；手动模式下使用你自建的档位。",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.textSecondary
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                OptionChip("自动", vm.apnMode == 0, enabled = !loading) {
                    scope.launch {
                        val r = vm.applyApnMode(0)
                        toast(r)
                        if (r == "success") vm.refreshApn()
                    }
                }
                OptionChip("手动", vm.apnMode == 1, enabled = !loading) {
                    scope.launch {
                        val r = vm.applyApnMode(1)
                        toast(r)
                        if (r == "success") vm.refreshApn()
                    }
                }
            }
        }

        // ---------------------------------------------------------- 自动档位（只读）
        if (vm.apnMode == 0) {
            AppCard {
                SectionTitle("运营商自动下发")
                Spacer(Modifier.height(6.dp))
                if (vm.apnAuto.isEmpty()) {
                    EmptyHint("未读到自动 APN 档位")
                } else {
                    vm.apnAuto.forEachIndexed { i, p ->
                        ApnCard(p, readonly = true, onEnable = null, onEdit = null, onDelete = null, loading = loading)
                        if (i != vm.apnAuto.lastIndex) ThinDivider(Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }

        // ---------------------------------------------------------- 手动档位
        AppCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SectionTitle("自建档位")
                TextButton(onClick = { creating = true }, enabled = !loading) {
                    Text("+ 新建", color = AppTheme.accent)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (vm.apnManual.isEmpty()) {
                EmptyHint("还没有自建 APN")
            } else {
                vm.apnManual.forEachIndexed { i, p ->
                    ApnCard(
                        p,
                        readonly = false,
                        loading = loading,
                        onEnable = {
                            scope.launch {
                                val r = vm.enableApn(p.profileId)
                                toast(r)
                                if (r == "success") vm.refreshApn()
                            }
                        },
                        onEdit = { editing = p },
                        onDelete = { pendingDelete = p }
                    )
                    if (i != vm.apnManual.lastIndex) ThinDivider(Modifier.padding(vertical = 4.dp))
                }
            }
        }

        Text(
            "提示：切换 APN 后网络会短暂断开重连，属正常现象。",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.textSecondary,
            modifier = Modifier.padding(start = 4.dp)
        )
    }

    // ---------------------------------------------------------- 编辑 / 新建弹窗
    val target = when {
        creating -> ApnProfile(profileId = "", name = "", apn = "")
        else -> editing
    }
    if (target != null) {
        ApnEditDialog(
            initial = target,
            isNew = creating,
            onDismiss = { creating = false; editing = null },
            onSave = { profile ->
                scope.launch {
                    val r = if (creating) vm.addApn(profile) else vm.updateApn(profile)
                    toast(r)
                    creating = false
                    editing = null
                    if (r == "success") vm.refreshApn()
                }
            }
        )
    }

    // ---------------------------------------------------------- 删除确认
    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 APN") },
            text = { Text("确定删除「${p.name.ifBlank { p.apn }}」？该操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val id = p.profileId
                    pendingDelete = null
                    scope.launch {
                        val r = vm.deleteApn(id)
                        toast(r)
                        if (r == "success") vm.refreshApn()
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

@Composable
private fun ApnCard(
    p: ApnProfile,
    readonly: Boolean,
    loading: Boolean,
    onEnable: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                p.name.ifBlank { p.apn.ifBlank { p.profileId } },
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.textPrimary
            )
            if (p.enabled) {
                Text("使用中", style = MaterialTheme.typography.labelSmall, color = AppTheme.accent)
            }
        }
        Spacer(Modifier.height(4.dp))
        KeyValueRow("APN", p.apn, copyable = true)
        if (p.username.isNotBlank()) KeyValueRow("用户名", p.username, copyable = true)
        if (p.pdpType.isNotBlank()) KeyValueRow("协议", p.pdpType)
        if (p.pppAuthMode.isNotBlank()) KeyValueRow("鉴权", ApnProfile.authLabel(p.pppAuthMode))
        if (readonly) {
            Spacer(Modifier.height(4.dp))
            Text(
                "运营商下发，不可编辑",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textSecondary
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!p.enabled && onEnable != null) {
                    TextButton(onClick = onEnable, enabled = !loading) {
                        Text("启用", color = AppTheme.accent)
                    }
                }
                TextButton(onClick = { onEdit?.invoke() }, enabled = !loading) {
                    Text("编辑", color = AppTheme.accent)
                }
                TextButton(onClick = { onDelete?.invoke() }, enabled = !loading) {
                    Text("删除", color = AppTheme.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun ApnEditDialog(
    initial: ApnProfile,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ApnProfile) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var apn by remember { mutableStateOf(initial.apn) }
    var user by remember { mutableStateOf(initial.username) }
    var pwd by remember { mutableStateOf(initial.password) }
    var pdp by remember { mutableStateOf(initial.pdpType.ifBlank { "IP" }) }
    var auth by remember { mutableStateOf(initial.pppAuthMode.ifBlank { "0" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建 APN" else "编辑 APN") },
        text = {
            Column {
                LabeledField("名称", name, { name = it }, placeholder = "如 中国移动")
                Spacer(Modifier.height(8.dp))
                LabeledField("APN", apn, { apn = it }, placeholder = "如 cmnet")
                Spacer(Modifier.height(8.dp))
                LabeledField("用户名（可选）", user, { user = it })
                Spacer(Modifier.height(8.dp))
                LabeledField("密码（可选）", pwd, { pwd = it })
                Spacer(Modifier.height(12.dp))
                Text("协议类型", style = MaterialTheme.typography.labelMedium, color = AppTheme.textSecondary)
                Spacer(Modifier.height(6.dp))
                Row {
                    ApnProfile.PDP_OPTIONS.forEach { opt ->
                        OptionChip(opt, pdp == opt) { pdp = opt }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("鉴权方式", style = MaterialTheme.typography.labelMedium, color = AppTheme.textSecondary)
                Spacer(Modifier.height(6.dp))
                Row {
                    ApnProfile.AUTH_OPTIONS.forEach { (v, label) ->
                        OptionChip(label, auth == v) { auth = v }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            apn = apn.trim(),
                            username = user.trim(),
                            password = pwd,
                            pdpType = pdp,
                            pppAuthMode = auth,
                            roamingPdpType = pdp,
                        )
                    )
                },
                enabled = apn.isNotBlank()
            ) { Text("保存", color = AppTheme.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppTheme.textSecondary) }
        }
    )
}
