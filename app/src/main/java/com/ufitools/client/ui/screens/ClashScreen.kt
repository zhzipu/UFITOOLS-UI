package com.ufitools.client.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufitools.client.data.ClashConfig
import com.ufitools.client.model.ClashConnection
import com.ufitools.client.model.ClashProxy
import com.ufitools.client.model.bytesToHuman
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.KeyValueRow
import com.ufitools.client.ui.components.MetricCell
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.StaggeredFadeIn
import com.ufitools.client.ui.components.ThinDivider
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.ui.theme.StatusGood
import com.ufitools.client.ui.theme.StatusWarn
import com.ufitools.client.viewmodel.CLASH_NOT_RUNNING_HINT
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 常量与小工具统一放在 ClashCommon.kt：拆 Tab 后 private 不再跨文件可见

/**
 * Clash 控制面板（mihomo external-controller，原生 UI 实现）。
 *
 * 这是**纯原生 Compose** 的控制台，不内嵌 WebView：
 * 面板前端（如 zashboard）本质是这些 REST 接口的展示层，这里直接对接内核，
 * 好处是启动快、省内存、与 App 主题一致，也不受面板前端版本变动影响
 * （zashboard 已移除 sing-box 支持之类的变更不会波及本页）。
 *
 * ## 信息架构
 *
 * 页内再分五个二级 Tab，对齐 zashboard 的导航：
 * 概览（版本/模式/流量）→ 代理（策略组卡片网格 + 测速）→ 连接 →
 * 规则（分流规则 + 规则集）→ 日志（WebSocket 实时流）。
 *
 * 之所以拆 Tab 而不是一页到底：单页在接入规则与日志后会长到 2000 行以上，
 * 手机上一屏只能看到一两块内容，滚动找东西的成本已经超过翻 Tab。
 *
 * 数据轮询策略：进页拉一次全量（版本/配置/代理/订阅），
 * 同时起一个 5 秒的轻量循环只刷连接与速率；离开页面即停。
 * 日志是**长连接**，单独由日志 Tab 的进入/离开驱动，不参与轮询。
 */
