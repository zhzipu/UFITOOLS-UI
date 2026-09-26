package com.ufitools.client.ui.components

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.ui.theme.ValueTextStyle

/**
 * 统一卡片：12dp 圆角、卡片背景、点击涟漪。
 * 圆角数值对齐参考项目的 bg_widget_card（radius 12dp）。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(AppTheme.cardBg, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else Modifier
            )
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** 小节标题：14sp bold */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = AppTheme.textPrimary,
        modifier = modifier
    )
}

/** 指标单元：标签 10sp + 数值 15sp bold（对齐参考项目的 item 结构） */
@Composable
fun MetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /** 自定义图标（如随格数变化的信号柱 [SignalBars]）；给定时优先于 [icon] */
    iconSlot: (@Composable () -> Unit)? = null,
    valueColor: Color = AppTheme.textPrimary,
    sub: String? = null,
    /** 副文本颜色，默认次要色；用于「充电中」这类需要强调的状态 */
    subColor: Color = AppTheme.textSecondary
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val custom = iconSlot
        when {
            custom != null -> MetricLabelRow(label) { custom() }
            icon != null -> MetricLabelRow(label) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = AppTheme.iconTint,
                    modifier = Modifier.size(14.dp)
                )
            }
            else -> Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.textSecondary
            )
        }
        Spacer(Modifier.height(4.dp))
        // 走平滑更新：每次刷新数值变化时做模糊/淡入淡出过渡（对齐参考项目）
        SmoothUpdateText(
            text = value.ifBlank { "-" },
            style = ValueTextStyle,
            color = valueColor,
            maxLines = 1
        )
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.labelSmall, color = subColor)
        }
    }
}

/** 指标单元的「图标 + 标签」一行。图标做成插槽，好让调用方塞自绘图形。 */
@Composable
private fun MetricLabelRow(label: String, icon: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppTheme.textSecondary)
    }
}

/**
 * 自绘的蜂窝信号柱（5 格），用于「信号类型」这类需要**按格数变化**的图标位。
 *
 * 没有用 `Icons.Filled.SignalCellular*` 系列：该图标族里只有 `SignalCellular0Bar`
 * 与 `SignalCellular4Bar` 两种柱状，1~3 格的 `SignalCellularAlt1Bar/2Bar` 又是另一套
 * 形状，5 档既凑不齐也不单调。自己画 5 根递增的柱子，格数变化一眼可见。
 *
 * @param bars 信号格数，取 0..5；0 表示无信号（全部置灰）
 */
@Composable
fun SignalBars(
    bars: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    val level = bars.coerceIn(0, 5)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(1.5.dp)
    ) {
        repeat(5) { i ->
            Box(
                Modifier
                    .width(2.dp)
                    .height((4 + i * 2).dp)
                    .background(
                        if (i < level) color else AppTheme.textSecondary.copy(alpha = 0.25f),
                        RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

/**
 * 键值行：左标签 13sp 次要色 + 右数值 14sp 主色。
 *
 * @param copyable 打开后**长按整行**把数值复制到剪贴板，用于设备信息页这类
 *   需要抄录 IMEI / ICCID / MAC 等长串编号的字段。默认关闭，避免其它列表页误触。
 *   数值为空时不可复制（界面显示的是占位符 `-`，复制它没有意义）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    valueColor: Color = AppTheme.textPrimary,
    copyable: Boolean = false
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val canCopy = copyable && value.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (canCopy) {
                    Modifier.combinedClickable(
                        onLongClick = {
                            clipboard.setText(AnnotatedString(value))
                            Toast.makeText(context, "已复制 $label", Toast.LENGTH_SHORT).show()
                        },
                        // 短按不做动作：涟漪仅作为"此处可长按"的提示，避免滑动列表时误复制
                        onClick = {}
                    )
                } else {
                    Modifier
                }
            )
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.textSecondary
        )
        SmoothUpdateText(
            text = value.ifBlank { "-" },
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor,
            maxLines = 2,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

/** 分割线：1dp，半透明（对齐参考项目 alpha 0.1） */
@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppTheme.textPrimary.copy(alpha = 0.08f))
    )
}

/** 交错淡入：用于卡片入场动画（对齐参考项目的 staggered fade-in） */
@Composable
fun StaggeredFadeIn(
    index: Int,
    stepMillis: Int = 60,
    content: @Composable (Modifier) -> Unit
) {
    val value by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 280, delayMillis = index * stepMillis),
        label = "fadeIn"
    )
    content(Modifier.alpha(value))
}

// ------------------------------------------------------------------ 数值文本平滑更新

/** 旧值模糊/淡出的时长（毫秒），对齐参考项目 smoothUpdateText 的 260ms */
private const val SWAP_OUT_MILLIS = 260

/** 新值恢复清晰的时长（毫秒），对齐参考项目 smoothUpdateText 的 320ms */
private const val SWAP_IN_MILLIS = 320

/** 最大模糊半径（dp）。参考项目用 px，这里换算成 dp 以便跨密度一致 */
private const val SWAP_BLUR_DP = 3f

/**
 * 数值文本：**内容变化时做一次平滑过渡，而不是硬切**。
 *
 * 对齐参考项目 `UFITOOLS-Widget` 的 `AnimationUtil.smoothUpdateText()`：
 * 数据刷新时旧值先模糊/变淡，换成新值后再清晰回来，让"数值在动"这件事被看见。
 *
 * - Android 12+（API 31+）：模糊（[Modifier.blur]）→ 换文案 → 恢复清晰
 * - 低版本：退化为透明度 0.3 + 缩放 0.97 的淡出淡入
 *
 * 只在 `text` 真的变化时才播动画；值没变（例如速率为 0）不会有任何开销。
 */
@Composable
fun SmoothUpdateText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = ValueTextStyle,
    color: Color = AppTheme.textPrimary,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip
) {
    var shown by remember { mutableStateOf(text) }
    // 1f = 完全清晰，0f = 最模糊/最淡
    val clarity = remember { Animatable(1f) }

    LaunchedEffect(text) {
        if (text == shown) return@LaunchedEffect
        // 上一次动画没播完就来了新值：Animatable 会自动取消旧的，从这里继续
        clarity.animateTo(0f, tween(SWAP_OUT_MILLIS))
        shown = text
        clarity.animateTo(1f, tween(SWAP_IN_MILLIS))
    }

    val c = clarity.value
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Text(
        text = shown,
        style = style,
        color = color,
        maxLines = maxLines,
        textAlign = textAlign,
        overflow = overflow,
        modifier = modifier
            .graphicsLayer {
                // 低版本没有内容模糊，用透明度 + 轻微缩放替代
                if (!canBlur) {
                    alpha = 0.3f + 0.7f * c
                    val s = 0.97f + 0.03f * c
                    scaleX = s
                    scaleY = s
                }
            }
            .then(
                if (canBlur && c < 1f) {
                    Modifier.blur((SWAP_BLUR_DP * (1f - c)).dp, BlurredEdgeTreatment.Unbounded)
                } else {
                    Modifier
                }
            )
    )
}
