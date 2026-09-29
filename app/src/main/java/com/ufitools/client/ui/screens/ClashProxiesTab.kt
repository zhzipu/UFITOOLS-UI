package com.ufitools.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufitools.client.model.ClashProxy
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.KeyValueRow
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.ui.theme.StatusBad
import com.ufitools.client.ui.theme.StatusGood
import com.ufitools.client.ui.theme.StatusWarn
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 代理 Tab：策略组 + 订阅。
 *
 * ## 与旧版的区别
 *
 * 旧版节点是紧凑的列表行，一屏能塞十几行但很难点准（尤其平板横屏、
 * 手指粗的时候）。这里改成 **zashboard 风格的卡片网格**：每个节点一张
 * 小卡片，延迟数字放大居中、整卡可点，用「延迟色」而不是文字来传达好坏，
 * 扫一眼就能找到最快的节点。
 *
 * 用 `LazyColumn` 承载，因为一个组可能有上百个节点，一次性构建全部卡片
 * 会明显卡顿。
 *
 * ⚠️ 本组件自带滚动容器，**不能**再套进 `verticalScroll`，否则会因为
 * 无限高度约束崩溃（详见 `LabeledField` 的 KDoc 里那条实机崩溃记录）。
 */
@Composable
fun ClashProxiesTab(
    vm: MainViewModel,
    loading: Boolean,
    bulkProgress: Pair<Int, Int>?,
    onBulkProgress: (Pair<Int, Int>?) -> Unit,
    toast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val groups = vm.clashGroups

    // 默认展开第一个组，省去一次点击
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        // ------------------------------------------------------------ 策略组
        item {
            SectionTitle("策略组（${groups.size}）")
        }

        if (groups.isEmpty()) {
            item { AppCard { EmptyHint("未读取到策略组，请先刷新") } }
        } else {
            groups.forEach { g ->
                // 只算一次，卡片头计数与下面的网格共用同一份结果，
                // 避免出现「标题说 43 个、网格只渲染 1 张」的自相矛盾
                val renderable = renderableGroupMembers(g, vm.clashProxies)
                item(key = "g_${g.name}") {
                    GroupCard(
                        group = g,
                        renderableCount = renderable.size,
                        expanded = expandedGroup == g.name,
                        testing = g.name in vm.clashTesting,
                        enabled = !loading && bulkProgress == null,
                        onToggle = {
                            expandedGroup = if (expandedGroup == g.name) null else g.name
                        },
                        onTestGroup = {
                            scope.launch { toast(vm.testClashGroup(g.name)) }
                        },
                    )
                }

                if (expandedGroup == g.name) {
                    // 组内工具条：整组测速 + 进度
                    item(key = "gt_${g.name}") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 只测「有真实 proxy 条目、且不是子组」的成员：
                            // - 子组（如 GLOBAL 里的 PROXY）的延迟取决于内部选中谁，
                            //   单独测它意义不大，且组头已有「整组测速」
                            // - 只存在于 all 名单、provider 里没有实体的名字，
                            //   测它们只会拿到 0 或 404，白占 BULK_TEST_LIMIT 名额
                            val testable = renderable.filter { it.isSelectable && !it.isGroup }
                            OptionChip(
                                label = "全部测速",
                                selected = false,
                                enabled = !loading && bulkProgress == null && testable.isNotEmpty(),
                                onClick = {
                                    scope.launch {
                                        onBulkProgress(0 to testable.size)
                                        val r = vm.testClashNodes(
                                            testable.take(BULK_TEST_LIMIT).map { it.name }
                                        ) { done, total -> onBulkProgress(done to total) }
                                        onBulkProgress(null)
                                        toast(r)
                                    }
                                },
                            )
                            val bp = bulkProgress
                            if (bp != null) {
                                Text(
                                    "${bp.first}/${bp.second}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AppTheme.accent,
                                )
                            }
                        }
                    }

                    // 节点卡片网格：每行两张。
                    // 用上面已经算好的 renderable，与卡片头计数保持同一口径。
                    val nodes = renderable
                    if (nodes.isEmpty()) {
                        item(key = "gn_${g.name}") {
                            AppCard {
                                EmptyHint(
                                    if (g.all.isEmpty()) "该组内没有可展示的节点"
                                    else "该组 ${g.all.size} 个成员均无对应节点数据（多为机场信息占位）"
                                )
                            }
                        }
                    } else {
                        items(nodes.chunked(2), key = { row -> "r_${g.name}_${row.first().name}" }) { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { n ->
                                    NodeCard(
                                        node = n,
                                        selected = g.now == n.name,
                                        testing = n.name in vm.clashTesting,
                                        enabled = !loading,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            scope.launch {
                                                val r = vm.selectClashNode(g.name, n.name)
                                                if (r != "success") toast(r)
                                            }
                                        },
                                        onLongTest = {
                                            scope.launch { toast(vm.testClashNode(n.name)) }
                                        },
                                    )
                                }
                                // 奇数个节点时补一个空位，避免最后一张卡被拉伸成整行
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ 订阅
        if (vm.clashProviders.isNotEmpty()) {
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionTitle("订阅（${vm.clashProviders.size}）") }
            items(vm.clashProviders, key = { "p_${it.name}" }) { p ->
                AppCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = AppTheme.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    toast(vm.updateClashProvider(p.name))
                                    delay(1_200)
                                    vm.refreshClash()
                                }
                            },
                            enabled = !loading,
                        ) { Text("更新", color = AppTheme.accent) }
                    }
                    val sub = p.subscriptionInfo
                    if (sub != null && sub.total > 0) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { sub.ratio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (sub.ratio > 0.9f) StatusWarn else AppTheme.accent,
                            trackColor = AppTheme.textPrimary.copy(alpha = 0.08f),
                        )
                        Spacer(Modifier.height(8.dp))
                        KeyValueRow("已用", "${sub.usedText} / ${sub.totalText}")
                        KeyValueRow("剩余", sub.remainText)
                        if (sub.expire > 0) KeyValueRow("到期", clashFormatDate(sub.expire))
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            p.vehicleType.ifBlank { "代理集" } +
                                if (p.updatedAt > 0) " · 更新于 ${clashFormatTime(p.updatedAt)}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTheme.textSecondary,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** 策略组卡片头：组名 + 当前选中 + 展开箭头 + 整组测速 */
