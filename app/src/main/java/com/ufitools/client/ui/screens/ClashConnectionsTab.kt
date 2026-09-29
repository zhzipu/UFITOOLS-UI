package com.ufitools.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufitools.client.model.ClashConnection
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel

/**
 * 连接 Tab：活动连接列表（按流量降序）。
 *
 * 与旧版相比补上了**实时速率**（每条连接的 KB/s），这是判断「哪条在大流
 * 量下载」的关键依据——只看累计值没法区分「正在跑」和「早就跑完挂着」。
 *
 * 列表上限 [DISPLAY_LIMIT] 条：内核上可能有几千条短连接，全部渲染会卡。
 */
private const val DISPLAY_LIMIT = 120

@Composable
fun ClashConnectionsTab(
    vm: MainViewModel,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onCloseAll: () -> Unit,
    onDetail: (ClashConnection) -> Unit,
) {
    val conns = vm.clashConnections

    LazyColumn(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("活动连接（${conns.size}）")
                if (conns.isNotEmpty()) {
                    TextButton(onClick = onCloseAll, enabled = !loading) {
                        Text("全部关闭", color = AppTheme.textSecondary)
                    }
                }
            }
        }

        if (conns.isEmpty()) {
            item { AppCard { EmptyHint("当前没有活动连接") } }
        } else {
            items(conns.take(DISPLAY_LIMIT), key = { it.id }) { c ->
                ConnectionRow(c) { onDetail(c) }
            }
            if (conns.size > DISPLAY_LIMIT) {
                item {
                    Text(
                        "仅显示流量最大的 $DISPLAY_LIMIT 条，共 ${conns.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textSecondary,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}
