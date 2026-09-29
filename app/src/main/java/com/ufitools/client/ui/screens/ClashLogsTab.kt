package com.ufitools.client.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufitools.client.model.ClashLogEntry
import com.ufitools.client.model.ClashLogLevel
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.ui.theme.StatusBad
import com.ufitools.client.ui.theme.StatusGood
import com.ufitools.client.ui.theme.StatusWarn
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志 Tab：内核实时日志流（`WS /logs`）。
 *
 * ## 交互要点
 *
 * - **自动吸底**：只要用户停在底部附近就跟着最新日志滚；一旦用户手动
 *   往上翻，就停止吸底（用 [rememberLazyListState] 的 `canScrollForward`
 *   判断），否则用户想看历史日志时会被不断拽回底部。
 * - **暂停**：暂停只停「自动滚动」，**连接不断**、日志照收。这样用户暂停
 *   去看一条报错，看完点恢复，中间的内容一条不少——比断开重连体验好得多。
 * - **等级筛选**：内核只在建连时读 `level` 参数，所以切换等级会触发重连，
 *   由 ViewModel 负责。
 * - 长按日志行复制单条内容（排查问题时要贴给别人看）。
 *
 * ⚠️ 连接生命周期由 [ClashScreen] 的 `DisposableEffect` 管，本组件不自己
 * 建连，只消费 [MainViewModel.clashLogStream]。
 */
@Composable
fun ClashLogsTab(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val entries by vm.clashLogStream.entries.collectAsState()
    val connected by vm.clashLogStream.connected.collectAsState()
    val streamError by vm.clashLogStream.error.collectAsState()
    val received by vm.clashLogStream.received.collectAsState()

    val listState = rememberLazyListState()

    // 自动吸底：只在「用户没往上翻」且「没暂停」时跟滚
    val atBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total == 0 || last >= total - 3
        }
    }
    LaunchedEffect(entries.size, vm.clashLogPaused) {
        if (!vm.clashLogPaused && atBottom && entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    Column(modifier.fillMaxSize()) {
        // ------------------------------------------------------------ 状态条
        AppCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 连接状态点：绿=已连 / 黄=连接中 / 红=断开且有错误
                    val dotColor = when {
                        connected -> StatusGood
                        streamError != null -> StatusBad
                        else -> StatusWarn
                    }
                    Box(
                        Modifier
                            .height(8.dp)
                            .padding(end = 6.dp)
                            .background(dotColor, RoundedCornerShape(4.dp))
                    )
                    Text(
                        when {
                            connected -> "已连接"
                            streamError != null -> "连接中断"
                            else -> "连接中…"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = dotColor,
                    )
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Text(
                        "共 $received 条 · 缓存 ${entries.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textMuted,
                    )
                }
                TextButton(onClick = { vm.clearClashLogs() }) {
                    Text("清空", color = AppTheme.textSecondary)
                }
            }

            streamError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = StatusBad)
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                ClashLogLevel.entries.forEach { lv ->
                    OptionChip(
                        label = lv.label,
                        selected = vm.clashLogLevel == lv,
                        onClick = { vm.applyClashLogLevel(lv) },
                    )
                }
                OptionChip(
                    label = if (vm.clashLogPaused) "继续滚动" else "暂停滚动",
                    selected = vm.clashLogPaused,
                    onClick = { vm.toggleClashLogPaused() },
                )
                OptionChip(
                    label = "复制全部",
                    selected = false,
                    enabled = entries.isNotEmpty(),
                    onClick = { copyAll(context, entries) },
                )
            }
            Text(
                "切换等级会重连（内核只在建连时读取等级）。暂停只停滚动，不停止接收。",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textMuted,
            )
        }

        Spacer(Modifier.height(10.dp))

        // ------------------------------------------------------------ 日志列表
        if (entries.isEmpty()) {
            AppCard {
                EmptyHint(if (connected) "暂无日志，内核当前没有输出" else "正在建立日志连接…")
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(entries, key = { it.seq }) { e ->
                    LogRow(e) { copyOne(context, e) }
                }
            }
        }
    }
}

/** 单条日志行：时间 + 等级 + 内容，长按复制 */
@Composable
private fun LogRow(e: ClashLogEntry, onCopy: () -> Unit) {
    val color = when (e.level) {
        ClashLogLevel.ERROR -> StatusBad
        ClashLogLevel.WARNING -> StatusWarn
        ClashLogLevel.INFO -> AppTheme.textPrimary
        ClashLogLevel.DEBUG -> AppTheme.textMuted
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy)
            .padding(vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                TIME_FMT.format(Date(e.time)),
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textMuted,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(
                e.level.wire.uppercase().take(4),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(
                e.payload,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontFamily = FontFamily.Monospace,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 毫秒级时间格式：日志排查要精确到毫秒，秒级粒度分不清同一次请求的多行输出 */
private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun copyOne(context: Context, e: ClashLogEntry) {
    val text = "${TIME_FMT.format(Date(e.time))} [${e.level.wire}] ${e.payload}"
    clipboard(context).setPrimaryClip(ClipData.newPlainText("clash-log", text))
    android.widget.Toast.makeText(context, "已复制该行", android.widget.Toast.LENGTH_SHORT).show()
}

/** 复制当前缓冲里的全部日志，供贴到别处排查 */
private fun copyAll(context: Context, entries: List<ClashLogEntry>) {
    if (entries.isEmpty()) return
    val text = entries.joinToString("\n") {
        "${TIME_FMT.format(Date(it.time))} [${it.level.wire}] ${it.payload}"
    }
    clipboard(context).setPrimaryClip(ClipData.newPlainText("clash-log", text))
    android.widget.Toast
        .makeText(context, "已复制 ${entries.size} 条日志", android.widget.Toast.LENGTH_SHORT)
        .show()
}

private fun clipboard(context: Context): ClipboardManager =
    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
