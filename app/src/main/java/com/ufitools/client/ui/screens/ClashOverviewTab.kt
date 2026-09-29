package com.ufitools.client.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Memory
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.ufitools.client.model.bytesToHuman
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.KeyValueRow
import com.ufitools.client.ui.components.MetricCell
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.StaggeredFadeIn
import com.ufitools.client.ui.components.ThinDivider
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.ui.theme.StatusInfo
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/** 流量曲线保留的采样点数：5 秒一个点，120 个点约 10 分钟 */
private const val TRAFFIC_HISTORY = 120

/** 概览页专用的速率采样间隔，与连接轮询同步 */
private const val TRAFFIC_SAMPLE_MS = 5_000L

/**
 * 概览 Tab：实时速率曲线 + 累计流量 + 运行模式 / TUN / 端口。
 *
 * ## 实时速率怎么来的
 *
 * 内核不提供速率字段，只有累计值。这里每 [TRAFFIC_SAMPLE_MS] 采一次
 * `clashDownTotal` / `clashUpTotal`（这两个值由连接轮询顺带更新），
 * 用相邻两次的差值除以时间差得到速率，再压入曲线缓冲。
 *
 * 采样只读 ViewModel 已有状态，**不额外打接口**——连接轮询本来就在跑，
 * 这里只是搭个便车。
 */
