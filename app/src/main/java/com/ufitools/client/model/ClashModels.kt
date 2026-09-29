package com.ufitools.client.model

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * Clash / Mihomo 外部控制器（external-controller）数据模型。
 *
 * 与设备自研 `/api/` 那套完全无关：这里是**内核标准 REST API**，
 * 由 mihomo 自己监听（默认 9090），地址与设备 goform 端口不同，
 * 认证方式是 `Authorization: Bearer <secret>` 而不是 kano-sign。
 *
 * 之所以全部用 Gson 手工取值而不是 data class 反序列化：
 * 内核版本间字段增删频繁（`extra` / `expectedStatus` / `provider-name` 都是后来才有的），
 * 手工取值可以只挑界面真正需要的字段，缺字段时给合理默认值，不会整条解析失败。
 */
data class ClashVersion(
    /** 是否 Meta 内核（mihomo） */
    val meta: Boolean = false,
    /** 形如 `v1.18.1` */
    val version: String = "",
) {
    val display: String get() = if (version.isBlank()) "-" else version
}

/**
 * 一条代理记录。策略组（Selector / URLTest / Fallback / LoadBalance）
 * 会在 [all] 里带上候选成员、在 [now] 里带上当前选中项。
 */
data class ClashProxy(
    val name: String,
    val type: String,
    val alive: Boolean = true,
    /** 最近一次测得的延迟（毫秒）；0 或负数表示未测/失败 */
    val delay: Int = 0,
    /** 策略组当前选中成员；非策略组为空 */
    val now: String = "",
    /** 策略组候选成员（含嵌套子组） */
    val all: List<String> = emptyList(),
    val udp: Boolean = false,
    /** 策略组健康检查地址 */
    val testUrl: String = "",
    /** 面板要求隐藏的节点（如 `hidden`），列表里过滤掉 */
    val hidden: Boolean = false,
    /** 所属代理集名称，空串表示来自配置文件 */
    val providerName: String = "",
) {
    /** 是否是可在界面切换成员的策略组 */
    val isGroup: Boolean
        get() = type.equals("Selector", ignoreCase = true) ||
            type.equals("URLTest", ignoreCase = true) ||
            type.equals("Fallback", ignoreCase = true) ||
            type.equals("LoadBalance", ignoreCase = true)

    /** 是否是可被选中的真实节点（排除 DIRECT / REJECT 这类内置项） */
    val isSelectable: Boolean
        get() = !name.equals("DIRECT", true) &&
            !name.equals("REJECT", true) &&
            !name.equals("PASS", true) &&
            !name.equals("COMPATIBLE", true)

    /** 延迟文案：未测 / 超时 / `123 ms` */
    val delayText: String
        get() = when {
            delay <= 0 -> "-"
            else -> "$delay ms"
        }
}

/** 内核当前运行配置（`GET /configs`）里界面用得上的部分 */
data class ClashRuntimeConfig(
    /** `rule` / `global` / `direct` */
    val mode: String = "",
    val logLevel: String = "",
    val mixedPort: Int = 0,
    val port: Int = 0,
    /** TUN 是否开启；部分版本该字段是对象 */
    val tunEnabled: Boolean = false,
    val allowLan: Boolean = false,
    val ipv6: Boolean = false,
) {
    val modeLabel: String
        get() = when (mode.lowercase()) {
            "rule" -> "规则"
            "global" -> "全局"
            "direct" -> "直连"
            else -> mode.ifBlank { "-" }
        }
}

