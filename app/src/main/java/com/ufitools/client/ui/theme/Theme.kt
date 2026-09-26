package com.ufitools.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * 应用主题。
 *
 * 颜色体系参考 UFITOOLS-Widget：由 [Palette] 驱动的 21 角色配色，
 * Material3 colorScheme 从中派生，保证组件默认样式与自定义语义色一致。
 */
@Composable
fun UFIToolsTheme(
    paletteId: Int = 0,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = ThemePalettes.byId(paletteId)

    val pageBg = Color(if (dark) palette.pageBgDark else palette.pageBgLight)
    val cardBg = Color(if (dark) palette.cardBgDark else palette.cardBgLight)
    val textPrimary = Color(if (dark) palette.textPrimaryDark else palette.textPrimaryLight)
    val textSecondary = Color(if (dark) palette.textSecondaryDark else palette.textSecondaryLight)
    val accent = Color(if (dark) palette.accentDark else palette.accentLight)
    val accentSecondary = Color(if (dark) palette.accentSecondaryDark else palette.accentSecondaryLight)
    val divider = Color(if (dark) palette.dividerDark else palette.dividerLight)
    val btnBg = Color(if (dark) palette.btnBgDark else palette.btnBgLight)

    val scheme = if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = cardBg,
            onPrimaryContainer = textPrimary,
            secondary = accentSecondary,
            onSecondary = Color.White,
            background = pageBg,
            onBackground = textPrimary,
            surface = cardBg,
            onSurface = textPrimary,
            surfaceVariant = divider,
            onSurfaceVariant = textSecondary,
            outline = divider,
            error = Color(0xFFFF6B6B)
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = cardBg,
            onPrimaryContainer = textPrimary,
            secondary = accentSecondary,
            onSecondary = Color.White,
            background = pageBg,
            onBackground = textPrimary,
            surface = cardBg,
            onSurface = textPrimary,
            surfaceVariant = divider,
            onSurfaceVariant = textSecondary,
            outline = divider,
            error = Color(0xFFDC2626)
        )
    }

    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalIsDark provides dark
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            content = content
        )
    }
}
