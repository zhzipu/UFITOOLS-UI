package com.ufitools.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ufitools.client.ui.theme.AppTheme

/**
 * Clash 页内的二级 Tab。
 *
 * 顺序对齐 zashboard 的导航习惯：先看总览，其次才是操作目标。
 */
enum class ClashTab(val label: String) {
    OVERVIEW("概览"),
    PROXIES("代理"),
    CONNECTIONS("连接"),
    RULES("规则"),
    LOGS("日志"),
}

/**
 * 二级 Tab 栏：横向可滚动的一排胶囊。
 *
 * 为什么不用 Material3 的 `TabRow`：
 * 1. 5 个中文标签在窄屏上放不下，`TabRow` 会等分压缩到字被截断；这里要的是
 *    「按内容宽度排布 + 放不下就横滑」。
 * 2. 选中态想用 App 自己的强调色 + 白字（和 `OptionChip` 一致），
 *    `TabRow` 的 indicator 语义对不上。
 *
 * ⚠️ 选中态文字必须 `Color.White`，不能用 `AppTheme.btnBg` —— 见 `OptionChip`
 * 的 KDoc：浅色主题里 accent 与 btnBg 同色，拿 btnBg 当文字色会隐形。
 */
@Composable
fun ClashTabBar(
    selected: ClashTab,
    onSelect: (ClashTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        ClashTab.entries.forEach { t ->
            val active = t == selected
            val bg = if (active) AppTheme.accent else AppTheme.textPrimary.copy(alpha = 0.06f)
            val fg = if (active) Color.White else AppTheme.textPrimary
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .background(bg, RoundedCornerShape(9.dp))
                    .clickable { onSelect(t) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(t.label, style = MaterialTheme.typography.bodyMedium, color = fg)
            }
        }
    }
}
