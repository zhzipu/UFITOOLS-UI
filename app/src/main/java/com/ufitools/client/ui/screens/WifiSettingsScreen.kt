package com.ufitools.client.ui.screens

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufitools.client.model.WifiAp
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.ErrorBanner
import com.ufitools.client.ui.components.LabeledField
import com.ufitools.client.ui.components.SubPageScaffold
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 单个频段正在编辑的草稿。
 *
 * 设备值读回后填一次，用户编辑只改草稿，点「保存」才落设备 ——
 * 避免每敲一个字符就往设备打一次 goform 写请求。
 */
private data class WifiApDraft(
    val ssid: String,
    val password: String,
    val authMode: String,
    val broadcastDisabled: Boolean,
    val pmf: String,
) {
    companion object {
        fun from(ap: WifiAp) = WifiApDraft(
            ssid = ap.ssid,
            password = ap.password,
            authMode = ap.authMode,
            broadcastDisabled = ap.broadcastDisabled,
            pmf = ap.pmf,
        )
    }
}

/**
 * 安全模式下拉的选项。**值域与标签都照抄设备网页版**（`<select name="AuthMode">`），
 * 这样 App 与网页端各自保存后互相读起来不会有语义差。
 */
private val AUTH_MODES = listOf(
    "none" to "OPEN",
    "psk2+ccmp" to "WPA2-PSK",
    "psk-mixed+tkip+ccmp" to "WPA-PSK/WPA2-PSK",
    "sae-mixed" to "WPA2-PSK/WPA3-SAE",
    "sae" to "WPA3-SAE",
)

/** PMF 下拉：网页版只有 0/1 两档（关闭 / 开启） */
private val PMF_MODES = listOf("0" to "关闭", "1" to "开启")

/**
 * WiFi 设置（对齐设备网页版的「WiFi管理」弹窗，API 文档 §10）。
 *
 * 布局照搬网页版：左右两栏（2.4 GHz / 5 GHz），每栏 SSID、隐藏SSID、安全模式、
 * 密码（可切换明文）、PMF，下方是设备端实时生成的分享二维码。
 * 窄屏（竖屏）退化为纵向堆叠。
 *
 * ⚠️ **保存会重启对应频段的 WiFi**：连着它的设备全部断开几秒，
 * 改了 SSID/密码还要用新凭据重连 —— 所以提交前必须过一次确认对话框
 * （网页版的成功提示同样是 "请重新连接 WiFi"）。
 */
