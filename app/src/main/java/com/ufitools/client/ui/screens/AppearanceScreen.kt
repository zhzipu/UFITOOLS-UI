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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.ThinDivider
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.ui.theme.ThemeMode
import com.ufitools.client.ui.theme.ThemePalettes
import com.ufitools.client.viewmodel.MainViewModel

/**
 * 外观设置：主题配色 + 明暗模式。
 *
 * 配色网格对齐参考项目「5 套预设 + 双栏选择 + 选中态实色填充」的设计。
 */
@Composable
fun AppearanceScreen(vm: MainViewModel, nav: NavHostController) {
    val dark = AppTheme.isDark

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = AppTheme.iconTint)
            }
            Text(
                "外观",
                style = MaterialTheme.typography.headlineMedium,
                color = AppTheme.textPrimary
            )
        }

        // ── 配色选择 ──
        SectionTitle("主题配色", Modifier.padding(start = 4.dp, bottom = 8.dp))
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 双栏网格
                ThemePalettes.ALL.chunked(2).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        row.forEach { palette ->
                            val selected = palette.id == vm.paletteId
                            val swatch = Color(
                                if (dark) palette.accentDark else palette.accentLight
                            )
                            val bg = Color(if (dark) palette.pageBgDark else palette.pageBgLight)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(64.dp)
                                    .background(bg, RoundedCornerShape(10.dp))
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) AppTheme.accent
                                        else AppTheme.textPrimary.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { vm.setPalette(palette.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(18.dp)
                                            .background(swatch, CircleShape)
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        palette.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color(if (dark) palette.textPrimaryDark else palette.textPrimaryLight)
                                    )
                                    if (selected) {
                                        Spacer(Modifier.size(6.dp))
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "已选",
                                            tint = AppTheme.accent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 明暗模式 ──
        SectionTitle("明暗模式", Modifier.padding(start = 4.dp, bottom = 8.dp))
        AppCard {
            Column {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    val selected = mode == vm.themeMode
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.applyThemeMode(mode) }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            mode.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = AppTheme.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "已选",
                                tint = AppTheme.accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (index != ThemeMode.entries.lastIndex) {
                        ThinDivider()
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
