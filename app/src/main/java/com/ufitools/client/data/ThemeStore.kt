package com.ufitools.client.data

import android.content.Context

/** 主题偏好（配色 ID + 明暗模式）的本地持久化 */
class ThemeStore(context: Context) {

    private val prefs = context.getSharedPreferences("ufi_client_theme", Context.MODE_PRIVATE)

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
}
