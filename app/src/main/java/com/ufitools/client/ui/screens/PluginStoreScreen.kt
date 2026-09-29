package com.ufitools.client.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufitools.client.model.InstalledPlugin
import com.ufitools.client.model.StorePlugin
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.ErrorBanner
import com.ufitools.client.ui.components.LabeledField
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.SubPageScaffold
import com.ufitools.client.ui.components.ThinDivider
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/** 小写并剥掉扩展名，让「5G信号监控」与「5G信号监控.txt」能互相匹配 */
private fun stripExtLower(s: String): String {
    val dot = s.lastIndexOf('.')
    val base = if (dot > 0) s.substring(0, dot) else s
    return base.trim().lowercase()
}

/**
 * 插件管理（API 文档 §14）。
 *
 * 插件是跑在设备上的 Lua 小程序。本页只做「看列表 + 装/卸 + 看通知」，
 * 上传/打包插件这类操作留给设备自带的 Web 端。
 *
 * 页内分两个 Tab（[PluginTab]）：**已安装**与**商店**。两者数据源不同
 * （已安装读设备侧 `custom_head`、商店读远端列表，见 `storeMatchFor` 的说明），
 * 早先做过左右并列，但半宽之下信息被压得太狠，
 * 现改为 Tab 切换、每个列表独占整页宽度。
 */
private enum class PluginTab(val label: String) {
    /** 设备上真实装着的插件，来源 `/api/get_custom_head` */
    INSTALLED("已安装"),

    /** 远端商店的全量列表，来源 `/api/plugin/list` */
    STORE("商店"),
}