@Composable
fun ClashOverviewTab(
    vm: MainViewModel,
    loading: Boolean,
    toast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // 曲线数据：只保留下行，上行以文字形式给出（两条线在手机上太挤）
    var downSeries by remember { mutableStateOf<List<Float>>(emptyList()) }
    var upSeries by remember { mutableStateOf<List<Float>>(emptyList()) }
    var curDown by remember { mutableStateOf(0L) }
    var curUp by remember { mutableStateOf(0L) }

    // 速率采样循环：以「累计值增量 / 时间差」算速率
    LaunchedEffect(Unit) {
        var lastAt = 0L
        var lastDown = 0L
        var lastUp = 0L
        while (true) {
            delay(TRAFFIC_SAMPLE_MS)
            val nowAt = System.currentTimeMillis()
            val d = vm.clashDownTotal
            val u = vm.clashUpTotal
            if (lastAt > 0L) {
                val dt = nowAt - lastAt
                // 负增量 = 内核重启导致计数归零，按 0 处理而不是画一根向下的针
                val dr = if (d >= lastDown) (d - lastDown) * 1000 / dt else 0L
                val ur = if (u >= lastUp) (u - lastUp) * 1000 / dt else 0L
                curDown = dr
                curUp = ur
                downSeries = (downSeries + dr.toFloat()).takeLast(TRAFFIC_HISTORY)
                upSeries = (upSeries + ur.toFloat()).takeLast(TRAFFIC_HISTORY)
            }
            lastAt = nowAt
            lastDown = d
            lastUp = u
        }
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        // ------------------------------------------------------------ 实时速率
        StaggeredFadeIn(0) { m ->
            AppCard(m) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("实时速率")
                    Text(
                        vm.clashVersion?.display ?: "-",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppTheme.textSecondary
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    SpeedCell(
                        label = "下行",
                        value = bytesToHuman(curDown) + "/s",
                        color = AppTheme.accent,
                        modifier = Modifier.weight(1f),
                    )
                    SpeedCell(
                        label = "上行",
                        value = bytesToHuman(curUp) + "/s",
                        color = StatusInfo,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                TrafficChart(
                    down = downSeries,
                    up = upSeries,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "近 ${downSeries.size * TRAFFIC_SAMPLE_MS / 1000} 秒趋势",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.textMuted,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------------------ 累计与资源
        StaggeredFadeIn(1) { m ->
            AppCard(m) {
                SectionTitle("累计流量与资源")
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    MetricCell(
                        label = "下载累计",
                        value = bytesToHuman(vm.clashDownTotal),
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.CloudDownload,
                    )
                    MetricCell(
                        label = "上传累计",
                        value = bytesToHuman(vm.clashUpTotal),
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.CloudUpload,
                    )
                    MetricCell(
                        label = "内存",
                        value = bytesToHuman(vm.clashMemory),
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Memory,
                    )
                    MetricCell(
                        label = "连接数",
                        value = vm.clashConnections.size.toString(),
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.DevicesOther,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------------------ 运行配置
        StaggeredFadeIn(2) { m ->
            AppCard(m) {
                SectionTitle("运行模式")
                Spacer(Modifier.height(10.dp))
                Row {
                    val current = vm.clashRuntime?.mode.orEmpty()
                    CLASH_MODES.forEach { (value, label) ->
                        OptionChip(
                            label = label,
                            selected = current.equals(value, ignoreCase = true),
                            enabled = !loading,
                            onClick = {
                                scope.launch {
                                    val r = vm.applyClashMode(value)
                                    toast(if (r == "success") "已切换到$label" else r)
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                ThinDivider()
                Spacer(Modifier.height(10.dp))
                KeyValueRow("日志级别", vm.clashRuntime?.logLevel.orEmpty().ifBlank { "-" })
                KeyValueRow(
                    "混合端口",
                    vm.clashRuntime?.mixedPort?.takeIf { it > 0 }?.toString() ?: "-"
                )
                KeyValueRow("TUN 模式", if (vm.clashRuntime?.tunEnabled == true) "已开启" else "未开启")
                KeyValueRow("局域网访问", if (vm.clashRuntime?.allowLan == true) "允许" else "禁止")
                KeyValueRow("IPv6", if (vm.clashRuntime?.ipv6 == true) "开启" else "关闭")
                Spacer(Modifier.height(12.dp))
                Row {
                    OptionChip("TUN 开关", vm.clashRuntime?.tunEnabled == true, enabled = !loading) {
                        scope.launch {
                            vm.applyClashTun(vm.clashRuntime?.tunEnabled != true)
                        }
                    }
                }
            }
        }
    }
}

/** 速率数字块：大号数字 + 小号标签 */
@Composable
private fun SpeedCell(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = AppTheme.textSecondary)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
    }
}

/**
 * 双线面积图（无坐标轴，纯趋势）。
 *
 * 用 [Canvas] 手绘而不是引入图表库：只有两条折线、不需要交互与图例，
 * 引入 Vico / MPAndroidChart 这类库要多几百 KB 的包体和一套新的 API 心智。
 *
 * Y 轴按当前窗口内的最大值自适应；全 0 时给一个下限，避免除零并把线压成
 * 一条贴着底边的直线（那样看起来像"没有数据"而不是"没有流量"）。
 */
@Composable
private fun TrafficChart(
    down: List<Float>,
    up: List<Float>,
    modifier: Modifier = Modifier,
) {
    val accent = AppTheme.accent
    val info = StatusInfo
    val bg = AppTheme.textPrimary.copy(alpha = 0.05f)

    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (down.size < 2) {
            Text(
                "正在采集…",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textMuted,
            )
            return@Box
        }

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // 至少按 1 KB/s 定标，避免低流量时噪声被放大成剧烈起伏
            val peak = max(max(down.maxOrNull() ?: 0f, up.maxOrNull() ?: 0f), 1024f)
            val stepX = w / (TRAFFIC_HISTORY - 1).toFloat()

            fun line(series: List<Float>): Path {
                val p = Path()
                series.forEachIndexed { i, v ->
                    // 从右往左画：最新的点贴右边，视觉上数据是"从右往左流走"
                    val x = w - (series.size - 1 - i) * stepX
                    val y = h - (v / peak) * h
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                return p
            }

            drawPath(line(down), color = accent, style = Stroke(width = 2.5f))
            drawPath(line(up), color = info, style = Stroke(width = 2.5f))

            // 基线
            drawLine(
                color = accent.copy(alpha = 0.12f),
                start = Offset(0f, h),
                end = Offset(w, h),
                strokeWidth = 1f,
            )
        }
    }
}
