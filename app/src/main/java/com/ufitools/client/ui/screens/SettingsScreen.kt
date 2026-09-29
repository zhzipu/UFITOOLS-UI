package com.ufitools.client.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufitools.client.BuildConfig
import com.ufitools.client.R
import com.ufitools.client.data.RefreshInterval
import com.ufitools.client.model.IpValidator
import com.ufitools.client.model.LanSetting
import com.ufitools.client.model.NetworkMode
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.LabeledField
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.StaggeredFadeIn
import com.ufitools.client.ui.components.ThinDivider
import com.ufitools.client.ui.components.openUrl
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.ui.theme.StatusBad
import com.ufitools.client.ui.theme.ThemePalettes
import com.ufitools.client.viewmodel.CLASH_NOT_RUNNING_HINT
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 开关拨动后等待设备回读的宽限时间；超时仍未生效即回滚到设备真实值 */
private const val SWITCH_OPTIMISTIC_TIMEOUT_MS = 4_000L

/** 开源仓库地址；"关于"卡片展示并作为跳转目标 */
private const val PROJECT_URL = "https://github.com/zhzipu/UFITOOLS-UI"

/** 应用版本名，取自 BuildConfig（build.gradle.kts 的 versionName） */
private val APP_VERSION: String = BuildConfig.VERSION_NAME

/**
 * SIM 卡槽可选值。
 *
 * 设备侧取值语义：`0` 自动、`1` 卡槽1、`2` 卡槽2、`11` 双卡同开。
 * 之前是裸文本框，手输错了设备会静默忽略（goform 投递即成功），改点选可杜绝。
 */
private val SIM_SLOT_OPTIONS = listOf(
    "0" to "自动",
    "1" to "卡槽 1",
    "2" to "卡槽 2",
    "11" to "双卡同开",
)

/**
 * 休眠档位（分钟 → 显示名）。
 *
 * `-1` 是设备的"从不休眠"哨兵值，不是负数分钟；取值抄自设备 Web 端
 * `idx.html` 的 `SLEEP_TIME` 下拉项，设备对不在表里的值会静默忽略。
 */
private val SLEEP_OPTIONS = listOf(
    "-1" to "从不",
    "5" to "5 分钟",
    "10" to "10 分钟",
    "20" to "20 分钟",
    "30" to "30 分钟",
    "60" to "1 小时",
    "120" to "2 小时",
)

/**
 * NFC 配对 WiFi 通道。
 *
 * `1`=2.4G 主 / `2`=5G 主 / `3`=2.4G 访客 / `4`=5G 访客（取自设备 Web 端 idx.html）。
 * 设备读回 `web_wifi_nfc_flag` 缺省为 `"2"`（5G 主），所以默认值不能写成空串。
 */
private val NFC_AP_OPTIONS = listOf(
    "1" to "2.4G 主网络",
    "2" to "5G 主网络",
    "3" to "2.4G 访客网络",
    "4" to "5G 访客网络",
)

private val NFC_AP_LABELS = NFC_AP_OPTIONS.toMap()

/** 休眠分钟数 → 可读摘要；[minutes] < 0 一律显示"从不" */
private fun sleepSummary(minutes: Int): String {
    if (minutes < 0) return "从不休眠"
    val hit = SLEEP_OPTIONS.firstOrNull { it.first == minutes.toString() }
    return hit?.second ?: "$minutes 分钟"
}

