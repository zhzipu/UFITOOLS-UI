package com.ufitools.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ufitools.client.ui.screens.ApnScreen
import com.ufitools.client.ui.screens.AppearanceScreen
import com.ufitools.client.ui.screens.AtCommandScreen
import com.ufitools.client.ui.screens.BlacklistScreen
import com.ufitools.client.ui.screens.ClashScreen
import com.ufitools.client.ui.screens.ConnectionScreen
import com.ufitools.client.ui.screens.DashboardScreen
import com.ufitools.client.ui.screens.DeviceAdvancedScreen
import com.ufitools.client.ui.screens.PluginStoreScreen
import com.ufitools.client.ui.screens.ScheduledTaskScreen
import com.ufitools.client.ui.screens.SettingsScreen
import com.ufitools.client.ui.screens.SignalScreen
import com.ufitools.client.ui.screens.SmsForwardScreen
import com.ufitools.client.ui.screens.SmsScreen
import com.ufitools.client.ui.screens.TerminalServiceScreen
import com.ufitools.client.ui.screens.UploadManagerScreen
import com.ufitools.client.ui.screens.UsageHistoryScreen
import com.ufitools.client.ui.screens.WebScreen
import com.ufitools.client.ui.screens.WifiSettingsScreen
import com.ufitools.client.ui.components.UpdateDialog
import com.ufitools.client.ui.components.openUrl
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.ui.theme.UFIToolsTheme
import com.ufitools.client.viewmodel.ConnectionStatus
import com.ufitools.client.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            UFIToolsTheme(
                paletteId = vm.paletteId,
                themeMode = vm.themeMode
            ) {
                App(vm)
            }
        }
    }
}

/**
 * 底栏一项。
 *
 * 图标支持两种来源：[icon] 走 Material 矢量图标（会被 tint 染色），
 * [iconRes] 走位图资源（必须自带 alpha 掩码才能正确染色，见 drawable 下的 ic_cat.png）。
 * 两者互斥，谁非空用谁。
 */
private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector? = null,
    @androidx.annotation.DrawableRes val iconRes: Int? = null,
)

private val tabs = listOf(
    TabItem("dashboard", "仪表盘", icon = Icons.Filled.Dashboard),
    TabItem("signal", "信号", icon = Icons.Filled.SignalCellularAlt),
    TabItem("clash", "猫猫", iconRes = R.drawable.ic_cat),
    TabItem("sms", "短信", icon = Icons.Filled.Sms),
    TabItem("settings", "设置", icon = Icons.Filled.Settings)
)

@Composable
fun App(vm: MainViewModel) {
    val nav = rememberNavController()
    val status = vm.status
    val context = LocalContext.current

    // 启动自动检查发现新版本 → 弹窗。独立于连接状态，任何界面都会提示。
    val info = vm.updateInfo
    if (info != null && info.hasUpdate && !vm.updateDialogDismissed) {
        UpdateDialog(
            info = info,
            currentVersion = vm.appVersion,
            onDismiss = { vm.updateDialogDismissed = true },
            onDownload = {
                // 优先给匹配本机 ABI 的 APK 直链；拿不到就开 Release 页让用户自选
                openUrl(context, info.apkUrl ?: info.releaseUrl, "无法打开下载链接")
                vm.updateDialogDismissed = true
            }
        )
    }

    if (status !is ConnectionStatus.Connected) {
        // 上次成功登录过（vm 启动即自动连接）→ 这里显示过渡页，不要闪一下连接表单；
        // 一旦自动登录失败（status 变 Error），立刻回落到连接表单并把原因显示出来。
        if (vm.autoLogin && status !is ConnectionStatus.Error) {
            AutoLoginSplash(vm)
        } else {
            ConnectionScreen(vm)
        }
        return
    }

    Scaffold(
        containerColor = AppTheme.pageBg,
        bottomBar = {
            val backStack by nav.currentBackStackEntryAsState()
            val currentRoute = backStack?.destination?.route
            NavigationBar(containerColor = AppTheme.cardBg) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            val v = tab.icon
                            if (v != null) {
                                Icon(v, contentDescription = tab.label)
                            } else {
                                Icon(
                                    painter = painterResource(tab.iconRes!!),
                                    contentDescription = tab.label
                                )
                            }
                        },
                        label = { Text(tab.label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppTheme.accent,
                            selectedTextColor = AppTheme.accent,
                            unselectedIconColor = AppTheme.textSecondary,
                            unselectedTextColor = AppTheme.textSecondary,
                            indicatorColor = AppTheme.accent.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding)
        ) {
            composable("dashboard") { DashboardScreen(vm, nav) }
            composable("signal") { SignalScreen(vm) }
            // Clash 面板：原生 UI 直连 mihomo external-controller，不内嵌 WebView
            composable("clash") { ClashScreen(vm, nav) }
            // 注：「设备信息」不再是独立路由——已收进仪表盘右上角的「i」弹窗，
            // 见 DashboardScreen 里的 DeviceInfoDialog
            composable("sms") { SmsScreen(vm) }
            composable("settings") { SettingsScreen(vm, nav) }
            composable("appearance") { AppearanceScreen(vm, nav) }
            composable("at") { AtCommandScreen(vm, nav) }
            // ---- API 文档对照新增的二级页面 ----
            composable("apn") { ApnScreen(vm, nav) }
            composable("usage") { UsageHistoryScreen(vm, nav) }
            composable("tasks") { ScheduledTaskScreen(vm, nav) }
            composable("wifi-settings") { WifiSettingsScreen(vm, nav) }
            composable("uploads") { UploadManagerScreen(vm, nav) }
            composable("plugins") { PluginStoreScreen(vm, nav) }
            composable("terminal") { TerminalServiceScreen(vm, nav) }
            composable("sms-forward") { SmsForwardScreen(vm, nav) }
            composable("blacklist") { BlacklistScreen(vm, nav) }
            composable("device-advanced") { DeviceAdvancedScreen(vm, nav) }
            // 网页版 UFI-TOOLS：内嵌 WebView，与 App 同源（同一服务 :2333），
            // 从仪表盘点型号标题进来
            composable("web") { WebScreen(vm, nav) }
        }
    }
}

/**
 * 启动自动登录过渡页。
 *
 * 只在「上次成功登录过、本次冷启动正在自动连接」时出现，标题沿用连接页的视觉语言，
 * 但不展示表单，避免用户看到表单又被主界面顶掉。
 */
@Composable
private fun AutoLoginSplash(vm: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = AppTheme.accent,
            modifier = Modifier.size(40.dp),
            strokeWidth = 3.dp
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "正在连接设备…",
            style = MaterialTheme.typography.titleLarge,
            color = AppTheme.textPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            vm.config.baseUrl,
            style = MaterialTheme.typography.labelMedium,
            color = AppTheme.textMuted
        )
    }
}