@Composable
fun WifiSettingsScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loading = "wifiSettings" in vm.pageLoading
    val error = vm.pageError["wifiSettings"]
    val apList = vm.wifiApList

    // 草稿（key = chipIndex）；清空后由 apList 的 LaunchedEffect 重新填充
    var drafts by remember { mutableStateOf<Map<Int, WifiApDraft>>(emptyMap()) }
    // 保存成功后 ++：设备重启了 WiFi，二维码要按新配置重新生成
    var qrEpoch by remember { mutableIntStateOf(0) }
    var pendingSave by remember { mutableStateOf<Int?>(null) }

    fun toast(msg: String) {
        Toast.makeText(context, if (msg == "success") "已保存" else msg, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) { vm.refreshWifiApConfig() }

    LaunchedEffect(apList) {
        val list = apList ?: return@LaunchedEffect
        // 只在草稿为空时填充：编辑到一半被轮询触发的重新赋值不会冲掉输入
        if (drafts.isEmpty() && list.isNotEmpty()) {
            drafts = list.associate { it.chipIndex to WifiApDraft.from(it) }
        }
    }

    fun requestSave(chip: Int) {
        val d = drafts[chip] ?: return
        // 与网页版一致：非开放网络必须有密码（empty_password 提示）
        if (d.authMode != "none" && d.password.isBlank()) {
            toast("请输入密码")
            return
        }
        pendingSave = chip
    }

    SubPageScaffold(
        title = "WiFi 设置",
        onBack = { nav.popBackStack() },
        loading = loading,
        onRefresh = {
            drafts = emptyMap()
            scope.launch { vm.refreshWifiApConfig() }
        }
    ) {
        ErrorBanner(error)

        val list = apList
        when {
            list == null -> EmptyHint("正在读取 WiFi 配置…")
            list.isEmpty() -> EmptyHint("读不到 AP 配置，WiFi 模块可能未开启")
            else -> {
                val online = list.filter { it.switchOn }
                if (online.isEmpty()) {
                    EmptyHint("两个频段的 WiFi 都处于关闭状态，先在设置里打开再改配置")
                } else {
                    // 平板横屏走两栏（对齐网页版布局）；窄屏纵向堆叠，每栏仍独占整宽
                    val twoColumns = LocalConfiguration.current.screenWidthDp >= 900
                    if (twoColumns) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            online.forEach { ap ->
                                BandCard(
                                    ap = ap,
                                    draft = drafts[ap.chipIndex] ?: WifiApDraft.from(ap),
                                    qrEpoch = qrEpoch,
                                    vm = vm,
                                    onUpdate = { d -> drafts = drafts + (ap.chipIndex to d) },
                                    onSave = { requestSave(ap.chipIndex) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            online.forEach { ap ->
                                BandCard(
                                    ap = ap,
                                    draft = drafts[ap.chipIndex] ?: WifiApDraft.from(ap),
                                    qrEpoch = qrEpoch,
                                    vm = vm,
                                    onUpdate = { d -> drafts = drafts + (ap.chipIndex to d) },
                                    onSave = { requestSave(ap.chipIndex) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))
                    Text(
                        "保存后该频段 WiFi 会重启，已连接的设备需要重新连接；" +
                            "改了 SSID 或密码的话要用新凭据。",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textSecondary
                    )
                }
            }
        }
    }

    pendingSave?.let { chip ->
        AlertDialog(
            onDismissRequest = { pendingSave = null },
            title = { Text("保存 WiFi 配置") },
            text = {
                Text(
                    "保存后该频段 WiFi 会重启：已连接的设备会断开几秒；" +
                        "若改了 SSID 或密码，需要用新凭据重新连接。\n\n确定保存？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val d = drafts[chip] ?: run {
                        pendingSave = null
                        return@TextButton
                    }
                    val ap = apList?.firstOrNull { it.chipIndex == chip }
                    pendingSave = null
                    if (ap == null) return@TextButton
                    val cfg = ap.copy(
                        ssid = d.ssid,
                        password = d.password,
                        authMode = d.authMode,
                        broadcastDisabled = d.broadcastDisabled,
                        pmf = d.pmf,
                    )
                    scope.launch {
                        toast(vm.setWifiAp(cfg))
                        // 设备侧配置已变：清掉草稿让读回的新值重新填充，二维码也要重生成
                        drafts = emptyMap()
                        vm.refreshWifiApConfig()
                        qrEpoch++
                    }
                }) { Text("保存", color = AppTheme.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingSave = null }) { Text("取消", color = AppTheme.textSecondary) }
            }
        )
    }
}

/** 一个频段的编辑卡片：标题 + SSID + 隐藏SSID + 安全模式 + 密码 + PMF + 二维码 + 保存 */
@Composable
private fun BandCard(
    ap: WifiAp,
    draft: WifiApDraft,
    qrEpoch: Int,
    vm: MainViewModel,
    onUpdate: (WifiApDraft) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    var reveal by remember { mutableStateOf(false) }

    AppCard(modifier) {
        Text(
            ap.bandLabel,
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        LabeledField("SSID", draft.ssid, { onUpdate(draft.copy(ssid = it)) })

        Spacer(Modifier.height(6.dp))
        SwitchRow("隐藏SSID", draft.broadcastDisabled) { onUpdate(draft.copy(broadcastDisabled = it)) }

        Spacer(Modifier.height(6.dp))
        DropdownRow("安全模式", draft.authMode, AUTH_MODES) { onUpdate(draft.copy(authMode = it)) }
        Spacer(Modifier.height(6.dp))

        // 开放网络没有密码框 —— 与网页版 `showable` 的隐藏行为一致
        if (draft.authMode != "none") {
            SecretField(
                label = "密码",
                value = draft.password,
                onValueChange = { onUpdate(draft.copy(password = it)) },
                reveal = reveal,
                onToggleReveal = { reveal = !reveal }
            )
            Spacer(Modifier.height(6.dp))
        }

        DropdownRow("PMF", draft.pmf, PMF_MODES) { onUpdate(draft.copy(pmf = it)) }
        Spacer(Modifier.height(10.dp))

        QrSection(chip = ap.chipIndex, epoch = qrEpoch, vm = vm)
        Spacer(Modifier.height(10.dp))

        TextButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text("保存", color = AppTheme.accent)
        }
    }
}

/** label 在上的开关行（隐藏SSID） */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AppTheme.textPrimary)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AppTheme.accent)
        )
    }
}

