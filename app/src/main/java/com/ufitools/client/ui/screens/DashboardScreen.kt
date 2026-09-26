package com.ufitools.client.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.google.gson.JsonObject
import com.ufitools.client.model.ClientDevice
import com.ufitools.client.model.ClientIcon
import com.ufitools.client.model.PowerMode
import com.ufitools.client.model.bytesToHuman
import com.ufitools.client.model.carrierLabel
import com.ufitools.client.model.firstValidRsrp
import com.ufitools.client.model.formatTemp
import com.ufitools.client.model.networkTypeLabel
import com.ufitools.client.model.signalBarsOf
import com.ufitools.client.model.uptimeHuman
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.CarrierLogo
import com.ufitools.client.ui.components.KeyValueRow
import com.ufitools.client.ui.components.LabeledField
import com.ufitools.client.ui.components.MetricCell
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.SignalBars
import com.ufitools.client.ui.components.SmoothUpdateText
import com.ufitools.client.ui.components.StaggeredFadeIn
import com.ufitools.client.ui.components.ThinDivider
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.ui.theme.FlowValueTextStyle
import com.ufitools.client.ui.theme.StatusGood
import com.ufitools.client.ui.theme.StatusInfo
import com.ufitools.client.viewmodel.ConnectionStatus
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun JsonObject.s(key: String): String =
    get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

private fun JsonObject.num(key: String): Double =
    get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asDouble }.getOrDefault(0.0) } ?: 0.0

/**
 * 充电电流阈值（µA）——**仅作最后兜底。**
 *
 * 实测本固件上这套判据是无效的：`battery_charging` 恒为 `"0"`。
 * 且 `current_now` 的符号跟充放电无关（插着 PD 充电器实测 `-4028µA`，拔掉也是负值）。
 * 真正可靠的依据是内核 `battery/status`，见 [com.ufitools.client.model.PowerStatus]。
 * 只有当内核节点读不到时（未连接 / 固件无该路径）才退回到这里。
 * 阈值沿用参考项目 U60Pro-Widget 的 `current > 50`。
 */
private const val CHARGE_CURRENT_UA = 50_000.0