/** 一条活动连接（`GET /connections`） */
data class ClashConnection(
    val id: String,
    val host: String,
    val destinationIp: String,
    val sourceIp: String,
    val network: String,
    val type: String,
    val rule: String,
    val rulePayload: String,
    val chains: List<String>,
    val upload: Long,
    val download: Long,
    val start: Long,
    /** 进程名（部分内核的 metadata.process 会带，Android 上常为空） */
    val process: String = "",
    /**
     * 实时速率（字节/秒）。
     *
     * ⚠️ 内核**不返回**这个字段，是 App 侧用两次 `/connections` 快照的
     * 增量除以时间差算出来的，见 [rateOf]。只做展示，不参与排序。
     */
    val uploadSpeed: Long = 0L,
    val downloadSpeed: Long = 0L,
) {
    /** 界面主标题：优先显示域名，没有就显示目标 IP */
    val title: String
        get() = host.ifBlank { destinationIp.ifBlank { "-" } }

    /** 命中的策略链，形如 `节点 ← 组 ← 组` */
    val chainsText: String
        get() = chains.reversed().joinToString(" ← ")

    /** 规则展示，形如 `DOMAIN-SUFFIX,google.com` */
    val ruleText: String
        get() = if (rulePayload.isBlank()) rule else "$rule,$rulePayload"

    val uploadText: String get() = bytesToHumanOrZero(upload.toDouble())
    val downloadText: String get() = bytesToHumanOrZero(download.toDouble())
    val total: Long get() = upload + download

    /** 速率文案，形如 `1.2 MB/s`；为 0 时显示 `-` */
    val downloadSpeedText: String
        get() = if (downloadSpeed > 0) "${bytesToHumanOrZero(downloadSpeed.toDouble())}/s" else "-"
    val uploadSpeedText: String
        get() = if (uploadSpeed > 0) "${bytesToHumanOrZero(uploadSpeed.toDouble())}/s" else "-"
}

/** 连接汇总（`GET /connections` 的顶层字段） */
data class ClashConnectionSummary(
    val downloadTotal: Long = 0,
    val uploadTotal: Long = 0,
    val memory: Long = 0,
    val connections: List<ClashConnection> = emptyList(),
)

/** 代理集（`GET /providers/proxies`），用于展示订阅剩余流量与到期时间 */
data class ClashProvider(
    val name: String,
    val vehicleType: String,
    /** 上次更新时间戳（毫秒） */
    val updatedAt: Long = 0,
    val subscriptionInfo: ClashSubscriptionInfo? = null,
)

/** 订阅信息（机场下发的流量/到期数据，字段随订阅方而异） */
data class ClashSubscriptionInfo(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    /** 到期时间戳（毫秒） */
    val expire: Long = 0,
) {
    /** 已用流量 = 上传 + 下载 */
    val used: Long get() = upload + download

    /** 用量占比 0f~1f；总量未知时返回 0 */
    val ratio: Float
        get() = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)

    val usedText: String get() = bytesToHumanOrZero(used.toDouble())
    val totalText: String get() = bytesToHumanOrZero(total.toDouble())
    val remainText: String get() = bytesToHumanOrZero((total - used).coerceAtLeast(0L).toDouble())
}

// ================================================================== 解析

/** `JsonObject` 安全取字符串：null / JsonNull / 非原始值都不会抛 */
private fun JsonObject.str(key: String): String =
    get(key)?.let { el ->
        when {
            el.isJsonNull -> ""
            el.isJsonPrimitive -> (el as JsonPrimitive).asString
            else -> ""
        }
    } ?: ""

private fun JsonObject.boolean(key: String): Boolean =
    get(key)?.let { el ->
        when {
            el.isJsonNull -> false
            el.isJsonPrimitive -> (el as JsonPrimitive).let { p ->
                if (p.isBoolean) p.asBoolean else p.asString == "1" || p.asString.equals("true", true)
            }
            else -> false
        }
    } ?: false

private fun JsonObject.int(key: String): Int =
    get(key)?.let { el ->
        when {
            el.isJsonNull -> 0
            el.isJsonPrimitive -> runCatching { (el as JsonPrimitive).asInt }.getOrDefault(0)
            else -> 0
        }
    } ?: 0

private fun JsonObject.long(key: String): Long =
    get(key)?.let { el ->
        when {
            el.isJsonNull -> 0L
            el.isJsonPrimitive -> runCatching { (el as JsonPrimitive).asLong }.getOrDefault(0L)
            else -> 0L
        }
    } ?: 0L

/** 取 `history` 里最后一条可用延迟；内核每次测速会把结果追加进去 */
private fun JsonObject.latestDelay(): Int {
    val arr = get("history")?.takeIf { it.isJsonArray }?.asJsonArray ?: return 0
    var last = 0
    // 从后往前找第一条 delay > 0 的记录：失败的测速会写 0，不能当结果用
    for (i in arr.size() - 1 downTo 0) {
        val o = arr.get(i)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
        val d = o.int("delay")
        if (d > 0) {
            last = d
            break
        }
    }
    return last
}

private fun parseStringArray(el: com.google.gson.JsonElement?): List<String> {
    val arr = el?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return arr.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
}

/** 解析 `GET /version` */
fun parseClashVersion(root: JsonObject): ClashVersion = ClashVersion(
    meta = root.boolean("meta"),
    version = root.str("version"),
)

