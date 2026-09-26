package com.ufitools.client.model

/**
 * 已连接的终端设备（WiFi 客户端 / 有线客户端）。
 *
 * 数据来源是**两个互补的接口**（实测 U60Pro / BD_CNMU5250V1.0.0B31）：
 *
 * 1. `goform_get_cmd_process?cmd=station_list,lan_station_list`（**无需鉴权**）
 *    给出 `hostname / ip_addr / mac_addr / conn_type / devtype / vendor`，
 *    但**不包含**流量与连接时长。
 * 2. `/api/run_shell` 执行 `iw dev <iface> station dump`
 *    给出每台终端的 `rx bytes` / `tx bytes` / `connected time` / `signal`，
 *    需要控制台口令 + 设备令牌。
 *
 * 两者按 MAC 合并。第 2 步失败时（固件无 `iw`、无 `run_shell`、权限不足等）
 * 仍展示列表，只有流量与连接时长显示 `-`。
 */
data class ClientDevice(
    /** MAC 地址，作为合并主键（比较时忽略大小写） */
    val mac: String,
    val ip: String,
    /** 设备上报的主机名，可能为空 */
    val hostname: String,
    /** `station_list` 的 conn_type，实测形如 "5G" / "2.4G" */
    val connType: String = "",
    val vendor: String = "",
    /** 设备侧**收到**的字节数，即该终端上传的累计流量 */
    val rxBytes: Long? = null,
    /** 设备侧**发出**的字节数，即该终端下载的累计流量 */
    val txBytes: Long? = null,
    /** 本次连接已持续的秒数（iw 的 connected time） */
    val connectedSeconds: Long? = null,
    /** 该终端的无线信号（dBm），有线设备为 null */
    val signalDbm: Int? = null,
    /** 该终端所在的无线接口（实测 wlan0=2.4G / wlan2=5G）；有线或未拿到时为 null */
    val iface: String? = null
) {
    val displayName: String get() = hostname.ifBlank { "未知设备" }

    /** 已用流量 = 上行 + 下行；两项都拿不到时为 null（界面显示 `-`） */
    val usedBytes: Long?
        get() = if (rxBytes == null && txBytes == null) null else (rxBytes ?: 0L) + (txBytes ?: 0L)

    val usedText: String
        get() {
            val u = usedBytes ?: return "-"
            return if (u <= 0L) "0 B" else bytesToHuman(u)
        }

    val connectedText: String get() = secondsToHuman(connectedSeconds)

    /** 频段标记，如 "5G" / "2.4G"；无信息时返回空串 */
    val bandLabel: String
        get() = when {
            connType.isBlank() -> ""
            connType.contains("5", true) -> "5G"
            connType.contains("2.4", true) -> "2.4G"
            else -> connType
        }
}

/**
 * iw station dump 中单个终端的统计数据。
 *
 * @param iface 该终端所在的无线接口（如 wlan0 / wlan2）
 */
data class StationStat(
    val iface: String,
    val rxBytes: Long,
    val txBytes: Long,
    val connectedSeconds: Long,
    val signalDbm: Int?
)

/**
 * 一次读取设备上**所有**无线接口的终端统计。
 *
 * `iw dev` 的接口名不可假定（本机实测为 wlan0 / wlan2，另有固件用 wlan0/wlan1），
 * 所以用 `iw dev` 动态取接口名再逐个 dump，避免写死。
 */
const val IW_STATION_DUMP_CMD: String =
    "for i in \$(iw dev | awk '/Interface/{print \$2}'); do iw dev \$i station dump; done"

/**
 * 解析 `iw dev <iface> station dump` 的输出，按**小写 MAC** 聚合。
 *
 * 输出形如（字段间为制表符，缩进不固定）：
 * ```
 * Station 10:3c:59:5b:aa:1a (on wlan2)
 *     inactive time:  1524 ms
 *     rx bytes:       141390103
 *     tx bytes:       985826672
 *     signal:         -35 dBm
 *     connected time: 13271 seconds
 * ```
 * 解析失败或字段缺失时按 0 / null 处理，不抛异常——调用方只依赖"尽力而为"。
 */
