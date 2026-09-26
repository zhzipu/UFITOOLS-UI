package com.ufitools.client.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ufitools.client.data.DeviceConfig
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.ui.theme.ThemeMode
import com.ufitools.client.ui.theme.ThemePalettes
import com.ufitools.client.viewmodel.ConnectionStatus
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun ConnectionScreen(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    val cfg = vm.config

    var host by remember { mutableStateOf(cfg.host) }
    var port by remember { mutableStateOf(cfg.port.toString()) }
    var token by remember { mutableStateOf(cfg.token) }
    var adminPassword by remember { mutableStateOf(cfg.adminPassword) }

    val connecting = vm.status is ConnectionStatus.Connecting
    val error = (vm.status as? ConnectionStatus.Error)?.message

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AppTheme.accent,
        unfocusedBorderColor = AppTheme.textPrimary.copy(alpha = 0.15f),
        focusedLabelColor = AppTheme.accent,
        unfocusedLabelColor = AppTheme.textSecondary,
        cursorColor = AppTheme.accent,
        focusedTextColor = AppTheme.textPrimary,
        unfocusedTextColor = AppTheme.textPrimary
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))

        Icon(
            Icons.Filled.Wifi,
            contentDescription = null,
            tint = AppTheme.accent,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "UFITOOLS-UI",
            style = MaterialTheme.typography.headlineMedium,
            color = AppTheme.textPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "连接随身 WiFi 上的控制台服务",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.textMuted
        )

        Spacer(Modifier.height(26.dp))

        AppCard {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("设备地址") },
                placeholder = { Text("192.168.0.1") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("端口") },
                placeholder = { Text("2333") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("控制台口令") },
                placeholder = { Text("admin") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = adminPassword,
                onValueChange = { adminPassword = it },
                label = { Text("后台密码（用于高级设置）") },
                placeholder = { Text("设备标签上的密码") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(Modifier.height(20.dp))

        if (connecting) {
            val pulse = rememberInfiniteTransition(label = "pulse")
            val scale by pulse.animateFloat(
                initialValue = 1f,
                targetValue = 1.03f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.scale(scale)) {
                    CircularProgressIndicator(
                        color = AppTheme.accent,
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "正在连接…",
                    color = AppTheme.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            AppCard(
                onClick = {
                    val newCfg = DeviceConfig(
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 2333,
                        token = token,
                        adminPassword = adminPassword
                    )
                    vm.updateConfig(newCfg)
                    scope.launch { vm.connect() }
                }
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "连接",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        Text(
            "主题配色",
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.textPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 10.dp)
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemePalettes.ALL.forEach { palette ->
                val selected = palette.id == vm.paletteId
                val swatch = Color(if (AppTheme.isDark) palette.accentDark else palette.accentLight)
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clickable { vm.setPalette(palette.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .border(
                                width = if (selected) 2.dp else 0.dp,
                                color = if (selected) AppTheme.accent else Color.Transparent,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.size(if (selected) 26.dp else 30.dp)) {
                            drawCircle(color = swatch)
                        }
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "已选",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThemeMode.entries.forEach { mode ->
                val selected = mode == vm.themeMode
                TextButton(onClick = { vm.applyThemeMode(mode) }) {
                    Text(
                        mode.label,
                        color = if (selected) AppTheme.accent else AppTheme.textSecondary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "控制台默认地址为 http://设备IP:2333",
            style = MaterialTheme.typography.labelMedium,
            color = AppTheme.textMuted
        )
        Spacer(Modifier.height(24.dp))
    }
}