/** 解析 `GET /configs` */
fun parseClashConfig(root: JsonObject): ClashRuntimeConfig {
    // `tun` 在新版是对象 {enable:bool}，老版是布尔，两种都吃
    val tunEl = root.get("tun")
    val tun = when {
        tunEl == null || tunEl.isJsonNull -> false
        tunEl.isJsonObject -> tunEl.asJsonObject.boolean("enable")
        else -> root.boolean("tun")
    }
    return ClashRuntimeConfig(
        mode = root.str("mode"),
        logLevel = root.str("log-level"),
        mixedPort = root.int("mixed-port"),
        port = root.int("port"),
        tunEnabled = tun,
        allowLan = root.boolean("allow-lan"),
        ipv6 = root.boolean("ipv6"),
    )
}

/**
 * 解析 `GET /proxies`。
 *
 * 返回顺序：先出策略组（用户最常操作），再出节点；组内保持内核给的顺序。
 * [hidden] 为 true 的条目不返回。
 */
fun parseClashProxies(root: JsonObject): List<ClashProxy> {
    val map = root.get("proxies")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    val out = ArrayList<ClashProxy>(map.size())
    map.entrySet().forEach { (key, el) ->
        val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
        val name = o.str("name").ifBlank { key }
        val hidden = o.boolean("hidden")
        if (hidden) return@forEach
        val allSummary = parseStringArray(o.get("all"))
        // 顶层 `delay` 字段部分版本不返回，退回到 history 最后一条
        val delay = o.int("delay").takeIf { it > 0 } ?: o.latestDelay()
        out += ClashProxy(
            name = name,
            type = o.str("type"),
            alive = if (o.has("alive")) o.boolean("alive") else true,
            delay = delay,
            now = o.str("now"),
            all = allSummary,
            udp = o.boolean("udp"),
            testUrl = o.str("testUrl").ifBlank { o.str("test-url") },
            hidden = hidden,
            providerName = o.str("provider-name"),
        )
    }
    // 策略组排前面：instrumentation 顺序对界面很重要，重排一次
    return out.sortedByDescending { if (it.isGroup) 1 else 0 }
}

/** 解析 `GET /connections` */
fun parseClashConnections(root: JsonObject): ClashConnectionSummary {
    val downloadTotal = root.long("downloadTotal")
    val uploadTotal = root.long("uploadTotal")
    val memory = root.long("memory")
    val arr = root.get("connections")?.takeIf { it.isJsonArray }?.asJsonArray
        ?: return ClashConnectionSummary(downloadTotal, uploadTotal, memory, emptyList())

    val list = arr.mapNotNull { el ->
        val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val meta = o.get("metadata")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        ClashConnection(
            id = o.str("id"),
            host = meta.str("host"),
            destinationIp = meta.str("destinationIP").ifBlank { meta.str("destinationIp") },
            sourceIp = meta.str("sourceIP").ifBlank { meta.str("sourceIp") },
            network = meta.str("network"),
            type = meta.str("type"),
            rule = o.str("rule"),
            rulePayload = o.str("rulePayload"),
            chains = parseStringArray(o.get("chains")),
            upload = o.long("upload"),
            download = o.long("download"),
            start = parseStartTime(o.str("start")),
        )
    }.sortedByDescending { it.total }

    return ClashConnectionSummary(downloadTotal, uploadTotal, memory, list)
}

/**
 * 连接开始时间。
 *
 * 内核给的是 RFC3339（`2026-09-28T21:11:12.3456789+08:00`），
 * 直接 `SimpleDateFormat` 解析纳秒会失败，故这里只截到毫秒再解析；失败返回 0。
 */
private fun parseStartTime(raw: String): Long {
    if (raw.isBlank()) return 0L
    return runCatching {
        val normalized = raw
            // 去掉超长小数位：保留到 3 位毫秒
            .replace(Regex("(\\.\\d{3})\\d*"), "$1")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US)
        fmt.parse(normalized)?.time ?: 0L
    }.getOrDefault(0L)
}

/**
 * 合并两个节点来源：`/proxies` 顶层 + 各代理集下的节点。
 *
 * **顶层优先**：顶层那份带 `now`（策略组当前选中）、`testUrl` 等只有顶层
 * 才给的字段，被 provider 版本覆盖会丢信息；且顶层已经包含内置项
 * （`DIRECT` / `REJECT` / `GLOBAL` / `PROXY`），这些在 provider 里没有。
 *
 * 输出顺序沿用「策略组在前、节点在后」——[parseClashProxies] 已经排好，
 * 这里只追加 provider 节点，不重排，避免把组的顺序打乱。
 */
