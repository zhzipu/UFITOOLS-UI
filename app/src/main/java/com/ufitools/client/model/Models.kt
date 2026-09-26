package com.ufitools.client.model

import android.util.Base64

/** 短信消息 */
data class SmsMessage(
    val id: String,
    val number: String,
    /** base64 编码的正文 */
    val content: String,
    /** 1=未读 3=发送失败 */
    val tag: String,
    val date: String
) {
    val isUnread: Boolean get() = tag == "1"
    val isSendFailed: Boolean get() = tag == "3"

    val decodedContent: String
        get() = runCatching {
            String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(content)

    val displayDate: String get() = formatSmsDate(date)
}

/** 日期形如 "2024,1,15,12,30,45"，去掉末位后格式化 */
fun formatSmsDate(raw: String): String {
    val parts = raw.split(',').filter { it.isNotBlank() }
    if (parts.size < 5) return raw.replace(',', '-')
    val y = parts[0].padStart(4, '0')
    val m = parts[1].padStart(2, '0')
    val d = parts[2].padStart(2, '0')
    val hh = parts[3].padStart(2, '0')
    val mm = parts[4].padStart(2, '0')
    return "$y-$m-$d $hh:$mm"
}

/**
 * 网络制式转可读名（信号类型，如 5G SA / 5G NSA / 4G）。
 *
 * 不同固件返回形式不统一，两种都要吃得下：
 * - 数字编号：U60Pro 之外的固件会给 `20`/`18`/`13`/`15`
 * - 可读字符串：本固件（U60Pro / BD_CNMU5250V1.0.0B31）实测直接返回 `"5G SA"`
 */
fun networkTypeLabel(type: String?): String {
    val t = type?.trim().orEmpty()
    if (t.isEmpty() || t == "0" || t.equals("unknown", ignoreCase = true) ||
        t == "-" || t.equals("null", ignoreCase = true)
    ) return "-"
    return when (t.uppercase()) {
        "20", "5G" -> "5G"
        "18", "5G NSA" -> "5G NSA"
        "5G SA" -> "5G SA"
        "13", "4G", "LTE" -> "4G"
        "15", "3G" -> "3G"
        "17", "2G", "GSM" -> "2G"
        else -> t
    }
}

/**
 * 运营商代号 → 中文名。
 *
 * 固件里通常有两个字段：`provider` 多为代号（`CUCC`/`CMCC`/`CTCC`），
 * `fullname` 多为英文全称（`China Unicom`）。两者都参与匹配，命中优先用中文名，
 * 未命中则退回可读全称、再退回代号，保证任何固件都有东西可显示。
 */
fun carrierLabel(provider: String?, fullname: String? = null): String {
    matchCarrier(provider, fullname)?.let { return it.name }
    // 未收录的运营商（境外卡等）：优先给可读全称，否则给代号原文
    val tokens = carrierTokens(provider, fullname)
    if (tokens.isEmpty()) return "-"
    return tokens.firstOrNull { it.length > 4 && it.any { ch -> ch.isLetter() } } ?: tokens.first()
}

/**
 * 运营商代号（`CMCC`/`CUCC`/`CTCC`/`CBN`），供界面挑对应的运营商标识用；未收录返回 null。
 *
 * 与 [carrierLabel] 共用同一张别名表，避免出现"名字认出来了、图标却退成兜底"的两边不一致。
 */
fun carrierCode(provider: String?, fullname: String? = null): String? =
    matchCarrier(provider, fullname)?.code

/** 常见运营商：中文名 / 代号 / 匹配别名（均按包含匹配，大小写不敏感） */
private data class CarrierEntry(val name: String, val code: String, val aliases: List<String>)

private val CARRIER_ENTRIES: List<CarrierEntry> = listOf(
    CarrierEntry(
        "中国移动", "CMCC",
        listOf("CMCC", "CHINA MOBILE", "CHN-CMCC", "46000", "46002", "46004", "46007", "46008")
    ),
    CarrierEntry(
        "中国联通", "CUCC",
        listOf("CUCC", "CHINA UNICOM", "CHN-UNICOM", "46001", "46006", "46009")
    ),
    CarrierEntry(
        "中国电信", "CTCC",
        listOf("CTCC", "CHINA TELECOM", "CHN-CT", "46003", "46005", "46011")
    ),
    CarrierEntry(
        "中国广电", "CBN",
        listOf("CBN", "CHINA BROADCASTING", "46015")
    )
)

/** 把 `provider` / `fullname` 归一成可匹配的 token，剔掉空值与各种「未知」占位 */
private fun carrierTokens(provider: String?, fullname: String?): List<String> =
    listOfNotNull(provider, fullname)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { t ->
            val u = t.uppercase()
            u == "UNKNOWN" || u == "UNK" || u == "N/A" || u == "NA" || u == "NULL" || u == "0" || u == "-"
        }

private fun matchCarrier(provider: String?, fullname: String?): CarrierEntry? {
    val tokens = carrierTokens(provider, fullname)
    if (tokens.isEmpty()) return null
    return CARRIER_ENTRIES.firstOrNull { entry ->
        tokens.any { tk -> entry.aliases.any { a -> tk.uppercase().contains(a) } }
    }
}

/**
 * 从若干候选字段里取第一个**有效**的 RSRP。
 *
 * 本固件拿不到数据时不是给 `null`，而是给 `0`（个别固件给正值），必须当成"没数据"，
 * 不能当真值用——否则无效的 `0` 会被当成极强的信号推成满格 5 格，
 * 这正是「信号格数永远 5 格」的成因之一。
 *
 * @param raw 形如 `live["Z5g_rsrp"]`、`live["lte_rsrp"]` 的原始字符串
 */
fun firstValidRsrp(vararg raw: String?): Double? =
    raw.firstNotNullOfOrNull { s ->
        s?.toDoubleOrNull()?.takeIf { it < 0 && it > -160 }
    }

/**
 * 解析 0..5 的信号格数（仪表盘 / 信号页 / 桌面小组件共用，避免三处判定漂移）。
 *
 * **不能优先取设备直给的 `network_signalbar`**：本固件（U60Pro / BD_CNMU5250V1.0.0B31）
 * 实测 `network_signalbar` 与 `signalbar` **恒为 `"5"`**——连续 6 次采样中 RSRP 在
 * -83 ~ -89 之间波动，这两个字段一动不动。把它当主判据的结果就是永远显示满格
 * （旧逻辑 `network_signalbar ?: signalBarsFromRsrp(...)` 正是如此）。
 *
 * 现在的优先级：
 * 1. RSRP 有效 → 按 [signalBarsFromRsrp] 推算（唯一随真实信号变化的来源）
 * 2. RSRP 取不到 → 才退回设备字段兜底
 *
 * @param rsrp 已用 [firstValidRsrp] 过滤过的 RSRP；null 表示无有效值
 * @param deviceBar 设备直给字段原文（`network_signalbar`），仅作兜底
 */
fun signalBarsOf(rsrp: Double?, deviceBar: String?): Int {
    if (rsrp != null) return signalBarsFromRsrp(rsrp)
    return deviceBar?.toIntOrNull()?.takeIf { it in 1..5 } ?: 0
}

/**
 * 依据 RSRP 推算 5 级信号格。
 *
 * 阈值采用 5G NR / LTE 通用的 RSRP 分档：
 *
 * | RSRP (dBm) | 格数 | 含义 |
 * |---|---|---|
 * | >= -80 | 5 | 极好 |
 * | -90 ~ -80 | 4 | 好 |
 * | -100 ~ -90 | 3 | 中 |
 * | -110 ~ -100 | 2 | 差 |
 * | < -110 | 1 | 极差 |
 *
 * 早期版本沿用参考项目 U60Pro-Widget 的宽松阈值（-85/-95/-105/-115），
 * 但真机实测 RSRP 常年在 -77 ~ -82 之间，宽松阈值下**这段信号全都算满格**，
 * 于是"永远 5 格"的观感并不会因为改用 RSRP 而改善。
 * 换成上表后，-82 这类"良好但不满格"的信号会如实显示 4 格。
 *
 * 仪表盘、信号页与桌面小组件共用，避免各处阈值不一致。
 */
fun signalBarsFromRsrp(rsrp: Double?): Int = when {
    rsrp == null -> 0
    rsrp >= -80 -> 5
    rsrp >= -90 -> 4
    rsrp >= -100 -> 3
    rsrp >= -110 -> 2
    else -> 1
}

/**
 * 温度格式化。
 *
 * UFI 的 `/api/baseDeviceInfo` 返回的 `cpu_temp` / `battery_temperature` 单位是**毫摄氏度**
 * （设备 Web 前端同样按 `cpu_temp / 1000` 展示）。容错：
 * - >= 1000000：按微摄氏度处理（个别固件）
 * - > 200      ：按毫摄氏度处理（正常情况，如 56500 → 56.5℃）
 * - 其余       ：视为已经是摄氏度（个别固件直出 "56"）
 */
fun formatTemp(raw: Double?): String {
    if (raw == null || raw <= 0) return "-"
    val celsius = when {
        raw >= 1_000_000 -> raw / 1_000_000.0
        raw > 200 -> raw / 1000.0
        else -> raw
    }
    return String.format(java.util.Locale.US, "%.1f℃", celsius)
}

/** 字节数转可读大小；0 明确显示 `0 B`（与「取值失败」的 `-` 区分开） */
fun bytesToHumanOrZero(bytes: Double?): String {
    if (bytes == null || bytes < 0) return "-"
    return if (bytes < 1.0) "0 B" else bytesToHuman(bytes.toLong())
}

/** 字节数转可读大小 */
fun bytesToHuman(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return "-"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return String.format("%.2f %s", v, units[i])
}

/** 毫秒开机时长转可读 */
fun uptimeHuman(millis: Long?): String {
    if (millis == null || millis <= 0) return "-"
    val totalSec = millis / 1000
    val d = totalSec / 86400
    val h = (totalSec % 86400) / 3600
    val m = (totalSec % 3600) / 60
    return buildString {
        if (d > 0) append("${d}天")
        if (h > 0 || d > 0) append("${h}小时")
        append("${m}分钟")
    }
}