@Composable
fun PluginStoreScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loading = "plugins" in vm.pageLoading
    val error = vm.pageError["plugins"]

    var search by remember { mutableStateOf("") }
    var pendingUninstall by remember { mutableStateOf<StorePlugin?>(null) }
    var pendingRemove by remember { mutableStateOf<InstalledPlugin?>(null) }
    var changelog by remember { mutableStateOf<String?>(null) }

    // 默认停在「已安装」：进这个页面的第一诉求通常是"我装了什么"，
    // 而商店是"再去装点什么"。也便于与点插件名跳商店定位的行为呼应。
    var tab by remember { mutableStateOf(PluginTab.INSTALLED) }

    LaunchedEffect(Unit) {
        vm.refreshPlugins()
        // 已安装列表来自 /api/get_custom_head，与商店是两个数据源，分开拉
        vm.refreshInstalledPlugins()
    }

    fun toast(msg: String) {
        Toast.makeText(context, if (msg == "success") "完成" else msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 在商店列表里找已安装插件的对应条目。
     *
     * 两条数据源的键不一样：设备侧是块名 / `sid`（原始文件名），
     * 商店侧是 `publicName`（`51.txt`）/ `installName`（`5G信号监控.txt`）/ `displayName`（`5G信号监控`）。
     * 三个都试一遍，命中不了就返回 null（手工贴进 custom_head 的第三方脚本本来就无对应条目）。
     */
    fun storeMatchFor(p: InstalledPlugin): StorePlugin? {
        val keys = listOf(p.sid, p.publicName, p.name)
            .filter { it.isNotBlank() }
            .flatMap { listOf(it.lowercase(), stripExtLower(it)) }
            .toSet()
        if (keys.isEmpty()) return null
        return vm.storePlugins.firstOrNull { s ->
            listOf(s.publicName, s.installName, s.displayName)
                .filter { it.isNotBlank() }
                .any { it.lowercase() in keys || stripExtLower(it) in keys }
        }
    }

    // 搜索只在本地过滤，避免每敲一个字就打一次设备
    val filtered = remember(vm.storePlugins, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) vm.storePlugins
        else vm.storePlugins.filter { p ->
            p.publicName.lowercase().contains(q) ||
                p.displayName.lowercase().contains(q) ||
                p.description.lowercase().contains(q)
        }
    }

    SubPageScaffold(
        title = "插件管理",
        onBack = { nav.popBackStack() },
        loading = loading,
        onRefresh = { scope.launch { vm.refreshPlugins() } }
    ) {
        ErrorBanner(error)

        if (vm.pluginNotifications.isNotEmpty()) {
            AppCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("通知（${vm.pluginNotifications.size}）")
                    TextButton(onClick = { scope.launch { vm.readPluginNotifications() } }) {
                        Text("全部已读", color = AppTheme.accent)
                    }
                }
                Spacer(Modifier.height(6.dp))
                vm.pluginNotifications.take(10).forEach { n ->
                    Text(
                        "· $n",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.textSecondary,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }

        // ── Tab 切换：已安装 / 商店 ──
        //
        // 早先是左右并列两栏，半宽之下每栏只剩 160~180dp —— 插件名要省成省略号、
        // 版本与角标要挤进同一行、商店描述只能留两行，信息被压得太狠。
        // 换成 Tab 后每个列表独占整页宽度，那些妥协全部可以撤掉；
        // 何况两个列表的数据源、能做的操作本就不同（见 storeMatchFor 的说明），
        // 分开看比并排看更清楚。
        //
        // 用 OptionChip 而非 Material3 的 TabRow：本项目所有「一段式选择」
        // 都是这个胶囊样式（选中态强调色底 + 白字，说明见 OptionChip 的 KDoc），
        // TabRow 的 indicator 语义也对不上。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OptionChip(
                label = "${PluginTab.INSTALLED.label}（${vm.installedPluginList.size}）",
                selected = tab == PluginTab.INSTALLED,
                onClick = { tab = PluginTab.INSTALLED }
            )
            OptionChip(
                label = "${PluginTab.STORE.label}（${filtered.size}）",
                selected = tab == PluginTab.STORE,
                onClick = { tab = PluginTab.STORE }
            )
        }

        when (tab) {
            PluginTab.INSTALLED -> AppCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("已安装（${vm.installedPluginList.size}）")
                    TextButton(
                        onClick = { scope.launch { vm.refreshInstalledPlugins() } },
                        enabled = !loading
                    ) { Text("刷新", color = AppTheme.accent) }
                }
                Spacer(Modifier.height(6.dp))
                if (vm.installedPluginList.isEmpty()) {
                    EmptyHint("设备上还没有插件")
                } else {
                    Text(
                        "列表顺序即插件在设备上的执行顺序，可用 ↑ ↓ 调整",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textSecondary
                    )
                    Spacer(Modifier.height(4.dp))
                    vm.installedPluginList.forEachIndexed { i, p ->
                        val store = storeMatchFor(p)
                        InstalledPluginRow(
                            p = p,
                            storeVersion = store?.version,
                            hasUpdate = store != null && p.hasUpdate(store),
                            canMoveUp = i > 0,
                            canMoveDown = i < vm.installedPluginList.lastIndex,
                            // 只在「插件名」上挂点击去商店定位，**不给整行加 clickable**：
                            // 整行可点会让竖向手势在行内被 clickable 抢走，
                            // 列表一长就出现"滑不动、得先切页再回来"的手感问题。
                            onClickName = {
                                // 点插件名 → 切到商店 Tab 并把它填进搜索框。
                                // ⚠️ 必须同时切 Tab：搜索框现在只在商店那一栏里，
                                // 只设 search 的话用户在当前 Tab 看不到任何反馈。
                                store?.let { s ->
                                    search = s.displayName
                                    tab = PluginTab.STORE
                                }
                            },
                            onMoveUp = {
                                scope.launch {
                                    // `reorderInstalledPlugin` 走 `safeWithReason`：null 表示成功、
                                    // 非 null 才是失败原因，所以这里必须补个成功文案再交给 toast
                                    toast(vm.reorderInstalledPlugin(i, i - 1) ?: "已上移")
                                }
                            },
                            onMoveDown = {
                                scope.launch {
                                    toast(vm.reorderInstalledPlugin(i, i + 1) ?: "已下移")
                                }
                            },
                            onUninstall = { pendingRemove = p }
                        )
                        if (i != vm.installedPluginList.lastIndex) {
                            ThinDivider(Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }

            PluginTab.STORE -> Column(
                // 商店侧有两张卡片（商店列表 + 变更日志），
                // 外层 Column 用与页面一致的 12dp 间距；不这么做就得把变更日志
                // 常驻在 tab 之外，那样切到「已安装」时会白占一块屏幕。
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionTitle("商店（${filtered.size}）")
                        TextButton(onClick = {
                            scope.launch {
                                val o = vm.pluginUpdateCheck()
                                changelog = o.toString()
                            }
                        }, enabled = !loading) { Text("检查更新", color = AppTheme.accent) }
                    }
                    Spacer(Modifier.height(6.dp))
                    // 搜索框跟着商店一起切走：它只筛商店，
                    // 放在 tab 外面会被误读成同时筛两个列表。
                    LabeledField("搜索", search, { search = it }, placeholder = "按名称或说明筛选")
                    Spacer(Modifier.height(6.dp))
                    if (filtered.isEmpty()) {
                        EmptyHint(if (vm.storePlugins.isEmpty()) "未读到插件列表" else "没有匹配的插件")
                    } else {
                        filtered.forEachIndexed { i, p ->
                            PluginRow(
                                p = p,
                                loading = loading,
                                onInstall = {
                                    scope.launch { toast(vm.installPlugin(p.publicName)) }
                                },
                                onUninstall = { pendingUninstall = p }
                            )
                            if (i != filtered.lastIndex) ThinDivider(Modifier.padding(vertical = 4.dp))
                        }
                    }
                }

                AppCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionTitle("变更日志")
                        TextButton(onClick = {
                            scope.launch { changelog = vm.pluginChangelog().toString() }
                        }, enabled = !loading) { Text("查看", color = AppTheme.accent) }
                    }
                    changelog?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it.take(2000),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.textSecondary
                        )
                    }
                }
            }
        }
    }

    pendingUninstall?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("卸载插件") },
            text = { Text("确定卸载「${p.displayName}」？相关数据可能一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    val name = p.installName.ifBlank { p.publicName }
                    pendingUninstall = null
                    scope.launch { toast(vm.uninstallPlugin(name)) }
                }) { Text("卸载", color = AppTheme.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text("取消", color = AppTheme.textSecondary)
                }
            }
        )
    }

    // 删除 custom_head 里的插件块：与上面走商店卸载接口的是两条不同的路，
    // 这条能删掉"手工贴进去、商店里根本没有"的插件，所以文案要区分开。
    pendingRemove?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("卸载插件") },
            text = {
                Text(
                    "确定从设备上移除「${p.name}」？\n\n" +
                        "会从自定义头部内容中删除该插件脚本（含其注入的界面与逻辑），" +
                        "操作不可撤销。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    scope.launch { toast(vm.removeInstalledPlugin(p) ?: "已卸载") }
                }) { Text("卸载", color = AppTheme.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text("取消", color = AppTheme.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun PluginRow(
    p: StorePlugin,
    loading: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit
) {
    // 自上而下四块信息，谁都不会压掉谁：
    //   1. 显示名（超长省略）+ 安装/卸载按钮
    //   2. 版本 + 作者（合成一条，用 `·` 连接）
    //   3. 描述（截到 3 行）
    //   4. 设备上的实际文件名（灰色小字，便于与设备侧对照）
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                p.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 紧凑按钮：`安装`/`卸载` 两个字加内边距约 46dp 就够用。
            // 默认 TextButton 有 **64dp 最小宽度** + 约 24dp 左右内边距，
            // 会白白占掉长插件名本可以显示的宽度。
            if (p.installed) {
                TextButton(
                    onClick = onUninstall,
                    enabled = !loading,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 0.dp)
                ) {
                    Text(
                        "卸载",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppTheme.textSecondary
                    )
                }
            } else {
                TextButton(
                    onClick = onInstall,
                    enabled = !loading,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 0.dp)
                ) {
                    Text(
                        "安装",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppTheme.accent
                    )
                }
            }
        }

        // 版本与作者并成一行：两者都是辅助信息，用 `·` 连起来可读性没有损失，
        // 却省下一整行高度 —— 商店列表动辄几十条，这个收益是可观的。
        val meta = listOfNotNull(
            p.version.takeIf { it.isNotBlank() }?.let { "v$it" },
            p.author.takeIf { it.isNotBlank() }?.let { "by $it" }
        ).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (p.description.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                p.description,
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 灰字标出设备上的实际文件名（`51.txt`），便于在设备侧/网页端对照。
        // 显示名与它相同时就不必重复一遍。
        if (p.publicName.isNotBlank() && p.publicName != p.displayName) {
            Spacer(Modifier.height(2.dp))
            Text(
                "文件名 ${p.publicName}",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textSecondary.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 已安装插件的一行。
 *
 * 与 [PluginRow]（商店条目）分开写：数据源、可做的操作、要展示的信息都不一样 ——
 * 这里不需要"安装"按钮，但需要上移/下移和"有无新版"的角标。
 *
 * 排序按钮用 `IconButton` 而不是让整行可拖拽：设备侧的顺序是写进 custom_head 的
 * 真实执行顺序，上下移是明确的两步操作，比拖拽更不容易误触。
 */
@Composable
private fun InstalledPluginRow(
    p: InstalledPlugin,
    storeVersion: String?,
    hasUpdate: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClickName: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onUninstall: () -> Unit
) {
    // 三层信息自上而下排：插件名 → 版本/角标 → 操作。
    //
    // 为什么竖排而不把三样塞进一行：「可变长的插件名 + 版本号 + 角标 + 一组按钮」
    // 挤在一行时，插件名稍长（"插件折叠布局管理器"）就会把后面内容顶出去。
    // 竖排各占一行，谁都不会被压掉，代价只是多出 20dp 左右的行高。
    Column(
        Modifier
            .fillMaxWidth()
            // 行高刻意收紧：已安装列表在设备上有十来个插件（实测 11 个），
            // 每行省 4dp 就能多露出一行。
            .padding(vertical = 6.dp)
    ) {
        Text(
            p.name,
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // ⚠️ `clickable` 只挂在插件名这个 Text 上，**绝不给整行加**：
            // 整行可点会让竖向手势在行内被 clickable 抢走，
            // 列表一长就出现"滑不动、得先切页再回来"的手感问题。
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClickName)
        )

        // 版本 + 更新角标：两者都可能有、也可能都无，用 Row 统一处理间距
        if (p.version.isNotBlank() || hasUpdate) {
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.version.isNotBlank()) {
                    Text(
                        "v${p.version.removePrefix("v").removePrefix("V")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textSecondary
                    )
                }
                if (hasUpdate) {
                    if (p.version.isNotBlank()) Spacer(Modifier.width(6.dp))
                    // 有小新版：标出来并带上目标版本，省得用户自己去商店比对。
                    Text(
                        if (storeVersion.isNullOrBlank()) "可更新"
                        else "可更新到 v${storeVersion.removePrefix("v")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // sid 是插件原始文件名，和块名不同时才有必要展示（块名可能被改过）
        if (p.sid.isNotBlank() && p.sid != p.name) {
            Text(
                p.sid,
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textSecondary.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(2.dp))

        // ── 操作区 ──
        //
        // ⚠️ 这里刻意不用三个 TextButton：Material 的 TextButton 有
        // **64dp 最小宽度**（Material3 的 ButtonDefaults.MinWidth）+ 约 24dp 的
        // 默认左右内边距，三个并排就吃掉约 200dp，还会把行高撑到 60dp 以上。
        // 改用 28dp 的 IconButton + 紧凑卸载按钮，整组约 90dp。
        //
        // 左对齐是刻意的：列表现在独占整页宽度（平板约 2400dp），右对齐会让按钮
        // 贴着屏幕最右侧，手指要点它得横跨整屏；贴着插件名下方更顺手。
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                modifier = Modifier.size(28.dp)
            ) {
                Text(
                    "↑",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (canMoveUp) AppTheme.accent
                    else AppTheme.textSecondary.copy(alpha = 0.3f)
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                modifier = Modifier.size(28.dp)
            ) {
                Text(
                    "↓",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (canMoveDown) AppTheme.accent
                    else AppTheme.textSecondary.copy(alpha = 0.3f)
                )
            }
            // 卸载是破坏性操作，给足点击区域、但用紧凑内边距把总宽压到 ~44dp
            TextButton(
                onClick = onUninstall,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.defaultMinSize(minWidth = 0.dp)
            ) {
                Text(
                    "卸载",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.textSecondary
                )
            }
        }
    }
}