fun mergeClashProxies(
    topLevel: List<ClashProxy>,
    providerNodes: List<ClashProxy>,
): List<ClashProxy> {
    if (providerNodes.isEmpty()) return topLevel
    val known = topLevel.mapTo(HashSet(topLevel.size)) { it.name }
    val extra = providerNodes.filter { it.name.isNotBlank() && known.add(it.name) }
    return if (extra.isEmpty()) topLevel else topLevel + extra
}

/** 把连接列表按「按流量排序」用于展示；已在上游排好，这里只是显式声明语义 */
fun List<ClashConnection>.sortedByTraffic(): List<ClashConnection> = sortedByDescending { it.total }

/**
 * 把 `GET /providers/proxies` 里各个代理集下的**节点**取出来，输出成 [ClashProxy]。
 *
 * ## 为什么必须合并这一步
 *
 * 实机踩到的坑（mihomo v1.19.31）：**`/proxies` 顶层只列内置项与策略组，
 * 不列代理集提供的节点**。实测：
 *
 * ```
 * /proxies 顶层        → 8 个（DIRECT / REJECT / GLOBAL / PROXY …）
 * /providers/proxies   → 46 个节点（机场1 的 42 个 + 其他）
 * PROXY 组的 all 名单   → 43 个名字
 * ```
 *
 * 只读 `/proxies` 的话，策略组那 43 个成员里只有 `DIRECT` 能找到实体，
 * UI 上表现为「共 43 个成员（可切换 1 个）」——**节点列表只出一张卡，
 * 而实际上 42 个节点全都可用**。合并两个来源后 43 个成员 100% 命中。
 *
 * ## 为什么单独一个函数，不塞进 [ClashProvider]
 *
 * 订阅页只关心 `name` / 流量 / 到期时间，用不上动辄上百条的节点列表。
 * 把节点挂在 [ClashProvider] 上会让订阅列表也跟着解析一遍大对象，
 * 白白增加订阅页的解析开销。这里保持职责分离：元数据归 [parseClashProviders]，
 * 节点归本函数，调用方按需取用。
 *
 * ## 同名冲突
 *
 * 顶层已有的名字（内置项、策略组）优先，provider 里的同名节点不覆盖——
 * 顶层那份带 `now`（策略组）等只有顶层才有的字段，覆盖会丢信息。
 */
fun parseClashProviderNodes(root: JsonObject): List<ClashProxy> {
    val map = root.get("providers")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    val out = ArrayList<ClashProxy>()
    val seen = HashSet<String>()
    map.entrySet().forEach { (key, el) ->
        val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
        // provider 名字用于回填 providerName，让界面能显示节点来源
        val providerName = o.str("name").ifBlank { key }
        val arr = o.get("proxies")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEach
        arr.forEach { ne ->
            val n = ne.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val name = n.str("name")
            if (name.isBlank() || !seen.add(name)) return@forEach
            if (n.boolean("hidden")) return@forEach
            // 顶层 delay 常缺失，回退 history 最后一条（与 parseClashProxies 同一策略）
            val delay = n.int("delay").takeIf { it > 0 } ?: n.latestDelay()
            out += ClashProxy(
                name = name,
                type = n.str("type"),
                alive = if (n.has("alive")) n.boolean("alive") else true,
                delay = delay,
                now = n.str("now"),
                all = parseStringArray(n.get("all")),
                udp = n.boolean("udp"),
                testUrl = n.str("testUrl").ifBlank { n.str("test-url") },
                hidden = false,
                // provider 里的节点自己可能不带 provider-name，用所属集合名兜底
                providerName = n.str("provider-name").ifBlank { providerName },
            )
        }
    }
    return out
}

