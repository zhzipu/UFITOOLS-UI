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
 * 校验用户手输的 MAC 是否合法（黑名单编辑页用）。
 *
 * 宽容处理常见的几种写法：大小写混写、连字符分隔（`AA-BB-CC-DD-EE-FF`）。
 * 合法则返回**规范化的冒号小写形式**，否则返回 null。
 */
fun normalizeMac(input: String): String? {
    val raw = input.trim().lowercase().replace('-', ':').replace(" ", "")
    if (!MAC_RE.matches(raw)) return null
    return raw
}

/** 该接口名是否可用于拼 shell 命令 */
fun isValidIface(name: String): Boolean = IFACE_NAME_RE.matches(name.trim())

/**
 * 设备接入控制（黑名单）状态。
 *
 * 契约全部来自**实测**（U60Pro / BD_CNMU5250V1.0.0B31），不是推测：
 *
 * ## 读取
 * `GET goform_get_cmd_process?cmd=station_list,lan_station_list,queryDeviceAccessControlList,hostNameList&isTest=false&_=<ts>`
 * + 登录 Cookie，**不能带 `multi_data=1`**。
 * `queryDeviceAccessControlList` 是触发器，不带上它这三个字段就不返回（详见 GoformClient）。
 *
 * ## 写入
 * `POST goformId=setDeviceAccessControlList`，字段：
 * `AclMode` / `WhiteMacList`(空) / `BlackMacList` / `WhiteNameList`(空) / `BlackNameList`。
 *
 * ## ⚠️ 三条必须知道的实测行为
 * 1. **`AclMode` 是设备自算的状态，不是你要设的模式**：
 *    黑名单为空时它读出来是 `"0"`，**一旦拉黑了任何设备就自动变成 `"1"`**，
 *    清空后自动回到 `"0"`。所以原厂 Web 端只是"读到什么就原样写回"。
 *    这里沿用同样做法（[aclMode] 仅在读到时透传，未读到时传 `"0"`）。
 * 2. **设备会对 `BlackMacList` 重新排序**（实测传 `aa:..;11:..`，回读变 `11:..;aa:..`）。
 *    因此**必须以回读结果为唯一事实来源**，不能假设写进去的顺序。
 * 3. **`BlackNameList` 与 `BlackMacList` 不是可靠的同序对应**：
 *    实测写入 `aa:..;11:..` + 名称 `测试机;第二台`，回读得到
 *    `BlackMacList="11:..;aa:.."` 而 `BlackNameList=";"` —— 中文名直接丢失。
 *    **结论：`BlackNameList` 不可信，界面上一律用 MAC 作为主键与标题。**
 */
data class AclState(
    /** 接入控制模式。设备自算：空黑名单=`0`，有黑名单=`1`。**不要自行编造语义。** */
    val aclMode: String = "0",
    /** 黑名单 MAC 列表（设备回读顺序，可能是设备重排过的） */
    val blackMacs: List<String> = emptyList(),
    /** 黑名单名称列表。⚠️ 设备端可能丢内容且与 MAC 不同序，**仅作参考，不可作主键**。 */
    val blackNames: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = blackMacs.isEmpty()

    /**
     * 黑名单条目：MAC 与名称配对。
     *
     * ⚠️ 名称只在下标能对上时才有意义（设备不同序/丢名），
     * 界面应优先显示 MAC，名称仅作辅助。
     */
    val entries: List<Pair<String, String>>
        get() = blackMacs.mapIndexed { i, mac -> mac to (blackNames.getOrNull(i) ?: "") }

    fun containsMac(mac: String): Boolean =
        blackMacs.any { it.equals(mac.trim(), ignoreCase = true) }

    companion object {
        /** 设备端的列表分隔符 */
        const val SEP = ";"

        /** 把设备返回的分号串解析为列表（去空、去首尾空格） */
        fun parseList(raw: String?): List<String> =
            raw?.split(SEP)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        /**
         * 追加一条黑名单并去重（MAC 大小写不敏感）。
         *
         * 与设备端 `[mac_addr, ...blackMacList]` 的语义一致。
         * 名称列表按 MAC 的下标同步维护，但**不要指望设备会保留它**（见类注释第 3 条）。
         */
        fun withEntry(state: AclState, mac: String, name: String): AclState {
            val m = mac.trim()
            if (m.isEmpty()) return state
            val entries = state.entries.toMutableList()
            val existing = entries.indexOfFirst { it.first.equals(m, ignoreCase = true) }
            if (existing >= 0) {
                if (name.isNotBlank()) entries[existing] = entries[existing].first to name.trim()
            } else {
                entries.add(m to name.trim())
            }
            return state.copy(
                blackMacs = entries.map { it.first },
                blackNames = entries.map { it.second }
            )
        }

        /** 移除一条黑名单（MAC 大小写不敏感），名称列表同步删除对应下标 */
        fun withoutMac(state: AclState, mac: String): AclState {
            val idx = state.blackMacs.indexOfFirst { it.equals(mac.trim(), ignoreCase = true) }
            if (idx < 0) return state
            val macs = state.blackMacs.toMutableList().also { it.removeAt(idx) }
            val names = state.blackNames.toMutableList().also {
                if (idx < it.size) it.removeAt(idx)
            }
            return state.copy(blackMacs = macs, blackNames = names)
        }

        /** 序列化为设备端要的分号串 */
        fun join(list: List<String>): String = list.joinToString(SEP)
    }
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
