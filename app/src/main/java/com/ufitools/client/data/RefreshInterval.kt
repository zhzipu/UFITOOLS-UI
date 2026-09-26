package com.ufitools.client.data

/**
 * 自动刷新间隔。
 *
 * [millis] 为 0 表示关闭自动刷新。
 */
enum class RefreshInterval(val millis: Long, val label: String) {
    OFF(0L, "关闭"),
    REALTIME(1_000L, "1 秒"),
    FAST(5_000L, "5 秒"),
    NORMAL(10_000L, "10 秒"),
    RELAXED(30_000L, "30 秒"),
    SLOW(60_000L, "1 分钟");

    val enabled: Boolean get() = millis > 0

    companion object {
        /** 默认 10 秒，兼顾实时性与设备负载。 */
        val DEFAULT = NORMAL

        fun byName(name: String?): RefreshInterval =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