/** 解析 `GET /providers/proxies` 里的订阅信息 */
fun parseClashProviders(root: JsonObject): List<ClashProvider> {
    val map = root.get("providers")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    return map.entrySet().mapNotNull { (key, el) ->
        val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val sub = o.get("subscriptionInfo")?.takeIf { it.isJsonObject }?.asJsonObject
        ClashProvider(
            name = o.str("name").ifBlank { key },
            vehicleType = o.str("vehicleType"),
            updatedAt = parseProviderTime(o.str("updatedAt")),
            subscriptionInfo = sub?.let {
                ClashSubscriptionInfo(
                    upload = it.long("Upload").takeIf { v -> v > 0 } ?: it.long("upload"),
                    download = it.long("Download").takeIf { v -> v > 0 } ?: it.long("download"),
                    total = it.long("Total").takeIf { v -> v > 0 } ?: it.long("total"),
                    expire = parseProviderTime(
                        it.str("Expire").ifBlank { it.str("expire") }
                    ),
                )
            }
        )
    }
}

/**
 * 代理集的更新时间格式不统一：有的是秒级数字字符串，有的是 RFC3339。
 * 统一成毫秒时间戳；无法识别返回 0。
 */
private fun parseProviderTime(raw: String): Long {
    if (raw.isBlank()) return 0L
    raw.toLongOrNull()?.let { v ->
        if (v <= 0L) return 0L
        // 小于 1e12 视为秒级
        return if (v < 1_000_000_000_000L) v * 1000 else v
    }
    return runCatching {
        val normalized = raw.replace(Regex("(\\.\\d{3})\\d*"), "$1")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US)
        fmt.parse(normalized)?.time ?: 0L
    }.getOrDefault(0L)
}

// ================================================================== 规则

/**
 * 一条分流规则（`GET /rules`）。
 *
 * ## 实测报文（mihomo v1.19.31）
 *
 * ```json
 * {"rules":[{"index":0,"type":"Match","payload":"","proxy":"PROXY","size":-1,
 *   "extra":{"disabled":false,"hitCount":789,"hitAt":"...","missCount":0}}]}
 * ```
 *
 * ⚠️ 三个和直觉不符的地方，全靠实机抓包才发现的：
 * 1. **`type` 是驼峰式**（`DomainSuffix` / `GeoIP` / `Match`），不是配置
 *    文件里写的 `DOMAIN-SUFFIX` / `GEOIP`。所以 [category] 的匹配必须
 *    转小写再比，否则所有规则都会掉进「其它」。
 * 2. **`size` 恒为 `-1`**，不是命中次数。真正的命中次数在
 *    `extra.hitCount`；[size] 只是对外的展示字段，取值优先级见 [parseClashRules]。
 * 3. `payload` 对 `Match` 这类规则为空串。
 */
data class ClashRule(
    val type: String,
    val payload: String,
    val proxy: String,
    /** 命中次数。取自 `extra.hitCount`；内核没给这个字段时回落到 `size`（>0 才算） */
    val size: Int = 0,
    /** `extra.disabled` 为 true 表示这条规则在内核里被临时禁用（部分版本支持） */
    val disabled: Boolean = false,
) {
    /** 展示文案，形如 `DOMAIN-SUFFIX,google.com` */
    val text: String get() = if (payload.isBlank()) type else "$type,$payload"

    /** 规则大类：用于筛选与配色，见 [category] */
    val category: String
        get() {
            // ⚠️ 内核给的是驼峰（DomainSuffix），配置里写的是大写加连字符
            // （DOMAIN-SUFFIX）。统一去掉非字母后转小写，两种写法都能命中。
            val t = type.lowercase().replace("-", "").replace("_", "").replace(" ", "")
            return when {
                t == "match" || t == "final" -> "兜底"
                t.startsWith("domain") -> "域名"
                t.startsWith("process") -> "进程"
                t.startsWith("ruleset") || t.startsWith("geosite") -> "规则集"
                t.startsWith("geoip") || t.startsWith("ipcidr") ||
                    t.startsWith("ipasn") || t.contains("ip") -> "IP"
                t.contains("port") -> "端口"
                else -> "其它"
            }
        }
}

/** 全部规则 + 总数（内核顶层会同时给 `rules` 数组和可选的 `ruleCount`） */
data class ClashRuleSet(
    val rules: List<ClashRule> = emptyList(),
    val total: Int = 0,
)

