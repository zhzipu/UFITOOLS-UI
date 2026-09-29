package com.ufitools.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufitools.client.model.ClashRule
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.KeyValueRow
import com.ufitools.client.ui.components.LabeledField
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.ui.theme.StatusInfo
import com.ufitools.client.ui.theme.StatusWarn
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 规则 Tab：分流规则 + 规则集订阅。
 *
 * ## 为什么值得单开一页
 *
 * 规则是排查「为什么这个网站走了代理 / 没走」的唯一依据。设备上常配了几千条
 * 规则，旧版没有这个入口，用户只能去 Web 面板看。
 *
 * 列表用 `LazyColumn`，因为规则动辄 5000+ 条——一次性构建会直接卡死。
 * 筛选栏做成横向滚动的分类 chip，比下拉框更适合触屏。
 */
@Composable
fun ClashRulesTab(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    toast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var keyword by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }

    // 进页拉一次。规则是大响应，不参与 5 秒轮询
    LaunchedEffect(Unit) {
        if (vm.clashRules.isEmpty()) vm.refreshClashRules()
    }

    val all = vm.clashRules
    // 分类列表由实际数据推导，不写死枚举——不同内核版本的规则类型不一样
    val categories = remember(all) {
        all.map { it.category }.distinct().sorted()
    }
    val filtered = remember(all, keyword, category) {
        val kw = keyword.trim()
        all.asSequence()
            .filter { category == null || it.category == category }
            .filter {
                kw.isEmpty() ||
                    it.text.contains(kw, ignoreCase = true) ||
                    it.proxy.contains(kw, ignoreCase = true)
            }
            .toList()
    }

    LazyColumn(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // ------------------------------------------------------------ 规则集订阅
        if (vm.clashRuleProviders.isNotEmpty()) {
            item { SectionTitle("规则集（${vm.clashRuleProviders.size}）") }
            items(vm.clashRuleProviders, key = { "rp_${it.name}" }) { p ->
                AppCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTheme.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (p.updatedAt > 0) {
                                Text(
                                    "更新于 ${clashFormatTime(p.updatedAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppTheme.textMuted,
                                )
                            }
                        }
                        TextButton(
                            onClick = { scope.launch { toast(vm.updateClashRuleProvider(p.name)) } },
                            enabled = !vm.clashRulesLoading,
                        ) { Text("更新", color = AppTheme.accent) }
                    }
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
        }

        // ------------------------------------------------------------ 统计
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("分流规则（${filtered.size}/${all.size}）")
                TextButton(
                    onClick = { scope.launch { vm.refreshClashRules() } },
                    enabled = !vm.clashRulesLoading,
                ) { Text("重新加载", color = AppTheme.accent) }
            }
        }

        if (vm.clashRulesLoading && all.isEmpty()) {
            item { AppCard { EmptyHint("正在读取规则…") } }
            return@LazyColumn
        }

        if (all.isEmpty()) {
            item {
                AppCard {
                    EmptyHint("未读取到规则，请点「重新加载」")
                }
            }
            return@LazyColumn
        }

        // ------------------------------------------------------------ 筛选
        item {
            LabeledField(
                label = "搜索",
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = "规则内容或出口节点名",
            )
        }

        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                OptionChip(
                    label = "全部（${all.size}）",
                    selected = category == null,
                    onClick = { category = null },
                )
                categories.forEach { cat ->
                    val n = all.count { it.category == cat }
                    OptionChip(
                        label = "$cat（$n）",
                        selected = category == cat,
                        onClick = { category = if (category == cat) null else cat },
                    )
                }
            }
        }

        // ------------------------------------------------------------ 规则列表
        if (filtered.isEmpty()) {
            item { AppCard { EmptyHint("没有符合条件的规则") } }
        } else {
            items(filtered, key = { "${it.type}|${it.payload}|${it.proxy}" }) { r ->
                RuleRow(r)
            }
        }

        item {
            Text(
                "规则按配置顺序匹配，命中即停。列表顺序即内核的匹配优先级。",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textMuted,
            )
        }
    }
}

/** 单条规则行：类型徽标 + 内容 + 出口 */
@Composable
private fun RuleRow(r: ClashRule) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RuleTypeBadge(r.type, r.category)
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(
                r.payload.ifBlank { "(无参数)" },
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "→ ${r.proxy.ifBlank { "-" }}",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (r.disabled) {
                Text(
                    "已禁用",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusWarn,
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
            }
            // size 只有内核给了命中数时才显示；为 0 说明这个内核版本不上报，
            // 显示 "命中 0" 会造成「这条规则从没命中过」的误导
            if (r.size > 0) {
                Text(
                    "命中 ${r.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.textMuted,
                )
            }
        }
    }
}

/** 规则类型小徽标，按大类着色 */
@Composable
private fun RuleTypeBadge(type: String, category: String) {
    val color = when (category) {
        "兜底" -> StatusWarn
        "IP" -> StatusInfo
        "域名" -> AppTheme.accent
        else -> AppTheme.textMuted
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(type, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
