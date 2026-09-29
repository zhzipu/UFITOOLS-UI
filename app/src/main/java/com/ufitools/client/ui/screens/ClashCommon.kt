package com.ufitools.client.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.ufitools.client.model.ClashProxy
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.ui.theme.StatusBad
import com.ufitools.client.ui.theme.StatusGood
import com.ufitools.client.ui.theme.StatusWarn

/**
 * Clash 页各 Tab 共用的常量与小工具。
 *
 * ## 为什么单独一个文件
 *
 * Kotlin 的 `private` 是**文件级**可见性，不是「类内可见」。原来的
 * `ClashScreen.kt` 把所有东西塞在一个文件里时，这些工具写成 `private`
 * 没问题；一旦按 Tab 拆成多个文件，`ClashProxiesTab.kt` 就再也看不到
 * `ClashScreen.kt` 里的 `BULK_TEST_LIMIT` / `formatDate` / `groupTypeLabel`
 * ——编译直接报 `Cannot access '...': it is private in file`。
 *
 * 统一提到这里、改成 `internal`（模块内可见），既让各 Tab 都能用，
 * 又不会污染到模块外部。**新增跨 Tab 共用的东西，一律加到这个文件**，
 * 不要再留在某一个 Tab 文件里写 `private`。
 */

/** 面板页自动刷新连接列表的间隔（毫秒）。连接是高频变化项，其余数据靠手动刷新。 */
internal const val CONN_AUTO_REFRESH_MS = 5_000L

/** 单次「一键测速」最多测多少个节点，避免一个组上百个节点把内核拖死 */
internal const val BULK_TEST_LIMIT = 40

/** 内核运行模式可选值：值 -> 中文名 */
internal val CLASH_MODES = listOf(
    "rule" to "规则",
    "global" to "全局",
    "direct" to "直连",
)

/** 延迟分档阈值（毫秒）——沿用信号页的「优 / 良 / 差」配色语言 */
internal const val DELAY_GOOD_MS = 200
internal const val DELAY_FAIR_MS = 500

/**
 * 延迟 → 颜色：优（绿）/ 良（黄）/ 差（红）。
 *
 * ⚠️ **必须带 `@Composable`**：`AppTheme.textMuted` / `AppTheme.accent`
 * 这类语义色是 `@ReadOnlyComposable` 属性，本质读 `CompositionLocal`，
 * 在普通函数里访问会编译失败。
 *
 * 因此**调用点必须在 Composable 上下文**。典型反例是在 `NodeCard` 里
 * 用 `val tint = delayColor(...)` 取到一个普通变量、再在 `Modifier`
 * 链或其他非 Composable 位置使用——那种写法要么改成本函数在
 * Composable 位置调用并立即消费，要么把颜色常量直接内联。
 */
@Composable
@ReadOnlyComposable
internal fun delayColor(delayMs: Int): Color = when {
    delayMs <= 0 -> AppTheme.textMuted
    delayMs <= DELAY_GOOD_MS -> StatusGood
    delayMs <= DELAY_FAIR_MS -> StatusWarn
    else -> StatusBad
}

/** 组类型中文名 */
internal fun groupTypeLabel(type: String): String = when {
    type.equals("Selector", true) -> "手动"
    type.equals("URLTest", true) -> "自动"
    type.equals("Fallback", true) -> "故障转移"
    type.equals("LoadBalance", true) -> "负载均衡"
    else -> type.ifBlank { "未知" }
}

/**
 * `MM-dd HH:mm`。
 *
 * ⚠️ **不能叫 `formatTime`**：`UploadManagerScreen.kt` 里已经有一个
 * 同名的 `private fun formatTime(Long)`。虽然当前它写成 `private`、
 * 两者井水不犯河水，但那是「靠另一个文件的可见性选择」在维持不冲突，
 * 非常脆弱——一旦那边哪天改成 `internal`，立刻 `Conflicting overloads`。
 * 加 `Clash` 前缀彻底避免这类跨文件重名。
 */
internal fun clashFormatTime(ms: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))

/** `yyyy-MM-dd`，命名理由同 [clashFormatTime] */
internal fun clashFormatDate(ms: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        .format(java.util.Date(ms))

/**
 * 从策略组的 `all` 名单里挑出**真正能渲染成卡片**的成员。
 *
 * ## 为什么需要这个过滤
 *
 * 实测（mihomo v1.19.31）发现：`PROXY` 组的 `all` 列了 **43 个名字**，
 * 但 `/proxies` 顶层只有 **8 个条目**，其中 42 个名字查不到对应 proxy。
 * 这些查不到的是**机场用「信息节点」占位**——分机场常把流量提示、
 * 到期时间、客服邮箱做成假节点塞进名单，内核不会为它们建真实出站。
 *
 * 直接在 UI 里 `mapNotNull` 会让「共 43 个节点」旁边只渲染出 1 张卡片，
 * 用户看到的是「数字对不上」这个明显 bug。所以：
 *
 * 1. 用本函数统一口径，**计数与渲染共用同一份结果**，不再各算各的；
 * 2. 界面上把「名义成员数」与「可展示数」的差额说明白，而不是假装没少。
 *
 * ## 顺序与去重
 *
 * 保持 `group.all` 的原始顺序（那是内核给的推荐排序，zashboard 同样遵循）；
 * 同一名字在名单里出现两次时只保留第一次，否则 `LazyColumn` 的 key 会撞。
 */
internal fun renderableGroupMembers(
    group: ClashProxy,
    allProxies: List<ClashProxy>,
): List<ClashProxy> {
    if (group.all.isEmpty()) return emptyList()
    val byName = allProxies.associateBy { it.name }
    val seen = HashSet<String>(group.all.size)
    return group.all.mapNotNull { name ->
        if (name.isBlank() || !seen.add(name)) return@mapNotNull null
        byName[name]
    }
}