fun parseIwStationDump(text: String): Map<String, StationStat> {
    val out = LinkedHashMap<String, StationStat>()
    var mac: String? = null
    var iface = ""
    var rx = 0L
    var tx = 0L
    var connected = 0L
    var signal: Int? = null

    fun flush() {
        val m = mac ?: return
        out[m] = StationStat(iface, rx, tx, connected, signal)
    }

    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        when {
            line.startsWith("Station ") -> {
                flush()
                mac = line.removePrefix("Station ").trim().substringBefore(' ').lowercase()
                iface = IFACE_RE.find(line)?.groupValues?.getOrNull(1) ?: ""
                rx = 0L
                tx = 0L
                connected = 0L
                signal = null
            }
            // "inactive time:" 不含 "connected"，但用精确前缀避免误吞
            line.startsWith("connected time:") ->
                connected = line.substringAfter(':').filter { it.isDigit() }.toLongOrNull() ?: 0L
            line.startsWith("rx bytes:") ->
                rx = line.substringAfter(':').filter { it.isDigit() }.toLongOrNull() ?: 0L
            line.startsWith("tx bytes:") ->
                tx = line.substringAfter(':').filter { it.isDigit() }.toLongOrNull() ?: 0L
            line.startsWith("signal:") ->
                signal = INT_RE.find(line.substringAfter(':'))?.value?.toIntOrNull()
        }
    }
    flush()
    return out
}

private val IFACE_RE = Regex("\\(on ([^)]+)\\)")
private val INT_RE = Regex("-?\\d+")

/** 合法 MAC（小写、冒号分隔），用于过滤来自设备端的数据，避免拼进 shell 命令 */
private val MAC_RE = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

/** 合法无线接口名，同样用于防注入 */
private val IFACE_NAME_RE = Regex("^[A-Za-z0-9_.-]{1,15}$")

/**
 * 生成「踢出该终端」的 shell 命令；MAC 非法时返回 null（调用方忽略即可）。
 *
 * 设备本身**没有**"断开指定客户端"的 goform/api —— 原厂 Web 控制台在设备管理里
 * 只提供黑名单（`setDeviceAccessControlList`）。但设备 root 权限下可以执行
 * `iw dev <iface> station del <MAC> subtype 0xC`，向该终端发送 deauthentication 帧，
 * 直接把它的无线连接踢掉（`iw ... station del` 已实测存在于该固件）。
 *
 * @param iface 该终端所在的无线接口（来自 `iw station dump`）。给了就先踢它，
 *              再兜底遍历全部接口——终端可能在 2.4G/5G 之间切过频段，只按旧接口踢会踢空。
 *
 * ⚠️ 被踢的终端通常会**自动重连**（AP 并未把它拉黑），这是预期行为。
 * 若需要"永久踢走"，得走 goform 黑名单。
 */
fun kickClientCmd(mac: String, iface: String? = null): String? {
    val safe = mac.trim().lowercase()
    if (!MAC_RE.matches(safe)) return null
    val known = iface?.trim()?.takeIf { IFACE_NAME_RE.matches(it) }
    val all = "\$(iw dev | awk '/Interface/{print \$2}')"
    val devs = if (known == null) all else "$known $all"
    return "for i in $devs; do iw dev \$i station del $safe subtype 0xC 2>/dev/null; done"
}

/** 秒 → 可读时长。短时长优先显示秒/分钟，避免出现 "0分钟"。 */
fun secondsToHuman(seconds: Long?): String {
    if (seconds == null || seconds <= 0L) return "-"
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        d > 0 || h > 0 -> buildString {
            if (d > 0) append("${d}天")
            append("${h}小时")
            append("${m}分钟")
        }
        m > 0 -> "${m}分钟"
        else -> "${s}秒"
    }
}
