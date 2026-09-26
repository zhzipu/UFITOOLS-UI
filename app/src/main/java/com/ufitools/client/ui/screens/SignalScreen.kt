package com.ufitools.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.SimCard
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.ufitools.client.model.NetworkMode
import com.ufitools.client.model.carrierLabel
import com.ufitools.client.model.firstValidRsrp
import com.ufitools.client.model.networkTypeLabel
import com.ufitools.client.model.SignalGrade
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.CarrierLogo
import com.ufitools.client.ui.components.KeyValueRow
import com.ufitools.client.ui.components.MetricCell
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.StaggeredFadeIn
import com.ufitools.client.ui.components.ThinDivider
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.ui.theme.StatusBad
import com.ufitools.client.ui.theme.StatusGood
import com.ufitools.client.ui.theme.StatusInfo
import com.ufitools.client.ui.theme.StatusWarn
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SignalScreen(vm: MainViewModel) {
    val live = vm.live
    val scope = rememberCoroutineScope()

    // 该固件 rssi 恒为 0，真实信号强度在 Z5g_rsrp / lte_rsrp，最后才回退 rssi
    val rsrp = live["Z5g_rsrp"]?.takeIf { it.toDoubleOrNull() != 0.0 }
        ?: live["lte_rsrp"]?.takeIf { it.toDoubleOrNull() != 0.0 }
        ?: live["rssi"]

    // 信号评估等级：把「几格」换成人话（优秀/良好/一般/糟糕/不可用）。
    // 阈值与 signalBarsOf 同源（-80/-90/-100/-110），见 model/SignalGrade.kt。
    // 注：本页已不再显示格数，故不再计算 signalBars，避免留下死变量。
    val grade = SignalGrade.from(firstValidRsrp(rsrp))

    var volteEnabled by remember { mutableStateOf<Boolean?>(null) }
    var vonrEnabled by remember { mutableStateOf<Boolean?>(null) }

    // 网络模式优先级：待确认的目标 / 是否正在下发 / 结果提示
    var pendingMode by remember { mutableStateOf<NetworkMode?>(null) }
    var modeBusy by remember { mutableStateOf(false) }
    var modeMsg by remember { mutableStateOf<String?>(null) }
    val currentModeLabel = NetworkMode.labelOf(live["net_select"])
    val currentMode = NetworkMode.byValue(live["net_select"])

    LaunchedEffect(Unit) {
        try {
            volteEnabled = vm.volteStatus().readEnabled()
        } catch (_: Exception) {
        }
        try {
            vonrEnabled = vm.vonrStatus().readEnabled()
        } catch (_: Exception) {
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            "信号",
            style = MaterialTheme.typography.headlineMedium,
            color = AppTheme.textPrimary
        )
        Spacer(Modifier.height(12.dp))

        // ── 网络状态（第一个卡片）──
        StaggeredFadeIn(0) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("网络状态", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard {
                    SignalMetricGrid(
                        items = listOf(
                            MetricItem(
                                "网络制式",
                                networkTypeLabel(live["network_type"]),
                                // 基站塔图标：比通用的信号柱更能表达「网络指示」
                                icon = Icons.Filled.CellTower
                            ),
                            MetricItem(
                                "运营商",
                                carrierLabel(live["network_provider"], live["network_provider_fullname"]),
                                // 真正的运营商标识（各家品牌色 + 图形）；未收录时退灰色兜底图
                                iconSlot = {
                                    CarrierLogo(
                                        live["network_provider"],
                                        live["network_provider_fullname"]
                                    )
                                }
                            ),
                            MetricItem(
                                "信号强度",
                                rsrp?.let { "$it dBm" } ?: "",
                                signalColor(rsrp),
                                // 仪表盘图标：表示「当前强度水平」。
                                // 原来用 SignalCellular4Bar，与信号评估、以及底部「信号」Tab 的
                                // 信号柱图标撞脸，换成轮廓完全不同的仪表盘。
                                icon = Icons.Filled.Speed
                            ),
                            MetricItem(
                                // 「格数」对用户没有意义，改成等级 + 配色：优秀/良好/一般/糟糕/不可用
                                // （等级与配色由 model/SignalGrade.kt + gradeColor 决定）
                                "信号评估",
                                grade.label,
                                gradeColor(grade),
                                // 图标固定用「徽章」，**不随等级变色**，只跟随主题的图标色；
                                // 强弱由右侧文字与文字颜色表达，避免同一格子里两条颜色通道打架。
                                icon = Icons.Filled.Verified
                            ),
                            MetricItem(
                                "拨号状态",
                                live["ppp_status"] ?: "",
                                icon = Icons.Filled.SettingsEthernet
                            ),
                            MetricItem(
                                "SIM 卡槽",
                                live["sim_slot"] ?: "",
                                icon = Icons.Filled.SimCard
                            )
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 蜂窝网络模式优先级（第二个卡片）──
        // 读 goform `net_select`；写优先走 ubus `nwinfo_set_netselect`（写完可回读校验），
        // 不可用时回退 goform `SET_BEARER_PREFERENCE`；取值见 model/NetworkMode.kt
        StaggeredFadeIn(1) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("蜂窝网络模式优先级", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard {
                    KeyValueRow(
                        "当前模式",
                        currentModeLabel,
                        valueColor = AppTheme.accent
                    )
                    ThinDivider()
                    Spacer(Modifier.height(12.dp))

                    NetworkMode.entries.chunked(2).forEachIndexed { i, row ->
                        if (i > 0) Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { mode ->
                                ModeChip(
                                    text = mode.label,
                                    selected = mode == currentMode,
                                    enabled = !modeBusy,
                                    onClick = { pendingMode = mode },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // 单数行（本例为偶数项，暂不会走到）补空位，保持列宽一致
                            if (row.size < 2) Spacer(Modifier.weight(1f))
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "切换后设备会重新搜网，期间可能短暂断网。",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textMuted
                    )

                    // 切换结果提示，4 秒后自动消失
                    val msg = modeMsg
                    if (msg != null) {
                        LaunchedEffect(msg) {
                            delay(4_000)
                            modeMsg = null
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTheme.textSecondary
                        )
                    }
                }
            }

            // 切换网络模式属于"会短暂断网"的操作，先确认再执行
            val target = pendingMode
            if (target != null) {
                AlertDialog(
                    onDismissRequest = { if (!modeBusy) pendingMode = null },
                    title = { Text("切换网络模式") },
                    text = {
                        Text(
                            "将网络模式从「$currentModeLabel」切换为「${target.label}」。\n\n" +
                                "切换后设备会重新搜网，期间可能短暂断网。"
                        )
                    },
                    confirmButton = {
                        TextButton(
                            enabled = !modeBusy,
                            onClick = {
                                scope.launch {
                                    modeBusy = true
                                    val result = vm.setBearerPreference(target.value)
                                    modeBusy = false
                                    pendingMode = null
                                    modeMsg = if (result == "success") {
                                        "已切换到「${target.label}」"
                                    } else {
                                        result
                                    }
                                    // 模式生效需要一点时间，稍后强制刷新一次再回显
                                    delay(1_500)
                                    vm.refreshAllNow()
                                }
                            }
                        ) { Text(if (modeBusy) "切换中…" else "切换") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingMode = null }) { Text("取消") }
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        StaggeredFadeIn(2) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("5G 信号", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard {
                    SignalMetricGrid(
                        items = listOf(
                            MetricItem(
                                "RSRP",
                                live["Z5g_rsrp"]?.let { "$it dBm" } ?: "",
                                signalColor(live["Z5g_rsrp"])
                            ),
                            MetricItem("SINR", live["Nr_snr"]?.let { "$it dB" } ?: ""),
                            MetricItem("RSRQ", live["nr_rsrq"]?.let { "$it dB" } ?: ""),
                            MetricItem("频段", live["Nr_bands"]?.let { "N$it" } ?: ""),
                            MetricItem("频点", live["Nr_fcn"] ?: ""),
                            MetricItem("PCI", live["Nr_pci"] ?: ""),
                            MetricItem("RSSI", live["nr_rssi"] ?: ""),
                            MetricItem("带宽", live["Nr_bands_widths"] ?: ""),
                            MetricItem("小区 ID", live["Nr_cell_id"] ?: "")
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        StaggeredFadeIn(3) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("4G 信号", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard {
                    SignalMetricGrid(
                        items = listOf(
                            MetricItem(
                                "RSRP",
                                live["lte_rsrp"]?.let { "$it dBm" } ?: "",
                                signalColor(live["lte_rsrp"])
                            ),
                            MetricItem("SINR", live["Lte_snr"]?.let { "$it dB" } ?: ""),
                            MetricItem("RSRQ", live["lte_rsrq"]?.let { "$it dB" } ?: ""),
                            MetricItem("频段", live["Lte_bands"]?.let { "B$it" } ?: ""),
                            MetricItem("频点", live["Lte_fcn"] ?: ""),
                            MetricItem("PCI", live["Lte_pci"] ?: ""),
                            MetricItem("RSSI", live["lte_rssi"] ?: ""),
                            MetricItem("带宽", live["Lte_bands_widths"] ?: ""),
                            MetricItem("小区 ID", live["Lte_cell_id"] ?: "")
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        StaggeredFadeIn(4) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("语音与频段", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard {
                    SwitchRow("VoLTE", volteEnabled) { enabled ->
                        volteEnabled = enabled
                        scope.launch { vm.setVolte(enabled) }
                    }
                    ThinDivider()
                    SwitchRow("VoNR", vonrEnabled) { enabled ->
                        vonrEnabled = enabled
                        scope.launch { vm.setVonr(enabled) }
                    }
                    ThinDivider()
                    KeyValueRow("4G 频段锁", live["lte_band_lock"]?.ifBlank { "未锁定" } ?: "未锁定")
                    KeyValueRow("5G 频段锁", live["nr_band_lock"]?.ifBlank { "未锁定" } ?: "未锁定")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 模块化网格里的一个指标。
 *
 * [icon] 与 [iconSlot] 二选一：普通矢量图标给 [icon]；需要在图标位里塞自绘图形或彩色资源
 * （信号柱、运营商标识）就用 [iconSlot]。
 */
private data class MetricItem(
    val label: String,
    val value: String,
    val valueColor: Color? = null,
    val icon: ImageVector? = null,
    val iconSlot: (@Composable () -> Unit)? = null
)

/**
 * 把指标按 [columns] 列铺成模块化网格。
 *
 * 原先这些字段用 [KeyValueRow] 竖排，一行只放一个字段、行距又大，
 * 平板上大半个屏幕都是留白，同类参数也没法横向对比。
 * 改成网格后同屏字段多一倍，观感也更接近参考项目"卡片内分格"的做法。
 * 末行不足时补空位，保证每列上下对齐。
 */
@Composable
private fun SignalMetricGrid(items: List<MetricItem>, columns: Int = 3) {
    val rows = items.chunked(columns)
    Column {
        rows.forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { item ->
                    MetricCell(
                        label = item.label,
                        value = item.value,
                        valueColor = item.valueColor ?: AppTheme.textPrimary,
                        icon = item.icon,
                        iconSlot = item.iconSlot,
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 网络模式选项按钮：选中态用强调色填充 + 白字 */
@Composable
private fun ModeChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) AppTheme.accent else AppTheme.textPrimary.copy(alpha = 0.06f)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Color.White else AppTheme.textPrimary,
            maxLines = 1
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean?, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked == true,
            onCheckedChange = onChange,
            enabled = checked != null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppTheme.accent,
                checkedTrackColor = AppTheme.accent.copy(alpha = 0.4f)
            )
        )
    }
}

/** 依据 RSRP 区间返回状态色 */
@Composable
private fun signalColor(rsrp: String?): Color {
    val v = rsrp?.toDoubleOrNull() ?: return AppTheme.textPrimary
    return when {
        v > -85 -> StatusGood
        v > -105 -> StatusInfo
        v > -115 -> StatusWarn
        else -> StatusBad
    }
}

/**
 * 信号评估等级 → 颜色。
 *
 * 五档各自一色，绿 → 蓝 → 琥珀 → 红 → 灰，从好到坏单调递减，扫一眼就能判断。
 * 「不可用」用次要文字色，表示「没有可用信号」而不是「很差」。
 */
@Composable
private fun gradeColor(g: SignalGrade): Color = when (g) {
    SignalGrade.EXCELLENT -> StatusGood
    SignalGrade.GOOD -> StatusInfo
    SignalGrade.FAIR -> StatusWarn
    SignalGrade.POOR -> StatusBad
    SignalGrade.NONE -> AppTheme.textSecondary
}

private fun JsonObject.readEnabled(): Boolean? {
    val e = get("enabled") ?: return null
    if (e.isJsonNull) return null
    return if (e.isJsonPrimitive && e.asJsonPrimitive.isBoolean) e.asBoolean else e.asString == "1"
}