/**
 * 下拉选择行（安全模式 / PMF）。
 *
 * 不用 `ExposedDropdownMenuBox`：它对测量约束有要求，在 `SubPageScaffold` 的
 * `verticalScroll`（无限高）里没有验证过；普通 `DropdownMenu` 是 Popup，无此风险。
 */
@Composable
private fun DropdownRow(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = AppTheme.textSecondary)
        Spacer(Modifier.height(2.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppTheme.textPrimary.copy(alpha = 0.06f))
                    .clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    options.firstOrNull { it.first == value }?.second ?: value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = AppTheme.textSecondary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (v, text) ->
                    DropdownMenuItem(
                        text = { Text(text, color = if (v == value) AppTheme.accent else AppTheme.textPrimary) },
                        onClick = {
                            onSelect(v)
                            open = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * 密码输入框：比 [LabeledField] 多一个「切换明文」的眼睛按钮。
 *
 * ⚠️ **必须给有界高度**（`heightIn`）—— 本页挂在 `SubPageScaffold` 的
 * `verticalScroll` 里，滚动容器传下来的是无限高约束，
 * 裸用 `OutlinedTextField` 会崩（见 LabeledField 的 KDoc，同一坑）。
 */
@Composable
private fun SecretField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    reveal: Boolean,
    onToggleReveal: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleReveal) {
                Icon(
                    if (reveal) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (reveal) "隐藏密码" else "显示密码",
                    tint = AppTheme.textSecondary
                )
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AppTheme.accent,
            unfocusedBorderColor = AppTheme.textPrimary.copy(alpha = 0.15f),
            focusedLabelColor = AppTheme.accent,
            unfocusedLabelColor = AppTheme.textSecondary,
            cursorColor = AppTheme.accent,
            focusedTextColor = AppTheme.textPrimary,
            unfocusedTextColor = AppTheme.textPrimary
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp, max = 56.dp)
    )
}

/**
 * 设备端实时生成的分享二维码（`/api/wifi/qrcode?chip=N`，PNG 直接解码）。
 *
 * 白底固定不跟主题（黑白二维码在白底上识别率最好，与原 WiFi 二维码页同决策）。
 * [epoch] 变化（保存成功后 ++）会强制重新拉取 —— 设备重启了 WiFi，
 * 旧二维码的内容（含旧密码）已失效。
 */
@Composable
private fun QrSection(chip: Int, epoch: Int, vm: MainViewModel) {
    var bmp by remember(chip, epoch) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(chip, epoch) { mutableStateOf(false) }

    LaunchedEffect(chip, epoch) {
        val bytes = vm.wifiQrcode(chip)
        bmp = bytes?.takeIf { it.isNotEmpty() }
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        failed = bmp == null
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("二维码", style = MaterialTheme.typography.titleSmall, color = AppTheme.textPrimary)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Color.White),
            contentAlignment = Alignment.Center
        ) {
            val b = bmp
            when {
                b != null -> Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = "WiFi 二维码",
                    contentScale = ContentScale.Fit,
                    // ⚠️ 必须 fillMaxSize()：只给 fillMaxWidth() 的话 Image 高度停在
                    // 位图固有高度上，二维码会被缩得很小（原 WiFi 二维码页踩过）
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                )
                failed -> Text(
                    "二维码获取失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.textSecondary
                )
                else -> CircularProgressIndicator(
                    color = AppTheme.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