@Composable
private fun GroupCard(
    group: ClashProxy,
    /** 实际可渲染成卡片的成员数；与 `group.all.size` 可能不等，见 renderableGroupMembers */
    renderableCount: Int,
    expanded: Boolean,
    testing: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onTestGroup: () -> Unit,
) {
    AppCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    TypeBadge(groupTypeLabel(group.type))
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    group.now.ifBlank { "未选择" },
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (testing) {
                CircularProgressIndicator(
                    color = AppTheme.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp).padding(end = 6.dp),
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = AppTheme.iconTint,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OptionChip(
                    label = if (testing) "测速中" else "整组测速",
                    selected = false,
                    enabled = enabled && !testing,
                    onClick = onTestGroup,
                )
                // 名义成员数与可展示数不一致时把实情说清楚（差额多为机场信息占位节点），
                // 只报一个数字会让用户以为界面漏渲染了
                Text(
                    if (renderableCount == group.all.size) "共 ${group.all.size} 个节点"
                    else "共 ${group.all.size} 个成员（可切换 $renderableCount 个）",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.textMuted,
                )
            }
        }
    }
}

/** 组类型小标签 */
@Composable
private fun TypeBadge(text: String) {
    Box(
        Modifier
            .background(AppTheme.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = AppTheme.textMuted)
    }
}

/**
 * 节点卡片（zashboard 风格）。
 *
 * 信息层级：**延迟数字最大**（这是选节点时唯一关心的），名字次之，
 * 再用一条颜色边和底色表示可用性。点击 = 切换节点，长按/右上角闪电 = 单点测速。
 *
 * 之所以把延迟做成主视觉：节点名字往往很长（`🇭🇰 HK-01 | 1x | 专线`），
 * 排成大字号会挤成一团；而延迟是纯数字，放大后辨识成本最低。
 */
@Composable
private fun NodeCard(
    node: ClashProxy,
    selected: Boolean,
    testing: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongTest: () -> Unit,
) {
    val tint = if (!node.alive) StatusBad else delayColor(node.delay)
    // 选中的卡片：底色用强调色的低透明度，边框用强调色
    val bg = if (selected) AppTheme.accent.copy(alpha = 0.14f) else AppTheme.textPrimary.copy(alpha = 0.05f)
    val border = if (selected) AppTheme.accent else Color.Transparent

    Box(
        modifier
            .padding(horizontal = 3.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.5.dp, border, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    node.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (testing) {
                    CircularProgressIndicator(
                        color = AppTheme.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(12.dp).padding(start = 4.dp),
                    )
                } else if (!node.isGroup) {
                    // 子组不给单点测速入口：测组等于测它内部当前选中的那个节点，
                    // 而组头已经有「整组测速」覆盖这个需求。放两处反而让人犹豫点哪个。
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = "测速",
                        tint = if (enabled) AppTheme.textMuted else AppTheme.textMuted.copy(alpha = 0.4f),
                        modifier = Modifier
                            .size(15.dp)
                            .clickable(enabled = enabled, onClick = onLongTest),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                // 组内成员可能是「子策略组」（如 GLOBAL 里的 PROXY）。
                // 子组没有自己的延迟——它的延迟取决于内部选中谁，
                // 显示 `--` 会让人误以为"这个节点坏了"。
                // 改显示它当前的选中项，嵌套关系一眼可见。
                if (node.isGroup) {
                    Text(
                        node.now.ifBlank { "未选择" },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "子组",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textMuted,
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                } else {
                    Text(
                        if (!node.alive) "超时" else if (node.delay > 0) node.delay.toString() else "--",
                        style = MaterialTheme.typography.titleMedium,
                        color = tint,
                    )
                    if (node.alive && node.delay > 0) {
                        Text(
                            " ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTheme.textMuted,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Text("当前", style = MaterialTheme.typography.labelSmall, color = AppTheme.accent)
                }
            }
        }
    }
}
