package com.ufitools.client.data

import android.content.Context
import android.content.res.Configuration

/** 主题偏好（配色 ID + 明暗模式）的本地持久化 */
class ThemeStore(context: Context) {

    private val prefs = context.getSharedPreferences("ufi_client_theme", Context.MODE_PRIVATE)

    /**
     * 应用 Context。
     *
     * 小组件侧拿到的是 `RemoteViews` 用的 Context，**必须靠它取 `uiMode`**：
     * 这样 `ThemeStore` 自己就能算明暗，不必让调用方再传一遍系统夜间标志。
     */
    private val appContext: Context = context.applicationContext

    /** 配色 ID：0 默认 / 1 科技蓝 / 2 薄荷绿 / 3 梦幻紫 / 4 活力橙 */
    fun loadPaletteId(): Int = prefs.getInt("color_theme", 1)

    fun savePaletteId(id: Int) {
        prefs.edit().putInt("color_theme", id).apply()
    }

    /** 主题模式：system / light / dark */
    fun loadThemeMode(): String = prefs.getString("app_theme", "system") ?: "system"

    fun saveThemeMode(mode: String) {
        prefs.edit().putString("app_theme", mode).apply()
    }

    /**
     * 当前是否应当使用深色。
     *
     * 与 [com.ufitools.client.ui.theme.UFIToolsTheme] 里的判定**必须保持一致**：
     * 小组件不在 Compose 树里，用不了 `isSystemInDarkTheme()`，
     * 只能自己读 `Configuration.uiMode` —— 两边算法一旦分叉，桌面卡片就会和 App 内配色不一致。
     */
    fun resolveIsDark(): Boolean = when (loadThemeMode().lowercase()) {
        "dark" -> true
        "light" -> false
        else -> {
            val mode = appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            mode == Configuration.UI_MODE_NIGHT_YES
        }
    }
}