@Composable
fun DashboardScreen(vm: MainViewModel, nav: NavHostController) {
    val base = vm.baseInfo
    val live = vm.live

    val model = base?.s("model")?.ifBlank { live["model"] ?: "" }
        ?.ifBlank { (vm.status as? ConnectionStatus.Connected)?.model ?: "" }
        ?: ""
    val netType = networkTypeLabel(live["network_type"])
    // 代号（CUCC）与英文全称（China Unicom）一起给，统一归一成中文名
    val carrier = carrierLabel(live["network_provider"], live["network_provider_fullname"])
    // 只认有效的 RSRP（设备无 5G 时返回 0，要当"没数据"而不是真值）
    val rsrpRaw = firstValidRsrp(live["Z5g_rsrp"], live["lte_rsrp"])
    // 格数以 RSRP 为准；设备的 network_signalbar 在本固件恒为 5，只能兜底（见 signalBarsOf）
    val signalBars = signalBarsOf(rsrpRaw, live["network_signalbar"])
    // 电量：baseDeviceInfo 的 `battery`（实测 88）最可靠——该固件 goform 侧根本不返回 battery。
    // 其余名字保留作兼容，最后回退到 run_shell 读到的内核 capacity
    val battery = listOf(
        base?.s("battery"), live["battery"], live["battery_value"], live["battery_vol_percent"],
        vm.power?.capacity?.toString()
    ).firstOrNull { !it.isNullOrBlank() } ?: ""
    // ── 电源三态：充电 / 直供 / 未充电 ──
    //
    // goform 完全靠不住：`battery_charging` 实测**恒为 "0"**（插着 PD 充电器时同样为 0），
    // `current_now` 的符号与充放电**无关**（插着充电器实测也是负值）。
    // 所以下面这条旧判定在任何情况下都会得出"未充电"——正是之前的显示 bug。
    //
    // 现在以 ViewModel 经 run_shell 读到的内核 `battery/status` 为准（见 model/PowerStatus.kt），
    // 只有读不到（未连接 / 固件无该节点）才回退到旧判定。
    val chargingByCurrent = live["battery_charging"] == "1" || live["battery_charging"] == "true" ||
        (base?.num("current_now") ?: 0.0) > CHARGE_CURRENT_UA
    val powerMode = vm.power?.mode
        ?: if (chargingByCurrent) PowerMode.CHARGING else PowerMode.UNPLUGGED
    // 运行内存：baseDeviceInfo.memInfo 给的是 kB 明细，可算出「已用 / 总量」
    val memInfo = base?.get("memInfo")?.takeIf { it.isJsonObject }?.asJsonObject
    val memUsedKb = memInfo?.num("mem_used_kb")?.takeIf { it > 0 }
    val memTotalKb = memInfo?.num("mem_total_kb")?.takeIf { it > 0 }
    // 设备（路由）自身的 LAN 地址：goform 的 lan_ipaddr；取不到就退回用户填的主机名
    // 注意 baseDeviceInfo.client_ip 是**本机**（跑 APP 的这台）地址，不能用在这里。
    val deviceIp = live["lan_ipaddr"]?.takeIf { it.isNotBlank() } ?: vm.config.host
    val clientIp = base?.s("client_ip")?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // ── 页头：型号大标题 + 副标题（对齐参考项目的 48sp 主标题） ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    model.ifBlank { "未知设备" },
                    style = MaterialTheme.typography.displaySmall,
                    color = AppTheme.dataHighlight,
                    maxLines = 1
                )
                Text(
                    buildString {
                        append(netType)
                        if (carrier.isNotBlank() && carrier != "-") append("  $carrier")
                        val ver = live["cr_version"] ?: ""
                        if (ver.isNotBlank()) append("  ·  $ver")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.textMuted
                )
            }
            IconButton(onClick = { vm.refreshAllNow() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = AppTheme.iconTint)
            }
        }

        // ── 实时状态卡片 ──
        StaggeredFadeIn(0) { m ->
            AppCard(m) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("实时状态")
                    Spacer(Modifier.weight(1f))
                    RefreshHint(vm)
                }
                Spacer(Modifier.height(10.dp))

                // ── 第一行：运营商 + 信号类型（对齐参考项目的网络信息位） ──
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MetricCell(
                        label = "运营商",
                        value = carrier,
                        // 用各家真实品牌标识（官方素材 PNG），刻意不跟随主题着色
                        iconSlot = {
                            CarrierLogo(
                                provider = live["network_provider"],
                                fullname = live["network_provider_fullname"]
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "信号类型",
                        value = netType,
                        sub = if (signalBars > 0) "$signalBars/5 格" else null,
                        // 图标随格数变化：5 根柱子按当前信号格数点亮（见 components/SignalBars）。
                        // 颜色取当前主题的强调色，跟着配色/明暗切换走。
                        iconSlot = { SignalBars(bars = signalBars, color = AppTheme.accent) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))
                ThinDivider()
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MetricCell(
                        label = "信号",
                        value = rsrpRaw?.let { "${it.toInt()} dBm" } ?: "-",
                        icon = Icons.Filled.Wifi,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "温度",
                        value = formatTemp(numOf(base, live, "cpu_temp")),
                        icon = Icons.Filled.Thermostat,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "CPU",
                        value = numOf(base, live, "cpu_usage")?.let { "${it.toInt()}%" } ?: "-",
                        icon = Icons.Filled.DeveloperBoard,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "内存",
                        value = numOf(base, live, "mem_usage")?.let { "${it.toInt()}%" } ?: "-",
                        // Memory = 内存条图标；原来是 Storage（硬盘叠层）是存储空间的图标，容易误读
                        icon = Icons.Filled.Memory,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))
                ThinDivider()
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MetricCell(
                        label = "实时下行",
                        // 实测固件字段是 real_rx_speed（B/s）；realtime_rx_thrpt 在该固件上不存在，保留作兼容回退
                        value = rateOf(base, live, "real_rx_speed", "realtime_rx_thrpt"),
                        icon = Icons.Filled.CloudDownload,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "实时上行",
                        value = rateOf(base, live, "real_tx_speed", "realtime_tx_thrpt"),
                        icon = Icons.Filled.CloudUpload,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))
                ThinDivider()
                Spacer(Modifier.height(12.dp))

                // 流量区：大数字 + 数据高亮色（对齐参考项目的 24sp 流量展示）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { nav.navigate("usage") },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FlowBlock(
                        label = "今日已用",
                        value = bytesToHuman(base?.num("daily_data")?.toLong()),
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(44.dp)
                            .background(AppTheme.textPrimary.copy(alpha = 0.08f))
                    )
                    FlowBlock(
                        label = "本月累计",
                        value = bytesToHuman(base?.num("monthly_data")?.toLong()),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "点按查看流量历史 ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.accent
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 硬件参数卡片 ──
        StaggeredFadeIn(1) { m ->
            AppCard(m) {
                SectionTitle("硬件参数")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MetricCell(
                        label = "电池",
                        value = "${battery.ifBlank { "-" }}%",
                        // 三态：充电中（绿）/ 直供（蓝，插着电源但电池不充）/ 未充电（次要色）
                        sub = powerMode.label,
                        subColor = when (powerMode) {
                            PowerMode.CHARGING -> StatusGood
                            PowerMode.DIRECT -> StatusInfo
                            else -> AppTheme.textSecondary
                        },
                        icon = when (powerMode) {
                            PowerMode.CHARGING -> Icons.Filled.BatteryChargingFull
                            PowerMode.DIRECT -> Icons.Filled.Power
                            else -> Icons.Filled.BatteryFull
                        },
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "存储空间",
                        value = bytesToHuman(base?.num("internal_used_storage")?.toLong()),
                        sub = "共 ${bytesToHuman(base?.num("internal_total_storage")?.toLong())}",
                        icon = Icons.Filled.Storage,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "运行内存",
                        // memInfo 缺失时退回百分比（goform 的 mem_usage）
                        value = memUsedKb?.let { bytesToHuman((it * 1024).toLong()) }
                            ?: numOf(base, live, "mem_usage")?.let { "${it.toInt()}%" } ?: "-",
                        sub = memTotalKb?.let { "共 ${bytesToHuman((it * 1024).toLong())}" },
                        icon = Icons.Filled.Memory,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MetricCell(
                        label = "设备地址",
                        // 这里要的是 UFI（路由）自己的 IP；本机地址放在 sub 里，避免两者混淆
                        value = deviceIp,
                        sub = clientIp?.let { "本机 $it" },
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "开机时长",
                        // goform 的 uptime 单位是秒，uptimeHuman 期望毫秒
                        value = uptimeHuman(live["uptime"]?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 已连接设备列表卡片 ──
        // 数据来源：goform station_list（名称/IP/MAC/频段）+ run_shell 的 iw station dump（流量/连接时长）
        StaggeredFadeIn(2) { m ->
            var pickerFor by remember { mutableStateOf<ClientDevice?>(null) }
            // 点某台设备的名称 → 终端详情弹窗（含本地别名编辑）
            var detailFor by remember { mutableStateOf<ClientDevice?>(null) }
            // 踢出：待确认的终端 / 是否正在执行 / 结果提示
            var kickTarget by remember { mutableStateOf<ClientDevice?>(null) }
            var kicking by remember { mutableStateOf(false) }
            var kickMsg by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            AppCard(m) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("设备列表")
                    Spacer(Modifier.weight(1f))
                    if (vm.clients.isNotEmpty()) {
                        Text(
                            "${vm.clients.size} 台",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTheme.textSecondary
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                if (vm.clients.isEmpty()) {
                    Text(
                        "暂无已连接设备",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.textMuted,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    vm.clients.forEachIndexed { i, c ->
                        if (i > 0) ThinDivider()
                        ClientRow(
                            client = c,
                            icon = vm.clientIconOf(c),
                            name = vm.clientNameOf(c),
                            onNameClick = { detailFor = c },
                            onIconClick = { pickerFor = c },
                            onKickClick = { kickTarget = c }
                        )
                    }
                }

                // 踢出结果提示：4 秒后自动消失
                val msg = kickMsg
                if (msg != null) {
                    LaunchedEffect(msg) {
                        delay(4_000)
                        kickMsg = null
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textSecondary
                    )
                }
            }

            val target = pickerFor
            if (target != null) {
                IconPickerDialog(
                    current = vm.clientIconOf(target),
                    title = target.displayName,
                    onPick = { vm.setClientIcon(target.mac, it) },
                    onReset = {
                        vm.setClientIcon(target.mac, null)
                        pickerFor = null
                    },
                    onDismiss = { pickerFor = null }
                )
            }

            // 踢出确认：会真的断掉对方的无线连接，先让用户确认。
            // 实现是 root shell 的 deauth（设备没有官方"断开客户端"接口），见 model/ClientDevice.kt
            val kick = kickTarget
            if (kick != null) {
                AlertDialog(
                    onDismissRequest = { if (!kicking) kickTarget = null },
                    title = { Text("踢出设备") },
                    text = {
                        Text(
                            "将断开「${vm.clientNameOf(kick)}」（${kick.mac}）的无线连接。\n\n" +
                                "注意：对方如果仍保存着 WiFi 密码，通常会立刻自动重连。"
                        )
                    },
                    confirmButton = {
                        TextButton(
                            enabled = !kicking,
                            onClick = {
                                scope.launch {
                                    kicking = true
                                    val err = vm.kickClient(kick)
                                    kicking = false
                                    kickTarget = null
                                    if (err != null) {
                                        kickMsg = err
                                        return@launch
                                    }
                                    // 等设备端处理完再刷新，用"列表里还有没有它"给出真实结论。
                                    // deauth 之后终端通常会自己重连，那样列表里还会出现它
                                    delay(1_200)
                                    vm.refreshAll(forceClients = true)
                                    val still = vm.clients.any {
                                        it.mac.equals(kick.mac, ignoreCase = true)
                                    }
                                    kickMsg = if (still) {
                                        "「${vm.clientNameOf(kick)}」已断开，但可能又自动重连了"
                                    } else {
                                        "已踢出「${vm.clientNameOf(kick)}」"
                                    }
                                }
                            }
                        ) { Text(if (kicking) "处理中…" else "踢出") }
                    },
                    dismissButton = {
                        TextButton(onClick = { kickTarget = null }) { Text("取消") }
                    }
                )
            }

            // 终端详情：点列表里的设备名称弹出
            val detail = detailFor
            if (detail != null) {
                ClientDetailDialog(
                    client = detail,
                    alias = vm.clientAliasOf(detail.mac),
                    onSaveName = { vm.setClientName(detail.mac, it) },
                    onDismiss = { detailFor = null }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 详情卡片 ──
        StaggeredFadeIn(3) { m ->
            AppCard(m) {
                SectionTitle("更多信息")
                Spacer(Modifier.height(6.dp))
                KeyValueRow("WiFi 连接数", live["wifi_access_sta_num"] ?: "")
                KeyValueRow("电流", base?.num("current_now")?.let { "${it / 1000.0} mA" } ?: "-")
                KeyValueRow("电压", base?.num("voltage_now")?.let { "${it / 1000.0} mV" } ?: "-")
                KeyValueRow("IPv6 WAN", live["ipv6_wan_ipaddr"] ?: "")
                KeyValueRow("外部存储可用", bytesToHuman(base?.num("external_available_storage")?.toLong()))
                if (base?.get("is_reached_data_flow_limit")?.asBoolean == true) {
                    KeyValueRow("流量上限", "已触发", valueColor = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 终端详情弹窗：在「设备列表」里点某台设备的**名称**弹出。
 *
 * 上半是只读信息（IP / MAC / 频段 / 信号 / 流量 / 连接时长 / 接口 / 厂商），
 * 下半可改**名称**——设备固件没有重命名已连接终端的接口，所以这是**存在 App 本地的别名**
 * （按 MAC 关联，见 `ClientNameStore`），清空即恢复设备上报的原名。
 *
 * @param alias 当前本地别名（空串表示还没设过）
 */
@Composable
private fun ClientDetailDialog(
    client: ClientDevice,
    alias: String,
    onSaveName: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(client.mac) { mutableStateOf(alias) }
    val hasAlias = alias.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = AppTheme.cardBg,
        title = {
            Text(
                vmDisplayName(alias, client.hostname),
                color = AppTheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                KeyValueRow("IP 地址", client.ip.ifBlank { "-" })
                KeyValueRow("MAC 地址", client.mac.ifBlank { "-" })
                if (client.bandLabel.isNotBlank()) KeyValueRow("频段", client.bandLabel)
                if (client.iface != null) KeyValueRow("无线接口", client.iface)
                KeyValueRow("信号强度", client.signalDbm?.let { "$it dBm" } ?: "有线 / 无数据")
                KeyValueRow("上行流量", bytesToHuman(client.rxBytes))
                KeyValueRow("下行流量", bytesToHuman(client.txBytes))
                KeyValueRow("合计流量", client.usedText)
                KeyValueRow("连接时长", client.connectedText)
                if (client.vendor.isNotBlank()) KeyValueRow("厂商", client.vendor)
                KeyValueRow("设备上报名", client.hostname.ifBlank { "（未上报）" })

                Spacer(Modifier.height(12.dp))
                ThinDivider()
                Spacer(Modifier.height(12.dp))

                SectionTitle("设备名称")
                Spacer(Modifier.height(6.dp))
                Text(
                    "设备本身不支持给已连接终端改名，这里的名称保存在本机、按 MAC 关联。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.textSecondary
                )
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    "名称",
                    name,
                    { name = it },
                    placeholder = client.hostname.ifBlank { "未命名设备" }
                )
            }
        },
        confirmButton = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (hasAlias) {
                    TextButton(onClick = { onSaveName("") }) {
                        Text("恢复原名", color = AppTheme.textSecondary)
                    }
                }
                TextButton(
                    onClick = { onSaveName(name.trim()) },
                    enabled = name.trim() != alias
                ) {
                    Text("保存", color = AppTheme.accent)
                }
            }
        }
    )
}

/** 弹窗标题用：别名优先，否则设备上报名，都空则占位 */
private fun vmDisplayName(alias: String, hostname: String): String =
    alias.ifBlank { hostname }.ifBlank { "未命名设备" }

@Composable
private fun FlowBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = AppTheme.textSecondary)
        Spacer(Modifier.height(4.dp))
        // 流量大数值同样走平滑更新过渡
        SmoothUpdateText(
            text = value,
            style = FlowValueTextStyle,
            color = AppTheme.dataHighlight,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 实时速率取值：优先 baseDeviceInfo（数值型），回退 goform 轮询字段（字符串型）。
 * 用于兼容不同固件把实时速率放在不同接口的情况。
 */
private fun rateOf(base: JsonObject?, live: Map<String, String>, vararg keys: String): String {
    for (k in keys) {
        val v = base?.get(k)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() }
        if (v != null && v >= 0) return speedText(v)
    }
    for (k in keys) {
        val v = live[k]?.trim()?.toDoubleOrNull()
        if (v != null && v >= 0) return speedText(v)
    }
    return "-"
}

/** 速率文本：0 也明确显示，避免与"字段缺失"混为一谈 */
private fun speedText(bytesPerSecond: Double): String =
    if (bytesPerSecond < 1.0) "0 B/s" else bytesToHuman(bytesPerSecond.toLong()) + "/s"

/** 取数值：优先 baseDeviceInfo，回退 goform 轮询字段 */
private fun numOf(base: JsonObject?, live: Map<String, String>, key: String): Double? =
    base?.get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() }
        ?: live[key]?.trim()?.toDoubleOrNull()

/** 刷新状态提示：让"是否真的在实时刷新"在界面上可见（失败时红字） */
@Composable
private fun RefreshHint(vm: MainViewModel) {
    val err = vm.lastError
    val ts = vm.lastUpdated
    val text = err
        ?: ts?.let {
            "更新时间 " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(it))
        }
        ?: return
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (err != null) MaterialTheme.colorScheme.error else AppTheme.textMuted,
        maxLines = 1
    )
}

/**
 * 设备列表中的一行。
 *
 * 左侧圆形图标**可点击**（由调用方弹出图标选择框），中间是名称 + IP/MAC，
 * 右侧是已用流量与已连接时长，最右侧是「踢出」按钮（点按由调用方弹确认框）。
 */
@Composable
private fun ClientRow(
    client: ClientDevice,
    icon: ClientIcon,
    name: String,
    onNameClick: () -> Unit,
    onIconClick: () -> Unit,
    onKickClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(AppTheme.accent.copy(alpha = 0.14f))
                .clickable(onClick = onIconClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon.icon,
                contentDescription = "更换图标（当前：${icon.label}）",
                tint = AppTheme.accent,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onNameClick)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (client.bandLabel.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    BandBadge(client.bandLabel)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                listOf(client.ip, client.mac).filter { it.isNotBlank() }.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(12.dp))

        // 右列：已用流量（上行 + 下行累计）与已连接时长。
        // 固件不支持 iw station dump 时这两项取不到，会显示 "-"。
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "已用流量",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textSecondary
            )
            SmoothUpdateText(
                text = client.usedText,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.dataHighlight,
                maxLines = 1
            )
            Text(
                "已连接 ${client.connectedText}",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textSecondary,
                maxLines = 1
            )
        }

        // 踢出按钮：向该终端发 deauth 断开连接。
        // 设备没有官方"断开指定客户端"的接口，只能走 root shell：
        // `iw dev <iface> station del <MAC>`，见 model/ClientDevice.kt:kickClientCmd
        IconButton(
            onClick = onKickClick,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                Icons.Filled.WifiOff,
                contentDescription = "踢出 ${client.displayName}",
                tint = AppTheme.textMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 频段小标记（5G / 2.4G） */
@Composable
private fun BandBadge(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(AppTheme.accent.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.accent,
            maxLines = 1
        )
    }
}

/**
 * 图标选择弹窗。点选即生效（无需确认）；「恢复自动」清除该 MAC 的自定义记录，
 * 回到按主机名 / 厂商 / OUI 的自动识别。
 */
@Composable
private fun IconPickerDialog(
    current: ClientIcon,
    title: String,
    onPick: (ClientIcon) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("选择设备图标 · $title", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ClientIcon.entries.chunked(4).forEach { rowItems ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowItems.forEach { ic ->
                            IconChoiceCell(
                                icon = ic,
                                selected = ic == current,
                                onClick = { onPick(ic) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // 末行补齐空位，保证每格宽度与前面的行一致
                        repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text("恢复自动") }
        }
    )
}

@Composable
private fun IconChoiceCell(
    icon: ClientIcon,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AppTheme.accent.copy(alpha = 0.20f) else AppTheme.textPrimary.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon.icon,
            contentDescription = icon.label,
            tint = if (selected) AppTheme.accent else AppTheme.iconTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            icon.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) AppTheme.accent else AppTheme.textSecondary,
            maxLines = 1
        )
    }
}
