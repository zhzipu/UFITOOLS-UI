package com.ufitools.client.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.KeyValueRow
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.StaggeredFadeIn
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel

private fun JsonObject.s(key: String): String =
    get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

@Composable
fun DeviceInfoScreen(vm: MainViewModel) {
    val live = vm.live
    val version = vm.version
    val base = vm.baseInfo

    LaunchedEffect(Unit) {
        vm.refreshDeviceExtra()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            "设备信息",
            style = MaterialTheme.typography.headlineMedium,
            color = AppTheme.textPrimary
        )
        Spacer(Modifier.height(12.dp))

        StaggeredFadeIn(0) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("设备标识", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard {
                    // 本页全是长串编号，统一允许长按复制
                    KeyValueRow("设备型号", base?.s("model") ?: version?.s("model") ?: "", copyable = true)
                    KeyValueRow("IMEI", live["imei"] ?: "", copyable = true)
                    KeyValueRow("ICCID", live["iccid"] ?: "", copyable = true)
                    KeyValueRow("IMSI", live["imsi"] ?: "", copyable = true)
                    KeyValueRow("MSISDN(手机号)", live["msisdn"] ?: "", copyable = true)
                    KeyValueRow("MAC 地址", live["mac_address"] ?: "", copyable = true)
                    KeyValueRow("网关 IP", live["lan_ipaddr"] ?: "", copyable = true)
                    KeyValueRow("固件版本", live["wa_inner_version"] ?: "", copyable = true)
                    KeyValueRow("CR 版本", live["cr_version"] ?: "", copyable = true)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        StaggeredFadeIn(1) { m ->
            Column(m.fillMaxWidth()) {
                SectionTitle("UFITOOLS-UI 版本", Modifier.padding(start = 4.dp, bottom = 8.dp))
                AppCard {
                    KeyValueRow(
                        "应用版本",
                        version?.s("app_ver") ?: base?.s("app_ver") ?: "",
                        copyable = true
                    )
                    KeyValueRow(
                        "版本号",
                        version?.s("app_ver_code") ?: base?.s("app_ver_code") ?: "",
                        copyable = true
                    )
                    KeyValueRow(
                        "设备别名",
                        version?.s("nickname") ?: version?.s("alias") ?: version?.s("device_name") ?: "",
                        copyable = true
                    )
                }
            }
        }

        val selinux = vm.selinux
        if (selinux != null) {
            Spacer(Modifier.height(16.dp))
            StaggeredFadeIn(2) { m ->
                Column(m.fillMaxWidth()) {
                    SectionTitle("系统", Modifier.padding(start = 4.dp, bottom = 8.dp))
                    AppCard {
                        KeyValueRow("SELinux", selinux, copyable = true)
                    }
                }
            }
        }

        // ---------------------------------------------------------- 资源占用（baseDeviceInfo 扩展字段）
        // 这些字段 goform 不提供（POLL_FIELDS 里请求了但 U60Pro 不回），
        // 只能从 /api/baseDeviceInfo 取，所以单独成卡、有值才显示。
        val extra = vm.baseExtra ?: base
        if (extra != null) {
            val rows = EXTRA_FIELDS.mapNotNull { (key, label) ->
                extra.s(key).takeIf { it.isNotBlank() }?.let { label to it }
            }
            if (rows.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                StaggeredFadeIn(3) { m ->
                    Column(m.fillMaxWidth()) {
                        SectionTitle("资源占用", Modifier.padding(start = 4.dp, bottom = 8.dp))
                        AppCard {
                            rows.forEach { (label, value) ->
                                KeyValueRow(label, value, copyable = true)
                            }
                        }
                    }
                }
            }
        }

        val conn = vm.connInfo
        if (conn != null) {
            Spacer(Modifier.height(16.dp))
            StaggeredFadeIn(4) { m ->
                Column(m.fillMaxWidth()) {
                    SectionTitle("连接统计", Modifier.padding(start = 4.dp, bottom = 8.dp))
                    AppCard {
                        if (conn.entrySet().isEmpty()) {
                            Text("无数据", color = AppTheme.textSecondary)
                        }
                        conn.entrySet().forEach { (k, v) ->
                            if (!v.isJsonNull && v.isJsonPrimitive) {
                                KeyValueRow(k, v.asString, copyable = true)
                            }
                        }
                    }
                }
            }
        }

        val usb = vm.usbStatus
        if (usb != null) {
            Spacer(Modifier.height(16.dp))
            StaggeredFadeIn(5) { m ->
                Column(m.fillMaxWidth()) {
                    SectionTitle("USB 状态", Modifier.padding(start = 4.dp, bottom = 8.dp))
                    AppCard {
                        // 之前直接 toString() 把整个 JSON 丢出来，阅读体验很差。
                        // 这里按与「连接统计」相同的做法展开成一行一个字段；
                        // 值可能是数字/布尔/字符串，统一走 asString 转文本。
                        if (usb.entrySet().isEmpty()) {
                            Text("无数据", color = AppTheme.textSecondary)
                        }
                        usb.entrySet().forEach { (k, v) ->
                            if (!v.isJsonNull && v.isJsonPrimitive) {
                                KeyValueRow(
                                    usbLabel(k),
                                    usbValue(k, v.asString),
                                    copyable = true
                                )
                            }
                        }
                        // 少数固件会返回嵌套对象，兜底平铺一层，避免整段 JSON 消失
                        usb.entrySet().forEach { (k, v) ->
                            if (v.isJsonObject) {
                                v.asJsonObject.entrySet().forEach { (k2, v2) ->
                                    if (!v2.isJsonNull && v2.isJsonPrimitive) {
                                        KeyValueRow(usbLabel("$k.$k2"), usbValue("$k.$k2", v2.asString), copyable = true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 资源占用字段 → 中文名。
 *
 * 字段名随固件略有差异，这里把见过的几种写法都列上，取到哪个显示哪个。
 */
private val EXTRA_FIELDS = listOf(
    "cpu_temp" to "CPU 温度",
    "cpu_temperature" to "CPU 温度",
    "cpu_usage" to "CPU 占用",
    "mem_usage" to "内存占用",
    "mem_total" to "内存总量",
    "mem_free" to "内存可用",
    "flash_total" to "存储总量",
    "flash_free" to "存储可用",
    "storage_total" to "存储总量",
    "storage_free" to "存储可用",
    "uptime" to "运行时长",
    "build_time" to "构建时间",
    "hw_ver" to "硬件版本",
    "model" to "设备型号",
)

/** USB 字段名 → 中文名；认不出来的保留原名，至少不丢信息 */
private fun usbLabel(key: String): String = when (key.substringAfterLast('.')) {
    "usb_mode", "mode" -> "USB 模式"
    "usb_network_protocal", "protocol" -> "USB 网络协议"
    "connected", "usb_connected", "usb_status" -> "连接状态"
    "speed" -> "速率"
    "ip_addr", "ip" -> "IP 地址"
    "mac_addr", "mac" -> "MAC 地址"
    "vendor", "vendor_id" -> "厂商 ID"
    "product", "product_id" -> "产品 ID"
    "tethering" -> "USB 共享"
    else -> key
}

/** USB 字段值本地化：0/1、true/false 这类原始值转成人话 */
private fun usbValue(key: String, raw: String): String {
    val k = key.substringAfterLast('.')
    if (k.contains("connected") || k == "usb_status") {
        return when (raw.lowercase()) {
            "1", "true", "connected", "on" -> "已连接"
            "0", "false", "disconnected", "off" -> "未连接"
            else -> raw
        }
    }
    if (k == "usb_network_protocal" || k == "protocol") {
        return when (raw.lowercase()) {
            "0" -> "RNDIS"
            "1" -> "ECM"
            "2" -> "NCM"
            else -> raw
        }
    }
    return raw
}
