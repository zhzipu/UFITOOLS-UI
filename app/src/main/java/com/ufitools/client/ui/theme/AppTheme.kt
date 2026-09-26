package com.ufitools.client.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * 语义色访问器。
 *
 * 对应参考项目的 `ThemeColors.pageBg(ctx)` / `accent(ctx)` / `dataHighlight(ctx)` 等便捷方法。
 * 在 Composable 中通过 `AppTheme.cardBg` 等属性访问，自动跟随当前明暗模式。
 */
object AppTheme {

    val palette: Palette
        @Composable @ReadOnlyComposable
        get() = LocalPalette.current

    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalIsDark.current

    /** 页面背景 */
    val pageBg: Color
        @Composable @ReadOnlyComposable
        get() = Color(if (isDark) palette.pageBgDark else palette.pageBgLight)

    /** 卡片背景 */
    val cardBg: Color
        @Composable @ReadOnlyComposable
        get() = Color(if (isDark) palette.cardBgDark else palette.cardBgLight)

    /** 主文字 */
    val textPrimary: Color
        @Composable @ReadOnlyComposable
        get() = Color(if (isDark) palette.textPrimaryDark else palette.textPrimaryLight)

    /** 次要文字 */
    val textSecondary: Color
        @Composable @ReadOnlyComposable
        get() = Color(if (isDark) palette.textSecondaryDark else palette.textSecondaryLight)

    /** 强调色（交互） */
    val accent: Color
        @Composable @ReadOnlyComposable
        get() = Color(if (isDark) palette.accentDark else palette.accentLight)

    /** 次级强调色 */
    val accentSecondary: Color
        @Composable @ReadOnlyComposable
        get() = Color(if (isDark) palette.accentSecondaryDark else palette.accentSecondaryLight)

    /** 分割线 */
    val divider: Color
        @Composable @ReadOnlyComposable
        get() = Color(if (isDark) palette.dividerDark else palette.dividerLight)

    /** 按钮背景（保证白色文字可读） */
    val btnBg: Color
        @Composable @ReadOnlyComposable
        get() = Color(if (isDark) palette.btnBgDark else palette.btnBgLight)

    /** 图标着色 */
    val iconTint: Color
        @Composable @ReadOnlyComposable
        get() = Color(if (isDark) palette.iconTintDark else palette.iconTintLight)

    /** 核心数据高亮：设备名、流量数值等，独立于 accent */
    val dataHighlight: Color
        @Composable @ReadOnlyComposable
        get() = Color(if (isDark) palette.dataHighlightDark else palette.dataHighlightLight)

    /** 次要文字带透明度（对应参考项目 alpha 0.45） */
    val textMuted: Color
        @Composable @ReadOnlyComposable
        get() = textPrimary.copy(alpha = 0.45f)
}