@Composable
fun ClashScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loading = "clash" in vm.pageLoading || "clashConn" in vm.pageLoading

    // 当前二级 Tab。用 rememberSaveable 让横竖屏切换后仍停在原来那页
    var tab by rememberSaveable { mutableStateOf(ClashTab.OVERVIEW) }

    var expandedGroup by remember { mutableStateOf<String?>(null) }
    var pendingCloseAll by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ClashConnection?>(null) }
    var bulkProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun toast(msg: String) {
        Toast.makeText(context, if (msg == "success") "完成" else msg, Toast.LENGTH_SHORT).show()
    }

    // 进页自动连接：读设备配置拿 secret → 探活（只跑一次，见 vm.autoConnectClash）
    LaunchedEffect(Unit) { vm.autoConnectClash() }

    // 连接列表轻量轮询：只刷连接，不动代理（代理刷新代价高）。
    // 速率的计算依赖这个固定间隔，不能改成「有变化才刷」，否则两次采样的
    // 时间差不可控，算出来的速率会乱跳。
    LaunchedEffect(Unit) {
        while (true) {
            delay(CONN_AUTO_REFRESH_MS)
            vm.refreshClashConnections(silent = true)
        }
    }

    // 首次进入且还没探测过 → 自动展开第一个策略组，省去一次点击
    val groups = vm.clashGroups
    LaunchedEffect(groups.size) {
        if (expandedGroup == null && groups.isNotEmpty()) expandedGroup = groups.first().name
    }

    // ⚠️ 日志长连接的生命周期绑定在 Tab 上：
    // 只有停在日志页才保持连接，切走立刻断开（省电、省设备端资源）。
    // DisposableEffect 保证「离开页面」和「切走 Tab」两条路径都能断开。
    DisposableEffect(tab, vm.clashOnline) {
        if (tab == ClashTab.LOGS && vm.clashOnline == true) vm.enterClashLogs()
        onDispose { if (tab == ClashTab.LOGS) vm.leaveClashLogs() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        // ---------------------------------------------------------------- 顶栏
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "猫猫面板",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppTheme.textPrimary
                )
                // 这里原本在标题下方显示内核地址（http://<host>:9090）。
                // 地址是写死的、且进页就会自动连接，对用户没有信息量，
                // 按需求去掉，只留标题。
            }
            if (loading || vm.clashAutoConnecting) {
                CircularProgressIndicator(
                    color = AppTheme.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            // 只需刷新——地址固定、secret 自动读，已经没有可配置项
            IconButton(onClick = { scope.launch { vm.retryClashAuto() } }) {
                Icon(Icons.Filled.Refresh, contentDescription = "重新连接", tint = AppTheme.iconTint)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------------------- 未连通形态
        // 连不上内核时没有二级 Tab 可谈，整页只留「怎么修」的指引
        if (vm.clashOnline != true) {
            // ⚠️ 区分「还没探测完」与「探测失败」：
            // clashOnline == null 说明一次都没探过（刚进页面，LaunchedEffect 还没跑完），
            // 这时显示「未安装」是错的——会先闪一下错误文案再跳成正常内容。
            val notProbedYet = vm.clashOnline == null
            val busy = vm.clashAutoConnecting || loading || notProbedYet
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                AppCard {
                    SectionTitle(if (busy) "正在连接…" else CLASH_NOT_RUNNING_HINT)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "猫猫服务运行在设备上（mihomo 内核），地址固定为 " +
                            "${ClashConfig.DEFAULT_BASE_URL}，密码会自动从设备读取。\n\n" +
                            "请确认设备上已安装并启动猫猫服务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.textSecondary
                    )
                    if (!busy && vm.clashProbe?.found == false) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "已尝试读取设备配置，但没找到 mihomo 配置文件——内核可能未安装。",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusWarn
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        OptionChip(
                            "重试",
                            false,
                            enabled = !busy
                        ) {
                            scope.launch { vm.retryClashAuto() }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            return
        }

        // ---------------------------------------------------------------- 二级 Tab 栏
        ClashTabBar(
            selected = tab,
            onSelect = { tab = it },
        )
        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------------- Tab 内容
        // 每个 Tab 自己管滚动容器：日志页要用 LazyColumn（有自己的滚动），
        // 套在外层 verticalScroll 里会因无限约束崩溃。
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                ClashTab.OVERVIEW -> ClashOverviewTab(
                    vm = vm,
                    loading = loading,
                    toast = ::toast,
                    modifier = Modifier.fillMaxSize(),
                )

                ClashTab.PROXIES -> ClashProxiesTab(
                    vm = vm,
                    loading = loading,
                    bulkProgress = bulkProgress,
                    onBulkProgress = { bulkProgress = it },
                    toast = ::toast,
                    modifier = Modifier.fillMaxSize(),
                )

                ClashTab.CONNECTIONS -> ClashConnectionsTab(
                    vm = vm,
                    loading = loading,
                    modifier = Modifier.fillMaxSize(),
                    onCloseAll = { pendingCloseAll = true },
                    onDetail = { detail = it },
                )

                ClashTab.RULES -> ClashRulesTab(
                    vm = vm,
                    modifier = Modifier.fillMaxSize(),
                    toast = ::toast,
                )

                ClashTab.LOGS -> ClashLogsTab(
                    vm = vm,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // ---------------------------------------------------------------- 弹窗
    if (pendingCloseAll) {
        AlertDialog(
            onDismissRequest = { pendingCloseAll = false },
            title = { Text("关闭全部连接") },
            text = { Text("将断开内核上所有活动连接，正在下载的任务会中断。确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingCloseAll = false
                    scope.launch { toast(vm.closeAllClashConnections()) }
                }) { Text("关闭", color = AppTheme.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCloseAll = false }) {
                    Text("取消", color = AppTheme.textSecondary)
                }
            }
        )
    }

    detail?.let { c ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("连接详情") },
            text = {
                Column {
                    KeyValueRow("目标", c.destinationIp.ifBlank { "-" })
                    KeyValueRow("域名", c.host.ifBlank { "-" })
                    KeyValueRow("来源", c.sourceIp.ifBlank { "-" })
                    KeyValueRow("协议", "${c.network.ifBlank { "-" }} / ${c.type.ifBlank { "-" }}")
                    KeyValueRow("规则", c.ruleText.ifBlank { "-" })
                    KeyValueRow("链路", c.chainsText.ifBlank { "-" })
                    KeyValueRow("上传", "${c.uploadText}   ${c.uploadSpeedText}")
                    KeyValueRow("下载", "${c.downloadText}   ${c.downloadSpeedText}")
                    if (c.start > 0) KeyValueRow("开始于", clashFormatTime(c.start))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = c.id
                    detail = null
                    scope.launch { toast(vm.closeClashConnection(id)) }
                }) { Text("关闭该连接", color = AppTheme.accent) }
            },
            dismissButton = {
                TextButton(onClick = { detail = null }) {
                    Text("返回", color = AppTheme.textSecondary)
                }
            }
        )
    }
}
// ------------------------------------------------------------------ 子组件

