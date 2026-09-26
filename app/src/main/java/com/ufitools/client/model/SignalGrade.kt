package com.ufitools.client.model

/**
 * 信号评估等级。
 *
 * 把「几格」换成人话。阈值**沿用** [signalBarsFromRsrp] 的 5G NR / LTE 通用 RSRP 分档
 * （-80 / -90 / -100 / -110），只是叫法不同：
 *
 * | RSRP (dBm) | 等级 | 格数 |
 * |---|---|---|
 * | >= -80 | 优秀 | 5 |
 * | -90 ~ -80 | 良好 | 4 |
 * | -100 ~ -90 | 一般 | 3 |
 * | -110 ~ -100 | 糟糕 | 2 |
 * | < -110 或无数据 | 不可用 | 0 |
 *
 * 注：[NONE] 同时吸收「极差（< -110）」与「读不到 RSRP」两种情况，
 * 都归为「不可用」——对用户来说这两种情况要做的事是一样的（换个位置试试）。
 *
 * 颜色属于 UI 层（见 SignalScreen 的 `gradeColor`），本文件保持不依赖 Compose。
 */
enum class SignalGrade(val label: String, val bars: Int) {
    EXCELLENT("优秀", 5),
    GOOD("良好", 4),
    FAIR("一般", 3),
    POOR("糟糕", 2),
    NONE("不可用", 0);

    companion object {

        /**
         * 按 RSRP 判定等级。
         *
         * @param rsrp 已用 `firstValidRsrp` 过滤过的 RSRP；null 表示无有效值 → 不可用
         */
        fun from(rsrp: Double?): SignalGrade {
            if (rsrp == null) return NONE
            return when {
                rsrp >= -80 -> EXCELLENT
                rsrp >= -90 -> GOOD
                rsrp >= -100 -> FAIR
                rsrp >= -110 -> POOR
                else -> NONE
            }
        }
    }
}