@Composable
fun SettingsScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live = vm.live

    var showNickname by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var showDataLimit by remember { mutableStateOf(false) }
    var showBandLock by remember { mutableStateOf(false) }
    var showCellLock by remember { mutableStateOf(false) }
    var showNetworkMode by remember { mutableStateOf(false) }
    var showSimSlot by remember { mutableStateOf(false) }
    var showConfirmReboot by remember { mutableStateOf(false) }
    var showConfirmShutdown by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showLan by remember { mutableStateOf(false) }
    var showNfcAp by remember { mutableStateOf(false) }

    // 系统设置组里的几项走独立通道、改动频率极低，只在进页面时拉一次，
    // 不进主轮询（见 vm.refreshDeviceSettings 的说明）。
    LaunchedEffect(Unit) { vm.refreshDeviceSettings() }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun run(label: String, action: suspend () -> String) {
        scope.launch {
            val r = action()
            toast(if (r == "success") "$label 成功" else r)
            if (r == "success") {
                // 设备侧生效有延迟（官方 Web 端同样是 1 秒后才回读），
                // 稍等一下再同步开关状态，否则界面会停在旧值上
                delay(1_000)
                vm.refreshAllNow()
            }
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
            "设置",
            style = MaterialTheme.typography.headlineMedium,
            color = AppTheme.textPrimary
        )
        Spacer(Modifier.height(12.dp))

        StaggeredFadeIn(0) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("连接", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                    InfoRow("设备地址", vm.config.baseUrl)
                    ThinDivider()
                    InfoRow("控制台口令", vm.config.token)
                    ThinDivider()
                    // 自动刷新间隔（原 VM 已支持该能力，此前没有入口，仪表盘数据只能靠手动刷新）
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "自动刷新",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTheme.textSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            vm.lastUpdated?.let { ts ->
                                Text(
                                    "更新于 " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                        .format(java.util.Date(ts)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (vm.lastError != null) MaterialTheme.colorScheme.error else AppTheme.textMuted
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RefreshInterval.entries.forEach { itv ->
                                val selected = itv == vm.refreshInterval
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selected) AppTheme.accent.copy(alpha = 0.18f)
                                            else AppTheme.textPrimary.copy(alpha = 0.04f)
                                        )
                                        .clickable { vm.applyRefreshInterval(itv) }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        itv.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) AppTheme.accent else AppTheme.textSecondary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    ThinDivider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        OutlinedButton(onClick = { vm.disconnect() }) {
                            Text("断开连接", color = AppTheme.textPrimary)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        StaggeredFadeIn(1) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("外观", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard(contentPadding = PaddingValues(0.dp)) {
                    ListRow(
                        title = "主题配色",
                        subtitle = "当前：${ThemePalettes.byId(vm.paletteId).name} · ${vm.themeMode.label}",
                        icon = Icons.Filled.Palette,
                        onClick = { nav.navigate("appearance") }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        StaggeredFadeIn(2) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("工具设置", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard(contentPadding = PaddingValues(0.dp)) {
                    ListRow(
                        title = "设备别名",
                        subtitle = live["nickname"] ?: "",
                        icon = Icons.Filled.Edit,
                        onClick = { showNickname = true }
                    )
                    ThinDivider()
                    ListRow(
                        title = "修改口令",
                        subtitle = "控制台登录口令",
                        icon = Icons.Filled.Lock,
                        onClick = { showToken = true }
                    )
                    ThinDivider()
                    ListRow(
                        title = "流量阈值",
                        subtitle = "上限与提醒设置",
                        icon = Icons.Filled.DataUsage,
                        onClick = { showDataLimit = true }
                    )
                    ThinDivider()
                    ListRow(
                        title = "黑名单",
                        subtitle = "禁止指定设备连接 WiFi",
                        icon = Icons.Filled.Block,
                        onClick = { nav.navigate("blacklist") }
                    )
                    ThinDivider()
                    ListRow(
                        title = "短信转发",
                        subtitle = if (vm.smsForward) "已开启 · 邮件/CURL/钉钉" else "未开启",
                        icon = Icons.Filled.Link,
                        onClick = { nav.navigate("sms-forward") }
                    )
                    // 注：原来这里还有一个「短信转发开关」SwitchItem，与上面这行重复
                    // （开关本身在「短信转发」详情页里已有），按反馈移除。
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        StaggeredFadeIn(3) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("系统设置", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard(contentPadding = PaddingValues(0.dp)) {
                    ListRow(
                        title = "网络模式",
                        subtitle = live["net_select"] ?: "未设置",
                        icon = Icons.Filled.CellTower,
                        onClick = { showNetworkMode = true }
                    )
                    ThinDivider()
                    ListRow(
                        title = "频段锁",
                        subtitle = bandLockSummary(live),
                        icon = Icons.Filled.Wifi,
                        onClick = { showBandLock = true }
                    )
                    ThinDivider()
                    ListRow(
                        title = "锁定基站",
                        subtitle = cellLockSummary(live),
                        icon = Icons.Filled.CellTower,
                        onClick = { showCellLock = true }
                    )
                    ThinDivider()
                    // 状态字段名是 `WiFiModuleSwitch`（查询命令为 queryWiFiModuleSwitch）
                    SwitchItem("WiFi 开关", live["WiFiModuleSwitch"] == "1", Icons.Filled.Wifi) { on ->
                        run("WiFi") { vm.setWifi(on) }
                    }
                    ThinDivider()
                    // 频段级开关：走 switchWiFiChip + ChipEnum（chip1=2.4G / chip2=5G），
                    // 状态来自 queryAccessPointInfo，已随主轮询限流刷新
                    SwitchItem("2.4G WiFi", vm.wifiBands.getOrNull(0) == true, Icons.Filled.Wifi) { on ->
                        run("2.4G WiFi") { vm.setWifiBand(0, on) }
                    }
                    ThinDivider()
                    SwitchItem("5G WiFi", vm.wifiBands.getOrNull(1) == true, Icons.Filled.Wifi) { on ->
                        run("5G WiFi") { vm.setWifiBand(1, on) }
                    }
                    ThinDivider()
                    // WiFi 配置（SSID / 密码 / 安全模式 / PMF / 二维码）单独成页 ——
                    // 内容多，直接嵌在设置列表里会把整页撑得很长。
                    // 原「WiFi 二维码」入口已并入该页（每个频段一张二维码）。
                    ListRow(
                        title = "WiFi 设置",
                        subtitle = "SSID / 密码 / 安全模式 / 二维码",
                        icon = Icons.Filled.Wifi,
                        onClick = { nav.navigate("wifi-settings") }
                    )
                    ThinDivider()
                    // 漫游读回以 dial_roam_setting_option 为准（官方 Web 端同此）
                    SwitchItem(
                        "数据漫游",
                        (live["dial_roam_setting_option"] ?: live["roam_setting_option"]) == "on",
                        Icons.Filled.Link
                    ) { on ->
                        run("数据漫游") { vm.setRoaming(on) }
                    }
                    ThinDivider()
                    ListRow(
                        title = "SIM 卡槽",
                        subtitle = live["sim_slot"] ?: "",
                        icon = Icons.Filled.SimCard,
                        onClick = { showSimSlot = true }
                    )

                    // ---- 以下为新增 6 项（读不到的一律不显示，避免暴露"点了没反应"的空壳） ----

                    // 休眠时间：设备不支持时会回空串（vm.sleepMinutes == null），自动隐藏
                    vm.sleepMinutes?.let { cur ->
                        ThinDivider()
                        ListRow(
                            title = "休眠时间",
                            subtitle = sleepSummary(cur),
                            icon = Icons.Filled.Schedule,
                            onClick = { showSleep = true }
                        )
                    }

                    // 内网设置：网关/掩码/DHCP 地址池；⚠️ 保存后设备重启网络，会断连
                    vm.lanSetting?.let { lan ->
                        ThinDivider()
                        ListRow(
                            title = "内网设置",
                            subtitle = "${lan.gateway} / ${lan.netmask}",
                            icon = Icons.Filled.SettingsEthernet,
                            onClick = { showLan = true }
                        )
                    }

                    // 数据开关：写后要轮询约 8 秒确认，故把乐观值超时放宽到 10 秒，
                    // 否则开关会在真正生效前先弹回去
                    vm.cellularOn?.let { on ->
                        ThinDivider()
                        SwitchItem(
                            title = "数据开关",
                            checked = on,
                            icon = Icons.Filled.DataUsage,
                            timeoutMs = 10_000L
                        ) { want ->
                            scope.launch {
                                toast(vm.setCellularData(want) ?: "数据${if (want) "已打开" else "已关闭"}")
                            }
                        }
                    }

                    // NFC：设备按硬件能力决定是否支持（nfcState.supported），不支持则整块不显示
                    if (vm.nfcState.supported) {
                        ThinDivider()
                        SwitchItem("NFC 开关", vm.nfcState.enabled, Icons.Filled.Wifi) { want ->
                            scope.launch { toast(vm.setNfc(want) ?: "NFC ${if (want) "已开启" else "已关闭"}") }
                        }
                        ThinDivider()
                        ListRow(
                            title = "NFC 配对 WiFi",
                            subtitle = NFC_AP_LABELS[vm.nfcState.ap] ?: vm.nfcState.ap,
                            icon = Icons.Filled.Wifi,
                            onClick = { showNfcAp = true }
                        )
                    }

                    // USB 调试：走 /api/adb/mode。关掉后本机 adb 会断开，属预期行为
                    vm.usbDebugOn?.let { on ->
                        ThinDivider()
                        SwitchItem("USB 调试", on, Icons.Filled.Terminal) { want ->
                            scope.launch {
                                toast(
                                    vm.setUsbDebug(want)
                                        ?: if (want) "USB 调试已开启" else "USB 调试已关闭（adb 会断开连接）"
                                )
                            }
                        }
                    }

                    // 设备 UFI-TOOLS 软件更新：走网页版自带的更新功能（点它的 #OTA 入口），
                    // 不再是「检查 App 自己在 GitHub 上的版本」。
                    ThinDivider()
                    ListRow(
                        title = "UFI-TOOLS 更新",
                        subtitle = "打开设备网页版的软件更新",
                        icon = Icons.Filled.UploadFile,
                        onClick = {
                            vm.webAutoAction = "softwareUpdate"
                            nav.navigate("web")
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        StaggeredFadeIn(4) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("数据与自动化", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard(contentPadding = PaddingValues(0.dp)) {
                    ListRow(
                        title = "流量历史",
                        subtitle = "按天查看蜂窝用量",
                        icon = Icons.Filled.DataUsage,
                        onClick = { nav.navigate("usage") }
                    )
                    ThinDivider()
                    ListRow(
                        title = "APN 管理",
                        subtitle = "自动 / 自建接入点",
                        icon = Icons.Filled.SettingsEthernet,
                        onClick = { nav.navigate("apn") }
                    )
                    ThinDivider()
                    ListRow(
                        title = "定时任务",
                        subtitle = "定时重启 / 开关 WiFi",
                        icon = Icons.Filled.Schedule,
                        onClick = { nav.navigate("tasks") }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        StaggeredFadeIn(5) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("扩展能力", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard(contentPadding = PaddingValues(0.dp)) {
                    ListRow(
                        title = "猫猫面板",
                        subtitle = when (vm.clashOnline) {
                            true -> "已连接 · ${vm.clashVersion?.display ?: ""}"
                            false -> CLASH_NOT_RUNNING_HINT
                            // null = 还没探测：进页面时会自动连接，这里给个中性描述
                            else -> "点击进入，自动连接"
                        },
                        iconRes = R.drawable.ic_cat,
                        onClick = { nav.navigate("clash") }
                    )
                    ThinDivider()
                    ListRow(
                        title = "文件管理",
                        subtitle = "上传图片 / 文件到设备",
                        icon = Icons.Filled.UploadFile,
                        onClick = { nav.navigate("uploads") }
                    )
                    ThinDivider()
                    ListRow(
                        title = "插件管理",
                        subtitle = "安装 / 卸载设备插件",
                        icon = Icons.Filled.Extension,
                        onClick = { nav.navigate("plugins") }
                    )
                    ThinDivider()
                    ListRow(
                        title = "终端与调试",
                        subtitle = "网页终端 (ttyd) / ADB 模式",
                        icon = Icons.Filled.Terminal,
                        onClick = { nav.navigate("terminal") }
                    )
                    ThinDivider()
                    ListRow(
                        title = "设备高级设置",
                        subtitle = "资源服务器 / 后台密码 / 自定义头部",
                        icon = Icons.Filled.AdminPanelSettings,
                        onClick = { nav.navigate("device-advanced") }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        StaggeredFadeIn(6) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("设备操作", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard(contentPadding = PaddingValues(0.dp)) {
                    ListRow(
                        title = "重启设备",
                        subtitle = "重启期间将短暂断开连接",
                        icon = Icons.Filled.RestartAlt,
                        onClick = { showConfirmReboot = true }
                    )
                    ThinDivider()
                    ListRow(
                        title = "关机",
                        subtitle = "关机后需手动开机",
                        icon = Icons.Filled.PowerSettingsNew,
                        iconTint = StatusBad,
                        onClick = { showConfirmShutdown = true }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        StaggeredFadeIn(7) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("调试", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard(contentPadding = PaddingValues(0.dp)) {
                    ListRow(
                        title = "AT 命令终端",
                        subtitle = "直接与基带交互",
                        icon = Icons.Filled.Terminal,
                        onClick = { nav.navigate("at") }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        StaggeredFadeIn(8) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("关于", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "UFITOOLS-UI",
                            style = MaterialTheme.typography.titleLarge,
                            color = AppTheme.textPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "版本 $APP_VERSION",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTheme.textSecondary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            PROJECT_URL,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTheme.accent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openUrl(context, PROJECT_URL, "无法打开链接") }
                                .padding(vertical = 4.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "此项目免费，请勿上当受骗！",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StatusBad
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showNickname) {
        TextInputDialog("设备别名", "", "别名", onConfirm = { v ->
            showNickname = false; run("修改别名") { vm.setNickname(v) }
        }, onDismiss = { showNickname = false })
    }
    if (showToken) {
        TextInputDialog("修改口令", "", "新口令（8-128 位，含字母和数字）", onConfirm = { v ->
            showToken = false; run("修改口令") { vm.changeToken(v) }
        }, onDismiss = { showToken = false })
    }
    if (showNetworkMode) {
        OptionPickerDialog(
            title = "网络模式",
            options = NetworkMode.entries.map { it.value to it.label },
            selected = live["net_select"] ?: "",
            onSelect = { v ->
                showNetworkMode = false; run("网络模式") { vm.setNetworkMode(v) }
            },
            onDismiss = { showNetworkMode = false }
        )
    }
    if (showBandLock) {
        BandLockDialog(
            lte = live["lte_band_lock"] ?: "",
            nr = live["nr_band_lock"] ?: "",
            onConfirm = { lte, nr ->
                showBandLock = false; run("频段锁") { vm.setBandLock(lte, nr) }
            },
            onDismiss = { showBandLock = false }
        )
    }
    if (showCellLock) {
        CellLockDialog(
            current = cellLockSummary(live),
            onLock = { pci, earfcn, rat ->
                showCellLock = false; run("锁定基站") { vm.lockCell(pci, earfcn, rat) }
            },
            onUnlock = { showCellLock = false; run("解锁基站") { vm.unlockCell() } },
            onDismiss = { showCellLock = false }
        )
    }
    if (showDataLimit) {
        DataLimitDialog(
            onConfirm = { params ->
                showDataLimit = false; run("流量阈值") { vm.setDataLimit(params) }
            },
            onDismiss = { showDataLimit = false }
        )
    }
    if (showSimSlot) {
        OptionPickerDialog(
            title = "SIM 卡槽",
            options = SIM_SLOT_OPTIONS,
            selected = live["sim_slot"] ?: "",
            onSelect = { v ->
                showSimSlot = false; run("切换卡槽") { vm.setSimSlot(v) }
            },
            onDismiss = { showSimSlot = false }
        )
    }
    if (showConfirmReboot) {
        ConfirmDialog(
            title = "重启设备",
            text = "确定要重启设备吗？重启期间将短暂断开连接。",
            onConfirm = { showConfirmReboot = false; run("重启") { vm.reboot() } },
            onDismiss = { showConfirmReboot = false }
        )
    }
    if (showConfirmShutdown) {
        ConfirmDialog(
            title = "关机",
            text = "确定要关闭设备吗？关机后需要手动开机。",
            onConfirm = { showConfirmShutdown = false; run("关机") { vm.shutdown() } },
            onDismiss = { showConfirmShutdown = false }
        )
    }
    if (showSleep) {
        OptionPickerDialog(
            title = "休眠时间",
            options = SLEEP_OPTIONS,
            selected = (vm.sleepMinutes ?: -1).toString(),
            onSelect = { v ->
                showSleep = false
                val minutes = v.toIntOrNull()
                if (minutes != null) {
                    scope.launch { toast(vm.applySleepMinutes(minutes) ?: "休眠时间已更新") }
                }
            },
            onDismiss = { showSleep = false }
        )
    }
    if (showLan) {
        vm.lanSetting?.let { cur ->
            LanSettingDialog(
                initial = cur,
                onSubmit = { next ->
                    showLan = false
                    // 写入会断网，这里**不**走 run()——run 成功后会 refreshAllNow，
                    // 而此刻设备正在重启网络，必然超时刷出一堆报错，徒增噪音。
                    scope.launch {
                        val r = vm.applyLanSetting(next)
                        toast(r ?: "内网设置已下发，设备正在重启网络，请稍后用新地址重连")
                    }
                },
                onDismiss = { showLan = false }
            )
        }
    }
    if (showNfcAp) {
        OptionPickerDialog(
            title = "NFC 配对 WiFi",
            options = NFC_AP_OPTIONS,
            selected = vm.nfcState.ap,
            onSelect = { v ->
                showNfcAp = false
                scope.launch { toast(vm.setNfc(vm.nfcState.enabled, v) ?: "配对网络已更新") }
            },
            onDismiss = { showNfcAp = false }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.textSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.textPrimary
        )
    }
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String = "",
    icon: ImageVector? = null,
    @androidx.annotation.DrawableRes iconRes: Int? = null,
    iconTint: Color = AppTheme.iconTint,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        } else if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = AppTheme.textPrimary)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppTheme.textMuted)
            }
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = AppTheme.textSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SwitchItem(
    title: String,
    checked: Boolean,
    icon: ImageVector? = null,
    timeoutMs: Long = SWITCH_OPTIMISTIC_TIMEOUT_MS,
    onChange: (Boolean) -> Unit
) {
    // Switch 是受控组件，而设备回读有延迟：拨动后会先弹回旧值、约 1 秒后才跳到新值。
    // 用本地 pending 值顶住这段空窗；等外部真实值追平（写入成功）就交还控制权，
    // 迟迟追不平（写入失败）则超时回滚，避免开关一直停在假状态上。
    //
    // timeoutMs 可调：多数开关设备 1 秒内就生效，4 秒足够；
    // 但「数据开关」要等拨号/断链（官方 Web 端给的确认窗口是 8 秒），
    // 用默认值会在真正生效前就弹回去，看起来像"点了没用"。
    var pending by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(pending, checked) {
        val p = pending ?: return@LaunchedEffect
        if (checked == p) {
            pending = null
        } else {
            delay(timeoutMs)
            if (pending == p && checked != p) pending = null
        }
    }
    val shown = pending ?: checked

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = AppTheme.iconTint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = AppTheme.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = shown,
            onCheckedChange = { v ->
                pending = v
                onChange(v)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppTheme.accent,
                checkedTrackColor = AppTheme.accent.copy(alpha = 0.4f)
            )
        )
    }
}

private fun bandLockSummary(live: Map<String, String>): String {
    val lte = live["lte_band_lock"]
    val nr = live["nr_band_lock"]
    return when {
        lte.isNullOrBlank() && nr.isNullOrBlank() -> "未锁定"
        else -> "4G:${lte ?: "-"}  5G:${nr ?: "-"}"
    }
}

private fun cellLockSummary(live: Map<String, String>): String {
    val locked = live["locked_cell_info"]
    if (locked.isNullOrBlank() || locked == "0" || locked == "{}") return "未锁定"
    // 设备回读可能是 "pci,earfcn,rat" 或 JSON 对象，这里统一抽成一句能看懂的摘要。
    // 之前只判断"有值就显示已锁定"，看不出到底锁在哪个小区，等于白锁。
    val nums = Regex("\\d+").findAll(locked).map { it.value }.toList()
    val rat = when {
        locked.contains("NR", ignoreCase = true) -> "NR"
        locked.contains("LTE", ignoreCase = true) -> "LTE"
        else -> ""
    }
    return buildString {
        if (rat.isNotEmpty()) append(rat).append(" · ")
        if (nums.isNotEmpty()) append("PCI ").append(nums[0])
        if (nums.size > 1) append(" · 频点 ").append(nums[1])
        if (isEmpty()) append("已锁定")
    }
}

@Composable
private fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AppTheme.accent,
    unfocusedBorderColor = AppTheme.textPrimary.copy(alpha = 0.15f),
    focusedLabelColor = AppTheme.accent,
    unfocusedLabelColor = AppTheme.textSecondary,
    cursorColor = AppTheme.accent,
    focusedTextColor = AppTheme.textPrimary,
    unfocusedTextColor = AppTheme.textPrimary
)

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = AppTheme.textPrimary) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                colors = dialogFieldColors()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) {
                Text("确定", color = AppTheme.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppTheme.textSecondary) }
        },
        containerColor = AppTheme.cardBg
    )
}

@Composable
private fun BandLockDialog(
    lte: String,
    nr: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var lteValue by remember { mutableStateOf(lte) }
    var nrValue by remember { mutableStateOf(nr) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("频段锁", color = AppTheme.textPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = lteValue,
                    onValueChange = { lteValue = it },
                    label = { Text("4G 频段（如 1,3,5,8）") },
                    singleLine = true,
                    colors = dialogFieldColors()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = nrValue,
                    onValueChange = { nrValue = it },
                    label = { Text("5G 频段（如 41,78）") },
                    singleLine = true,
                    colors = dialogFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(lteValue.trim(), nrValue.trim()) }) {
                Text("应用", color = AppTheme.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppTheme.textSecondary) }
        },
        containerColor = AppTheme.cardBg
    )
}

@Composable
private fun CellLockDialog(
    current: String,
    onLock: (String, String, String) -> Unit,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit
) {
    var pci by remember { mutableStateOf("") }
    var earfcn by remember { mutableStateOf("") }
    var rat by remember { mutableStateOf("NR") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("锁定基站", color = AppTheme.textPrimary) },
        text = {
            Column {
                // 已锁定时先把当前锁定的基站信息展示出来，避免"不知道现在锁在哪"。
                // 设备回读字段 `locked_cell_info` 形如 "pci,earfcn,rat" 或 JSON，
                // 统一由 cellLockSummary 归一成 "NR · PCI 123 · 频点 504990"。
                if (current.isNotBlank()) {
                    Text(
                        "当前锁定：$current",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.accent
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = pci,
                    onValueChange = { pci = it },
                    label = { Text("PCI") },
                    singleLine = true,
                    colors = dialogFieldColors()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = earfcn,
                    onValueChange = { earfcn = it },
                    label = { Text("EARFCN/频点") },
                    singleLine = true,
                    colors = dialogFieldColors()
                )
                Spacer(Modifier.height(10.dp))
                // 制式由裸文本框改为选项：设备只认 NR/LTE 两个值，
                // 手输错了设备会静默忽略（goform 投递即成功），选项化可以杜绝这类错。
                Text("制式", style = MaterialTheme.typography.labelMedium, color = AppTheme.textSecondary)
                Spacer(Modifier.height(6.dp))
                Row {
                    listOf("NR", "LTE").forEach { r ->
                        OptionChip(r, rat == r) { rat = r }
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onUnlock) { Text("解除锁定", color = StatusBad) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pci.isNotBlank() && earfcn.isNotBlank()) onLock(pci.trim(), earfcn.trim(), rat.trim())
            }) { Text("锁定", color = AppTheme.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppTheme.textSecondary) }
        },
        containerColor = AppTheme.cardBg
    )
}

/**
 * 通用选项选择弹窗：把原先的裸文本框换成点选。
 *
 * @param options 值 → 显示名（显示名与值相同的可只传值名）
 */
@Composable
private fun OptionPickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = AppTheme.textPrimary) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    val isSel = value == selected || value.equals(selected, ignoreCase = true)
                    ListRow(
                        title = label,
                        subtitle = if (isSel) "当前：$value" else value,
                        icon = if (isSel) Icons.Filled.CheckCircle else null,
                        iconTint = AppTheme.accent,
                        onClick = { onSelect(value) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = AppTheme.textSecondary) }
        },
        containerColor = AppTheme.cardBg
    )
}

@Composable
private fun DataLimitDialog(
    onConfirm: (Map<String, Any>) -> Unit,
    onDismiss: () -> Unit
) {
    var enabled by remember { mutableStateOf(false) }
    var maxGb by remember { mutableStateOf("") }
    var daily by remember { mutableStateOf(false) }
    var forward by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("流量阈值", color = AppTheme.textPrimary) },
        text = {
            Column {
                DialogSwitchRow("启用流量上限", enabled) { enabled = it }
                DialogSwitchRow("按天统计", daily) { daily = it }
                DialogSwitchRow("触达时提醒", forward) { forward = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = maxGb,
                    onValueChange = { maxGb = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("上限（GB）") },
                    singleLine = true,
                    colors = dialogFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val maxBytes = (maxGb.toDoubleOrNull() ?: 0.0) * 1024.0 * 1024.0 * 1024.0
                onConfirm(
                    mapOf(
                        "data_flow_limit_enabled" to if (enabled) "1" else "0",
                        "data_flow_max_limit" to maxBytes.toLong(),
                        "data_flow_check_daily_or_monthly" to if (daily) "daily" else "monthly",
                        "data_limit_status_forward_enabled" to if (forward) "1" else "0",
                        "data_check_reference" to "ufi"
                    )
                )
            }) { Text("应用", color = AppTheme.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppTheme.textSecondary) }
        },
        containerColor = AppTheme.cardBg
    )
}

@Composable
private fun DialogSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AppTheme.textPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppTheme.accent,
                checkedTrackColor = AppTheme.accent.copy(alpha = 0.4f)
            )
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = AppTheme.textPrimary) },
        text = { Text(text, color = AppTheme.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确定", color = AppTheme.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppTheme.textSecondary) }
        },
        containerColor = AppTheme.cardBg
    )
}

/**
 * 内网设置弹窗（两阶段）。
 *
 * ⚠️ 保存后设备会**重启网络服务**，本机与设备的连接会断开、需要按新网段重连。
 * 因此这里做了两道闸：
 * 1. 表单本地先跑 [IpValidator.validate]，校验不过直接不给提交（设备对非法值是静默忽略的）；
 * 2. 校验通过后再弹一次确认，明确写出"会断网 + 新地址是多少"，用户点确认才真正下发。
 */
@Composable
private fun LanSettingDialog(
    initial: LanSetting,
    onSubmit: (LanSetting) -> Unit,
    onDismiss: () -> Unit
) {
    var gateway by remember { mutableStateOf(initial.gateway) }
    var netmask by remember { mutableStateOf(initial.netmask) }
    var dhcpOn by remember { mutableStateOf(initial.dhcpEnabled) }
    var start by remember { mutableStateOf(initial.dhcpStart) }
    var end by remember { mutableStateOf(initial.dhcpEnd) }
    var lease by remember { mutableStateOf(initial.dhcpLeaseHour) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }

    fun build() = LanSetting(
        gateway = gateway.trim(),
        netmask = netmask.trim(),
        dhcpEnabled = dhcpOn,
        dhcpStart = start.trim(),
        dhcpEnd = end.trim(),
        dhcpLeaseHour = lease.trim(),
    )

    if (confirming) {
        val next = build()
        ConfirmDialog(
            title = "确认修改内网设置？",
            text = "保存后设备会「重启网络服务」：\n\n" +
                "• 本机将与本设备断开，需要重新连接\n" +
                "• 管理页面随之改为 http://${next.gateway}:2333\n" +
                "• 若地址已改，旧的 ${initial.gateway} 将无法再访问\n\n" +
                "确定要继续吗？",
            onConfirm = { onSubmit(next) },
            onDismiss = { confirming = false }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("内网设置", color = AppTheme.textPrimary) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                // 输入框统一走公共 LabeledField：它内部给了 heightIn，
                // 避免滚动容器传 Infinity 约束导致 OutlinedTextField 测量崩溃。
                LabeledField("网关地址（如 192.168.0.1）", gateway, { gateway = it })
                Spacer(Modifier.height(10.dp))
                LabeledField("子网掩码（如 255.255.255.0）", netmask, { netmask = it })
                Spacer(Modifier.height(6.dp))
                DialogSwitchRow("启用 DHCP 服务", dhcpOn) { dhcpOn = it }
                if (dhcpOn) {
                    Spacer(Modifier.height(4.dp))
                    LabeledField("地址池起始（如 192.168.0.2）", start, { start = it })
                    Spacer(Modifier.height(10.dp))
                    LabeledField("地址池结束（如 192.168.0.253）", end, { end = it })
                    Spacer(Modifier.height(10.dp))
                    LabeledField("租期（小时，如 24）", lease, { lease = it })
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = StatusBad)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val next = build()
                val msg = IpValidator.validate(next)
                if (msg != null) {
                    error = msg
                } else {
                    error = null
                    confirming = true
                }
            }) { Text("应用", color = AppTheme.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppTheme.textSecondary) }
        },
        containerColor = AppTheme.cardBg
    )
}
