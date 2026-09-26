package com.ufitools.client.model

/**
 * 蜂窝网络模式优先级。
 *
 * 取值与文案**逐一来自设备自带 Web 控制台的下拉框**（`idx.html` 的 `#NET_TYPE`，
 * 其对应的写入逻辑见 `main.js#changeNetwork`），实测本机 `net_select = "WL_AND_5G"`。
 *
 * - 读取：goform `goform_get_cmd_process?cmd=net_select`（无需鉴权）
 * - 写入：goform `goform_set_cmd_process`，`goformId=SET_BEARER_PREFERENCE`、
 *   参数 `BearerPreference=<value>`（**需要登录 Cookie + AD 签名**）
 *
 * 注意：设备固件里第 6 项是 `Only_WCDMA`（**仅 3G**），并没有"仅 5G"这一档；
 * 「5G SA」对应的值就是 `Only_5G`。
 */
enum class NetworkMode(val value: String, val label: String) {
    /** 5G / 4G / 3G 自动（设备默认） */
    AUTO("WL_AND_5G", "5G/4G/3G"),

    /** 5G NSA（非独立组网，锚定在 4G 上） */
    NSA("LTE_AND_5G", "5G NSA"),

    /** 5G SA（独立组网） */
    SA("Only_5G", "5G SA"),

    /** 4G / 3G */
    LTE_3G("WCDMA_AND_LTE", "4G/3G"),

    /** 仅 4G */
    ONLY_LTE("Only_LTE", "仅4G"),

    /** 仅 3G */
    ONLY_3G("Only_WCDMA", "仅3G");

    companion object {
        /** 按设备返回的原始值匹配；未知值返回 null */
        fun byValue(value: String?): NetworkMode? {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return null
            return entries.firstOrNull { it.value.equals(v, ignoreCase = true) }
        }

        /**
         * 原始值 → 可读文案。设备可能返回未收录的值（不同固件/机型），
         * 此时原样显示而不是显示"-"，避免把真实状态藏起来。
         */
        fun labelOf(value: String?): String {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return "-"
            return byValue(v)?.label ?: v
        }
    }
}
