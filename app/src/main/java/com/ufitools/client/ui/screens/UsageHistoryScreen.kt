package com.ufitools.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufitools.client.model.UsagePoint
import com.ufitools.client.model.bytesToHumanOrZero
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.ErrorBanner
import com.ufitools.client.ui.components.KeyValueRow
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.SubPageScaffold
import com.ufitools.client.ui.components.ThinDivider
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/** 区间选项：天数 → 标签 */
private val RANGE_OPTIONS = listOf(7 to "近 7 天", 30 to "近 30 天", 90 to "近 90 天")

/**
 * 蜂窝流量历史（API 文档 §8）。
 *
 * 数据源 `/api/cellularUsage?method=date-range`，按天返回用量。
 * 图表用纯 Compose 柱子画（项目没有图表库），柱高按区间峰值归一化，
 * 这样即使只有几十 KB 也能看出趋势。
 */
@Composable
fun UsageHistoryScreen(vm: MainViewModel, nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val loading = "usage" in vm.pageLoading
    val error = vm.pageError["usage"]
    var days by remember { mutableStateOf(30) }

    LaunchedEffect(days) { vm.refreshUsageHistory(days) }

    val points = vm.usagePoints
    val total = points.sumOf { it.bytes }
    val peak = points.maxOfOrNull { it.bytes } ?: 0L
    val avg = if (points.isEmpty()) 0L else total / points.size

    SubPageScaffold(
        title = "流量历史",
        onBack = { nav.popBackStack() },
        loading = loading,
        onRefresh = { scope.launch { vm.refreshUsageHistory(days) } }
    ) {
        ErrorBanner(error)

        AppCard {
            SectionTitle("区间")
            Spacer(Modifier.height(8.dp))
            Row {
                RANGE_OPTIONS.forEach { (d, label) ->
                    OptionChip(label, days == d, enabled = !loading) { days = d }
                }
            }
        }

        AppCard {
            SectionTitle("汇总")
            Spacer(Modifier.height(4.dp))
            KeyValueRow("合计", bytesToHumanOrZero(total.toDouble()))
            KeyValueRow("日均", bytesToHumanOrZero(avg.toDouble()))
            KeyValueRow("峰值日", bytesToHumanOrZero(peak.toDouble()))
            KeyValueRow("天数", if (points.isEmpty()) "-" else "${points.size} 天")
        }

        AppCard {
            SectionTitle("每日用量")
            Spacer(Modifier.height(12.dp))
            if (points.isEmpty()) {
                EmptyHint("该区间没有流量记录")
            } else {
                UsageBarChart(points, peak)
                Spacer(Modifier.height(12.dp))
                ThinDivider()
                Spacer(Modifier.height(8.dp))
                // 只列有流量的日子，避免 90 天空白项刷屏
                points.filter { it.bytes > 0 }.reversed().take(20).forEach { p ->
                    KeyValueRow(p.date, bytesToHumanOrZero(p.bytes.toDouble()))
                }
                if (points.none { it.bytes > 0 }) {
                    EmptyHint("区间内有记录但用量均为 0")
                }
            }
        }
    }
}

/**
 * 极简柱状图。
 *
 * 每根柱子按 [peak] 归一化高度；柱子超过 40 根时不再画 x 轴标签
 * （宽度不够，文字会重叠成一坨）。
 */
@Composable
private fun UsageBarChart(points: List<UsagePoint>, peak: Long) {
    val maxH = 120.dp
    val showLabels = points.size <= 16

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(maxH),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            points.forEach { p ->
                val ratio = if (peak <= 0L) 0f else (p.bytes.toFloat() / peak.toFloat())
                // 有量但极小的日子也要看得见，给个 3dp 下限
                val h = if (p.bytes > 0L) (maxH * ratio).coerceAtLeast(3.dp) else 1.dp
                Box(
                    Modifier
                        .weight(1f)
                        .height(h)
                        .background(
                            if (p.bytes > 0L) AppTheme.accent else AppTheme.textPrimary.copy(alpha = 0.12f),
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }
        if (showLabels) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                points.forEach { p ->
                    Text(
                        // 只留 MM-DD，年份在标题/汇总里
                        p.date.takeLast(5),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    points.first().date.takeLast(5),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.textSecondary
                )
                Text(
                    "共 ${points.size} 天",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.textSecondary
                )
                Text(
                    points.last().date.takeLast(5),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.textSecondary
                )
            }
        }
    }
}
