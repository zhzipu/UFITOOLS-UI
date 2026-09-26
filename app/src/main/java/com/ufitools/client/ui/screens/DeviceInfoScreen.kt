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

        val conn = vm.connInfo
        if (conn != null) {
            Spacer(Modifier.height(16.dp))
            StaggeredFadeIn(3) { m ->
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
            StaggeredFadeIn(4) { m ->
                Column(m.fillMaxWidth()) {
                    SectionTitle("USB 状态", Modifier.padding(start = 4.dp, bottom = 8.dp))
                    AppCard {
                        Text(
                            usb.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTheme.textPrimary
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
