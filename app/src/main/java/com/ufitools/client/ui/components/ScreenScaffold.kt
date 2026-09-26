package com.ufitools.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ufitools.client.ui.theme.AppTheme

/**
 * 二级页面通用外壳：返回按钮 + 标题 + 可滚动内容。
 *
 * 新增的功能页（APN / 定时任务 / 上传 / 插件 / ttyd …）都是「设置页 → 子页」的形态，
 * 统一走这里，避免每页重复抄一遍顶栏和滚动容器。
 *
 * @param loading 进行中时在标题右侧显示一个小转圈
 * @param onRefresh 给定时在标题右侧显示刷新按钮
 */
@Composable
fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppTheme.iconTint
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = AppTheme.textPrimary,
                modifier = Modifier.weight(1f)
            )
            if (loading) {
                CircularProgressIndicator(
                    color = AppTheme.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            if (onRefresh != null) {
                TextButton(onClick = onRefresh, enabled = !loading) {
                    Text("刷新", color = AppTheme.accent)
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 页面级错误条：红色提示 + 文字，出错时才出现 */
@Composable
fun ErrorBanner(message: String?, modifier: Modifier = Modifier) {
    if (message.isNullOrBlank()) return
    AppCard(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFEF4444)
        )
    }
}

/** 空列表占位 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = AppTheme.textSecondary)
    }
}

/**
 * 通用文本输入弹窗内容（供 AlertDialog 使用）。
 *
 * 与设置页里的 `TextInputDialog` 不同的是：这里是**受控组件**，
 * 自己持有输入状态，由调用方在 `onConfirm` 里决定提交动作。
 */
/**
 * 通用文本输入（供二级页面使用）。
 *
 * ⚠️ **必须给有界高度**：这些页面挂在 [SubPageScaffold] 的 `verticalScroll` 里，
 * 滚动容器测量子元素时传下来的是 **`Constraints.Infinity`（无限高）**。
 * `OutlinedTextField` 的 measure policy 会做 `maxHeight - 内部高度` 的算术，
 * 拿到 Infinity 后算出天文数字，直接抛：
 * `IllegalArgumentException: Can't represent a width of … and height of 1412905 in Constraints`
 * ——曾在「设备高级设置」页实机崩溃。
 *
 * 故这里显式 `heightIn(max = …)`：单行固定高度，多行封顶，彻底避开无限约束。
 * 新增页面的输入框**都走这个组件**，不要裸用 `OutlinedTextField`。
 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    supportingText: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it, color = AppTheme.textSecondary) } },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AppTheme.accent,
            unfocusedBorderColor = AppTheme.textPrimary.copy(alpha = 0.15f),
            focusedLabelColor = AppTheme.accent,
            unfocusedLabelColor = AppTheme.textSecondary,
            cursorColor = AppTheme.accent,
            focusedTextColor = AppTheme.textPrimary,
            unfocusedTextColor = AppTheme.textPrimary
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp, max = if (singleLine) 56.dp else 140.dp)
    )
}

/**
 * 可选项行：单选/多选标签，选中态用强调色填充。
 * 与信号页的 `ModeChip` 视觉一致。
 *
 * ⚠️ `onClick` **必须放最后一个参数**：调用方普遍写成 `OptionChip("自动", sel) { … }`
 * 的尾随 lambda 形式。若把 `enabled` 排在 `onClick` 之后，尾随 lambda 会被解析到
 * `enabled: Boolean` 上 → 编译报 “No value passed for parameter 'onClick'”。
 * 需要传 `enabled` 时用命名参数写在 lambda 前：`OptionChip("x", sel, enabled = f) { … }`。
 */
@Composable
fun OptionChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val bg = if (selected) AppTheme.accent else AppTheme.cardBg
    val fg = if (selected) AppTheme.btnBg else AppTheme.textPrimary
    Box(
        modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) fg else fg.copy(alpha = 0.5f)
        )
    }
}

/** 一行操作按钮（次要动作，右对齐） */
@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    confirmText: String = "确定",
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    danger: Boolean = false
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onCancel != null) {
            TextButton(onClick = onCancel) { Text("取消", color = AppTheme.textSecondary) }
        }
        TextButton(onClick = onConfirm, enabled = confirmEnabled) {
            Text(confirmText, color = if (danger) Color(0xFFEF4444) else AppTheme.accent)
        }
    }
}
