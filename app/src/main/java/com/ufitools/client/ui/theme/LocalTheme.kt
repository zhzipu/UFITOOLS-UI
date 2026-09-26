package com.ufitools.client.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** 当前激活的配色方案 */
val LocalPalette = staticCompositionLocalOf { ThemePalettes.ALL[0] }

/** 当前是否为深色模式 */
val LocalIsDark = staticCompositionLocalOf { false }

/** 主题模式：跟随系统 / 浅色 / 深色 */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
