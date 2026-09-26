package com.ufitools.client.ui.theme

/**
 * 配色系统。
 *
 * 设计参考 UFITOOLS-Widget 的 ThemeColors.Palette：
 * 每套主题包含 21 个角色色值（浅色/深色各 9 个 + 独立数据高亮 2 个 + id/name）。
 *
 * 关键设计意图（沿用参考项目）：
 * - 卡片与页面分层：浅色模式卡片纯白、页面稍灰；深色模式卡片比页面更亮，用明度差营造层次
 * - 强调色在深色下提亮
 * - dataHighlight 独立于 accent，专供设备名与流量数值等「核心数据」，避免与交互色混淆
 */
data class Palette(
    val id: Int,
    val name: String,
    // 浅色
    val pageBgLight: Long,
    val cardBgLight: Long,
    val textPrimaryLight: Long,
    val textSecondaryLight: Long,
    val dividerLight: Long,
    val accentLight: Long,
    val accentSecondaryLight: Long,
    val btnBgLight: Long,
    val iconTintLight: Long,
    // 深色
    val pageBgDark: Long,
    val cardBgDark: Long,
    val textPrimaryDark: Long,
    val textSecondaryDark: Long,
    val dividerDark: Long,
    val accentDark: Long,
    val accentSecondaryDark: Long,
    val btnBgDark: Long,
    val iconTintDark: Long,
    // 核心数据高亮（设备名 / 流量数值专用）
    val dataHighlightLight: Long,
    val dataHighlightDark: Long
)

object ThemePalettes {

    val ALL: List<Palette> = listOf(
        Palette(
            id = 0,
            name = "默认",
            pageBgLight = 0xFFF8F8F8,
            cardBgLight = 0xFFFFFFFF,
            textPrimaryLight = 0xFF111111,
            textSecondaryLight = 0xFF444444,
            dividerLight = 0xFFE5E5E5,
            accentLight = 0xFF222222,
            accentSecondaryLight = 0xFFE5E5E5,
            btnBgLight = 0xFF222222,
            iconTintLight = 0xFF111111,
            pageBgDark = 0xFF1A1A1A,
            cardBgDark = 0xFF2A2A2A,
            textPrimaryDark = 0xFFEEEEEE,
            textSecondaryDark = 0xFFBBBBBB,
            dividerDark = 0xFF333333,
            accentDark = 0xFF555555,
            accentSecondaryDark = 0xFF555555,
            btnBgDark = 0xFF5A5A5A,
            iconTintDark = 0xFFEEEEEE,
            dataHighlightLight = 0xFF111111,
            dataHighlightDark = 0xFFFFFFFF
        ),
        Palette(
            id = 1,
            name = "科技蓝",
            pageBgLight = 0xFFF5F7FA,
            cardBgLight = 0xFFFFFFFF,
            textPrimaryLight = 0xFF1D2129,
            textSecondaryLight = 0xFF86909C,
            dividerLight = 0xFFE5E6EB,
            accentLight = 0xFF1677FF,
            accentSecondaryLight = 0xFF69B1FF,
            btnBgLight = 0xFF1677FF,
            iconTintLight = 0xFF1677FF,
            pageBgDark = 0xFF1D2939,
            cardBgDark = 0xFF263548,
            textPrimaryDark = 0xFFE8EDF2,
            textSecondaryDark = 0xFF86909C,
            dividerDark = 0xFF2A3A4E,
            accentDark = 0xFF0E5ACD,
            accentSecondaryDark = 0xFF69B1FF,
            btnBgDark = 0xFF0E5ACD,
            iconTintDark = 0xFF0E5ACD,
            dataHighlightLight = 0xFF1677FF,
            dataHighlightDark = 0xFF69B1FF
        ),
        Palette(
            id = 2,
            name = "薄荷绿",
            pageBgLight = 0xFFF7FCFA,
            cardBgLight = 0xFFFFFFFF,
            textPrimaryLight = 0xFF2C3631,
            textSecondaryLight = 0xFF7A9487,
            dividerLight = 0xFFE2EBE6,
            accentLight = 0xFF34C799,
            accentSecondaryLight = 0xFF90E4C3,
            btnBgLight = 0xFF34C799,
            iconTintLight = 0xFF34C799,
            pageBgDark = 0xFF1A2822,
            cardBgDark = 0xFF24332D,
            textPrimaryDark = 0xFFD8E8DF,
            textSecondaryDark = 0xFF7A9487,
            dividerDark = 0xFF2A3D33,
            accentDark = 0xFF34C799,
            accentSecondaryDark = 0xFF1B6B4E,
            btnBgDark = 0xFF228B55,
            iconTintDark = 0xFF34C799,
            dataHighlightLight = 0xFF34C799,
            dataHighlightDark = 0xFF90E4C3
        ),
        Palette(
            id = 3,
            name = "梦幻紫",
            pageBgLight = 0xFFF7F5FF,
            cardBgLight = 0xFFFFFFFF,
            textPrimaryLight = 0xFF3A3152,
            textSecondaryLight = 0xFF8A84B8,
            dividerLight = 0xFFEAE6FC,
            accentLight = 0xFF7B61FF,
            accentSecondaryLight = 0xFFB1A1FF,
            btnBgLight = 0xFF7B61FF,
            iconTintLight = 0xFF7B61FF,
            pageBgDark = 0xFF1A1630,
            cardBgDark = 0xFF272044,
            textPrimaryDark = 0xFFEAE6FF,
            textSecondaryDark = 0xFF8A84B8,
            dividerDark = 0xFF2A2540,
            accentDark = 0xFFB1A1FF,
            accentSecondaryDark = 0xFF5B46CC,
            btnBgDark = 0xFF5B46CC,
            iconTintDark = 0xFFB1A1FF,
            dataHighlightLight = 0xFF7B61FF,
            dataHighlightDark = 0xFFB1A1FF
        ),
        Palette(
            id = 4,
            name = "活力橙",
            pageBgLight = 0xFFFFF8F3,
            cardBgLight = 0xFFFFFFFF,
            textPrimaryLight = 0xFF3D2B20,
            textSecondaryLight = 0xFF997B69,
            dividerLight = 0xFFFFEDE0,
            accentLight = 0xFFFF7D34,
            accentSecondaryLight = 0xFFFFB989,
            btnBgLight = 0xFFFF7D34,
            iconTintLight = 0xFFFF7D34,
            pageBgDark = 0xFF241A15,
            cardBgDark = 0xFF2F221A,
            textPrimaryDark = 0xFFE8D8CC,
            textSecondaryDark = 0xFF997B69,
            dividerDark = 0xFF3A2A20,
            accentDark = 0xFFFF7D34,
            accentSecondaryDark = 0xFFB86020,
            btnBgDark = 0xFFCC5500,
            iconTintDark = 0xFFFF7D34,
            dataHighlightLight = 0xFFFF7D34,
            dataHighlightDark = 0xFFFFB989
        )
    )

    fun byId(id: Int): Palette = ALL.getOrElse(id) { ALL[0] }
}