/** 策略组标题行：组名 + 当前选中 + 展开箭头 + 测速按钮 */
@Composable
private fun GroupHeader(
    group: ClashProxy,
    expanded: Boolean,
    testing: Boolean,
    loading: Boolean,
    onToggle: () -> Unit,
    onTest: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                // 组类型标记：Selector 可手动选，URLTest 自动选
                Text(
                    groupTypeLabel(group.type),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.textSecondary
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                group.now.ifBlank { "未选择" },
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (testing) {
            CircularProgressIndicator(
                color = AppTheme.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        TextButton(onClick = onTest, enabled = !loading && !testing) {
            Text("测速", color = AppTheme.accent)
        }
        Text(
            if (expanded) "收起" else "展开",
            style = MaterialTheme.typography.labelMedium,
            color = AppTheme.textSecondary
        )
    }
}

/** 组内节点列表：每行可点选，右侧显示延迟与单独测速入口 */
@Composable
private fun GroupNodes(
    group: ClashProxy,
    vm: MainViewModel,
    onPick: (String) -> Unit,
    onTestNode: (String) -> Unit
) {
    // 组内成员可能是子组，也可能是节点；按名字去 clashProxies 找详细信息
    val byName = remember(vm.clashProxies) { vm.clashProxies.associateBy { it.name } }
    Column(Modifier.fillMaxWidth()) {
        group.all.forEach { name ->
            val node = byName[name]
            val selected = name == group.now
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPick(name) }
                    .padding(vertical = 9.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 选中指示：实心圆点，避免用 Material RadioButton 拉高行高
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (selected) AppTheme.accent
                            else AppTheme.textPrimary.copy(alpha = 0.15f)
                        )
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) AppTheme.accent else AppTheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sub = buildString {
                        node?.let { n ->
                            if (n.isGroup) append("策略组")
                            if (n.providerName.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(n.providerName)
                            }
                        }
                    }
                    if (sub.isNotEmpty()) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTheme.textSecondary
                        )
                    }
                }
                val testing = name in vm.clashTesting
                if (testing) {
                    CircularProgressIndicator(
                        color = AppTheme.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                } else if (node != null && !node.isGroup) {
                    val d = node.delay
                    Text(
                        node.delayText,
                        style = MaterialTheme.typography.labelMedium,
                        color = delayColor(d)
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = "测速",
                        tint = AppTheme.iconTint,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onTestNode(name) }
                    )
                }
            }
        }
        if (group.all.isEmpty()) {
            EmptyHint("该策略组没有成员")
        }
        if (group.testUrl.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "测速地址：${group.testUrl}",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 单条活动连接 */
@Composable
internal fun ConnectionRow(c: ClashConnection, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(
            c.title,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            c.chainsText.ifBlank { c.ruleText },
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "↓ ${c.downloadText}  ↑ ${c.uploadText}",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textSecondary,
            )
            // 实时速率只在有流量时出现，静止连接不占视觉重量
            if (c.downloadSpeed > 0 || c.uploadSpeed > 0) {
                Spacer(Modifier.padding(horizontal = 5.dp))
                Text(
                    "↓ ${c.downloadSpeedText}  ↑ ${c.uploadSpeedText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.accent,
                )
            }
        }
    }
}


