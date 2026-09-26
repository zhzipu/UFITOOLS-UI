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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun AtCommandScreen(vm: MainViewModel, nav: NavHostController) {
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("ATI") }
    var result by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppTheme.iconTint
                )
            }
            Text(
                "AT 命令终端",
                style = MaterialTheme.typography.headlineMedium,
                color = AppTheme.textPrimary
            )
        }

        AppCard {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("AT 命令（以 AT 开头）") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppTheme.accent,
                    unfocusedBorderColor = AppTheme.textPrimary.copy(alpha = 0.15f),
                    focusedLabelColor = AppTheme.accent,
                    unfocusedLabelColor = AppTheme.textSecondary,
                    cursorColor = AppTheme.accent,
                    focusedTextColor = AppTheme.textPrimary,
                    unfocusedTextColor = AppTheme.textPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        color = AppTheme.accent,
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(6.dp))
                }
                TextButton(
                    onClick = {
                        loading = true
                        scope.launch {
                            result = vm.atCommand(command.trim())
                            loading = false
                        }
                    },
                    enabled = !loading && command.startsWith("AT", ignoreCase = true)
                ) {
                    Text("执行", color = AppTheme.accent)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionTitle("输出", Modifier.padding(start = 4.dp, bottom = 8.dp))
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(14.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    result.ifBlank { "（暂无输出）" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = if (result.isBlank()) AppTheme.textSecondary else AppTheme.textPrimary
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}