/** 解析 `GET /rules` */
fun parseClashRules(root: JsonObject): ClashRuleSet {
    val arr = root.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray
    val list = arr?.mapNotNull { el ->
        val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val extra = o.get("extra")?.takeIf { it.isJsonObject }?.asJsonObject
        // ⚠️ 实测 `size` 恒为 -1（不是命中次数），真正的命中数在 extra.hitCount。
        // 优先取 hitCount；只有它缺失时才退回 size，且只在 size > 0 时采信。
        val hit = extra?.int("hitCount")?.takeIf { it > 0 }
            ?: o.int("size").takeIf { it > 0 }
            ?: 0
        ClashRule(
            type = o.str("type"),
            payload = o.str("payload"),
            proxy = o.str("proxy"),
            size = hit,
            disabled = extra?.boolean("disabled") ?: false,
        )
    } ?: emptyList()
    // 内核的 ruleCount 有时缺失；缺失时用实际条数兜底
    val declared = root.int("ruleCount")
    return ClashRuleSet(list, if (declared > 0) declared else list.size)
}

// ================================================================== 日志

/** 内核日志等级，顺序即严重度递增 */
enum class ClashLogLevel(val wire: String, val label: String) {
    DEBUG("debug", "调试"),
    INFO("info", "信息"),
    WARNING("warning", "警告"),
    ERROR("error", "错误");

    companion object {
        fun of(raw: String): ClashLogLevel =
            entries.firstOrNull { it.wire.equals(raw.trim(), true) } ?: INFO
    }
}

/**
 * 一条内核日志（`WS /logs` 或 `GET /logs` 的流）。
 *
 * 原始报文形如 `{"type":"info","payload":"[TCP] 1.2.3.4:80 --> ..."}`。
 * `time` 是内核给的可选字段，缺失时由 App 侧补本地时间。
 */
data class ClashLogEntry(
    val level: ClashLogLevel,
    val payload: String,
    val time: Long,
    /** 单调递增序号，供 UI 做「只增不减」的列表 key，避免重复内容被 Compose 复用错行 */
    val seq: Long = 0L,
)

/**
 * 解析一条日志报文。
 *
 * @param fallbackTime 报文里没有 `time` 时用的本地时间
 * @param seq 调用方维护的递增序号
 * @return 解析失败返回 null（内核偶尔会推空行或纯文本）
 */
fun parseClashLog(raw: String, fallbackTime: Long, seq: Long): ClashLogEntry? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    return runCatching {
        val o = com.google.gson.JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
            ?: return null
        val payload = o.str("payload").ifBlank { return null }
        ClashLogEntry(
            level = ClashLogLevel.of(o.str("type")),
            payload = payload,
            time = parseProviderTime(o.str("time")).takeIf { it > 0 } ?: fallbackTime,
            seq = seq,
        )
    }.getOrNull()
}

// ================================================================== 实时速率

/** 一次流量采样：用于算速率 */
data class ClashTrafficSample(val at: Long, val download: Long, val upload: Long)

/**
 * 用两次采样的总流量差算实时速率（字节/秒）。
 *
 * ⚠️ 内核不提供「当前速率」，`/connections` 只给累计值。zashboard 也是
 * 同样的做法：定时拉 `/connections`，用累计增量除以时间差。
 *
 * @return `(下行速率, 上行速率)`；采样间隔过短或出现回绕（重启内核导致累计值归零）
 *         时返回上一次的值，避免图上出现尖刺或负数。
 */
fun rateOf(prev: ClashTrafficSample?, now: ClashTrafficSample): Pair<Long, Long> {
    if (prev == null) return 0L to 0L
    val dt = now.at - prev.at
    if (dt <= 0) return 0L to 0L
    val dd = now.download - prev.download
    val du = now.upload - prev.upload
    // 负增量 = 内核重启/计数器回绕，按 0 处理而不是画一根向下的针
    val down = if (dd < 0) 0L else dd * 1000 / dt
    val up = if (du < 0) 0L else du * 1000 / dt
    return down to up
}

/**
 * 按 id 对齐两份连接快照，补上实时速率。
 *
 * 新增的连接（[prev] 里没有）速率为 0，第一次刷新不显示虚高的数字。
 */
fun applyRates(
    current: List<ClashConnection>,
    prev: List<ClashConnection>,
    prevAt: Long,
    nowAt: Long,
): List<ClashConnection> {
    if (prev.isEmpty() || prevAt <= 0L) return current
    val byId = prev.associateBy { it.id }
    val dt = nowAt - prevAt
    if (dt <= 0) return current
    return current.map { c ->
        val p = byId[c.id] ?: return@map c
        val dd = c.download - p.download
        val du = c.upload - p.upload
        if (dd < 0 || du < 0) c else c.copy(
            downloadSpeed = dd * 1000 / dt,
            uploadSpeed = du * 1000 / dt,
        )
    }
}
