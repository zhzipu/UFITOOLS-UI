package com.ufitools.client.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.ErrorBanner
import com.ufitools.client.ui.components.LabeledField
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.SubPageScaffold
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel
import com.ufitools.client.viewmodel.SmsForwardConfig
import kotlinx.coroutines.launch

/**
 * 短信转发配置（API 文档 §6.3）。
 *
 * 设备把不同转发方式拆成 4 组接口，本页按「方式选择 → 该方式的参数 → 黑名单」组织。
 * 保存时只提交当前方式那一组，避免把别的方式的已填内容顺手覆盖掉。
 */
@Composable
fun SmsForwardScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loading = "smsForward" in vm.pageLoading
    val error = vm.pageError["smsForward"]

    // 本地编辑副本：初始来自设备，用户改完点保存才写回
    var cfg by remember { mutableStateOf<SmsForwardConfig?>(null) }
    var enabled by remember { mutableStateOf(vm.smsForward) }
    var powerFwd by remember { mutableStateOf(vm.powerForward) }

    LaunchedEffect(Unit) {
        vm.refreshSmsForwardConfig()
        vm.refreshSmsForward()
    }

    // 设备数据回来后就地刷新本地副本（只在本地副本还是空的时候灌入，避免冲掉用户正在编辑的内容）
    LaunchedEffect(vm.smsForwardCfg) {
        if (cfg == null) cfg = vm.smsForwardCfg
    }
    LaunchedEffect(vm.powerForward) { powerFwd = vm.powerForward }

    fun toast(msg: String) {
        Toast.makeText(context, if (msg == "success") "已保存" else msg, Toast.LENGTH_SHORT).show()
    }

    SubPageScaffold(
        title = "短信转发",
        onBack = { nav.popBackStack() },
        loading = loading,
        onRefresh = {
            scope.launch {
                cfg = null
                vm.refreshSmsForwardConfig()
                vm.refreshSmsForward()
            }
        }
    ) {
        ErrorBanner(error)

        // ---------------------------------------------------------- 总开关
        AppCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("短信转发")
                    Text(
                        "收到短信后自动转发到你配置的目标",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.textSecondary
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        scope.launch { toast(vm.applySmsForward(on)) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AppTheme.btnBg,
                        checkedTrackColor = AppTheme.accent
                    )
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("电量变化转发", style = MaterialTheme.typography.bodyMedium, color = AppTheme.textPrimary)
                    Text(
                        "设备电量变化时也推送一条通知",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.textSecondary
                    )
                }
                Switch(
                    checked = powerFwd,
                    onCheckedChange = { on ->
                        powerFwd = on
                        scope.launch { toast(vm.applyPowerForward(on)) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AppTheme.btnBg,
                        checkedTrackColor = AppTheme.accent
                    )
                )
            }
        }

        val c = cfg
        if (c == null) {
            AppCard { EmptyHint(if (loading) "正在读取配置…" else "未读到转发配置，点右上角刷新重试") }
        } else {
            // ---------------------------------------------------------- 方式选择
            AppCard {
                SectionTitle("转发方式")
                Spacer(Modifier.height(8.dp))
                Row {
                    SmsForwardConfig.METHODS.forEach { (value, label) ->
                        OptionChip(label, c.method == value, enabled = !loading) {
                            cfg = c.copy(method = value)
                            scope.launch { toast(vm.saveSmsForwardMethod(value)) }
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 各方式参数
            when (c.method) {
                "CURL" -> AppCard {
                    SectionTitle("CURL 配置")
                    Spacer(Modifier.height(8.dp))
                    LabeledField(
                        "模板（必须含 {{sms-body}}）",
                        c.curlText,
                        { cfg = c.copy(curlText = it) },
                        singleLine = false,
                        placeholder = "curl -X POST -d '{{sms-body}}' http://..."
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "可用占位符：{{sms-body}} 正文、{{sms-time}} 时间、{{sms-from}} 号码",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textSecondary
                    )
                    ForwardDevInfoSwitch(c.curlForwardDevInfo) { cfg = c.copy(curlForwardDevInfo = it) }
                    SaveRow { scope.launch { toast(vm.saveSmsForwardCurl(c)) } }
                }

                "DINGTALK" -> AppCard {
                    SectionTitle("钉钉机器人")
                    Spacer(Modifier.height(8.dp))
                    LabeledField("Webhook 地址", c.dingtalkWebhook, { cfg = c.copy(dingtalkWebhook = it) })
                    Spacer(Modifier.height(8.dp))
                    LabeledField("加签密钥（可选）", c.dingtalkSecret, { cfg = c.copy(dingtalkSecret = it) })
                    ForwardDevInfoSwitch(c.dingtalkForwardDevInfo) { cfg = c.copy(dingtalkForwardDevInfo = it) }
                    SaveRow { scope.launch { toast(vm.saveSmsForwardDingtalk(c)) } }
                }

                else -> AppCard {
                    SectionTitle("邮件 (SMTP)")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(2f)) {
                            LabeledField("SMTP 服务器", c.smtpHost, { cfg = c.copy(smtpHost = it) })
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            LabeledField("端口", c.smtpPort, { cfg = c.copy(smtpPort = it) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LabeledField("收件人", c.smtpTo, { cfg = c.copy(smtpTo = it) })
                    Spacer(Modifier.height(8.dp))
                    LabeledField("发件账号", c.smtpUsername, { cfg = c.copy(smtpUsername = it) })
                    Spacer(Modifier.height(8.dp))
                    LabeledField("密码 / 授权码", c.smtpPassword, { cfg = c.copy(smtpPassword = it) })
                    ForwardDevInfoSwitch(c.mailForwardDevInfo) { cfg = c.copy(mailForwardDevInfo = it) }
                    SaveRow { scope.launch { toast(vm.saveSmsForwardMail(c)) } }
                }
            }

            // ---------------------------------------------------------- 黑名单
            AppCard {
                SectionTitle("黑名单")
                Spacer(Modifier.height(4.dp))
                Text(
                    "命中号码或关键词的短信不会被转发（多个用英文逗号分隔）",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.textSecondary
                )
                Spacer(Modifier.height(8.dp))
                LabeledField("号码", c.blacklistPhones, { cfg = c.copy(blacklistPhones = it) })
                Spacer(Modifier.height(8.dp))
                LabeledField("关键词", c.blacklistKeywords, { cfg = c.copy(blacklistKeywords = it) })
                SaveRow { scope.launch { toast(vm.saveSmsForwardBlacklist(c)) } }
            }
        }
    }
}

@Composable
private fun ForwardDevInfoSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppTheme.btnBg,
                checkedTrackColor = AppTheme.accent
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "附带设备信息",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.textPrimary
        )
    }
}

@Composable
private fun SaveRow(onSave: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onSave) { Text("保存", color = AppTheme.accent) }
    }
}
