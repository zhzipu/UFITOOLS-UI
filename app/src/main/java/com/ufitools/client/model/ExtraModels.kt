package com.ufitools.client.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * 定时任务（API 文档 §9）。
 *
 * 设备侧的 `action` 是一段自由 JSON，不同任务形态差异很大（重启、开关 WiFi…），
 * 这里只抽出界面需要的公共字段，原始 `action` 保留在 [rawAction] 里，
 * 编辑时原样回传，避免把不认识的字段抹掉。
 */
data class ScheduledTask(
    val id: String,
    /** `HH:MM` */
    val time: String,
    val repeatDaily: Boolean,
    /** 人类可读的动作摘要，从 action 里猜出来的 */
    val actionLabel: String,
    /** 原始 action JSON 文本，回传时原样使用 */
    val rawAction: String,
    val lastRunTimestamp: Long? = null,
    val hasTriggered: Boolean = false,
) {
    companion object {
        fun from(el: JsonElement): ScheduledTask? {
            if (!el.isJsonObject) return null
            val o = el.asJsonObject
            val id = o.str("id")
            if (id.isEmpty()) return null
            val actionEl = o.get("action") ?: o.get("actionMap")
            return ScheduledTask(
                id = id,
                time = o.str("time"),
                repeatDaily = o.bool("repeatDaily"),
                actionLabel = summarize(actionEl),
                rawAction = actionEl?.toString() ?: "{}",
                lastRunTimestamp = o.str("lastRunTimestamp").toLongOrNull()?.takeIf { it > 0 },
                hasTriggered = o.bool("hasTriggered"),
            )
        }

        fun collect(root: JsonObject): List<ScheduledTask> {
            val arr = root.get("tasks")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            return arr.mapNotNull { from(it) }
        }

        /** 把 action JSON 压成一句人话，认不出来的就原样截断展示 */
        private fun summarize(action: JsonElement?): String {
            if (action == null || action.isJsonNull) return "未知动作"
            val txt = action.toString()
            val lower = txt.lowercase()
            return when {
                "reboot" in lower || "restart" in lower -> "重启设备"
                "shutdown" in lower || "poweroff" in lower -> "关机"
                "wifi" in lower && ("off" in lower || "\"0\"" in lower) -> "关闭 WiFi"
                "wifi" in lower -> "开启 WiFi"
                "sms" in lower && "send" in lower -> "发送短信"
                else -> txt.take(60)
            }
        }

        private fun JsonObject.str(key: String): String =
            get(key)?.let { if (it.isJsonNull) "" else if (it.isJsonPrimitive) (it as JsonPrimitive).asString else it.toString() } ?: ""

        private fun JsonObject.bool(key: String): Boolean =
            get(key)?.let { if (it.isJsonPrimitive) (it as JsonPrimitive).asBoolean else false } ?: false
    }
}

/** 一天的流量点，用于历史图表 */
data class UsagePoint(val date: String, val bytes: Long)

/**
 * 解析 `/api/cellularUsage?method=date-range` 的返回：
 * `{"result":"success","usage":[{"date":"2026-06-12","bytes":1048576}]}`
 */
fun parseUsagePoints(root: JsonObject): List<UsagePoint> {
    val arr: JsonArray = root.get("usage")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val o = el.asJsonObject
        val date = o.get("date")?.let { if (it.isJsonNull) "" else it.asString } ?: ""
        val bytes = o.get("bytes")?.let {
            when {
                it.isJsonNull -> 0L
                it.isJsonPrimitive -> (it as JsonPrimitive).asString.toLongOrNull() ?: 0L
                else -> 0L
            }
        } ?: 0L
        if (date.isEmpty()) null else UsagePoint(date, bytes)
    }
}

/** 解析 `method=mills-range` 的单值返回：`{"result":"success","usage":"1048576"}` */
fun parseUsageTotal(root: JsonObject): Long =
    root.get("usage")?.let {
        when {
            it.isJsonNull -> 0L
            it.isJsonPrimitive -> (it as JsonPrimitive).asString.toLongOrNull() ?: 0L
            else -> 0L
        }
    } ?: 0L

/** 上传目录里的一个文件（API 文档 §11） */
data class UploadedFile(
    val name: String,
    val size: Long,
    val mtime: Long,
    val url: String,
) {
    val isImage: Boolean
        get() = name.lowercase().let {
            it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") ||
                it.endsWith(".gif") || it.endsWith(".webp") || it.endsWith(".bmp")
        }

    companion object {
        fun collect(root: JsonObject): List<UploadedFile> {
            val arr = root.get("files")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val name = o.get("name")?.let { if (it.isJsonNull) "" else it.asString } ?: ""
                if (name.isEmpty()) return@mapNotNull null
                UploadedFile(
                    name = name,
                    size = o.long("size"),
                    mtime = o.long("mtime"),
                    url = o.get("url")?.let { if (it.isJsonNull) "" else it.asString } ?: "/uploads/$name",
                )
            }
        }

        private fun JsonObject.long(key: String): Long =
            get(key)?.let {
                if (it.isJsonPrimitive) (it as JsonPrimitive).asString.toLongOrNull() ?: 0L else 0L
            } ?: 0L
    }
}

/** 插件商店里的一个插件（API 文档 §14） */
data class StorePlugin(
    val publicName: String,
    val displayName: String,
    val version: String,
    val author: String,
    val description: String,
    val type: String,
    val installed: Boolean,
    /** 已安装插件的卸载名（可能与 publicName 不同） */
    val installName: String = "",
) {
    val isLua: Boolean get() = publicName.endsWith(".lua", ignoreCase = true)

    companion object {
        /**
         * 从商店响应里的一个条目构造。
         *
         * ⚠️ **真正的元信息在 `meta` 子对象里**（实测 `/api/plugin/list` 的结构）：
         * ```json
         * { "name": "5G信号监控.txt", "public_name": "51.txt",
         *   "meta": { "plugin_no": 51, "name": "5G信号监控", "public_name": "51.txt",
         *             "description": "检测5G信号", "version": "1.0.0", "author": "优先攻击富婆" } }
         * ```
         * 外层只有 `name` / `public_name` / `size` / `download_url` / `modified`，
         * **没有** version / author / description / enabled。
         *
         * 之前只从外层取，结果版本与作者恒为空、显示名还带着 `.txt` 后缀
         * （截图里主标题会长成「5G信号监控.txt」）。
         */
        fun from(el: JsonElement, installedNames: Set<String> = emptySet()): StorePlugin? {
            if (!el.isJsonObject) return null
            val o = el.asJsonObject
            val meta = o.get("meta")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

            // 安装文件名（下载/卸载用的键）：外层 public_name，回退 meta.public_name
            val pub = o.sAny("public_name", "publicName", "file")
                .ifEmpty { meta.sAny("public_name") }
                .ifEmpty { o.sAny("name") }
            if (pub.isEmpty()) return null

            val installName = o.sAny("install_name", "installed_name", "name")
                .ifEmpty { meta.sAny("id") }

            // 显示名优先取 meta.name（干净、无扩展名），再退回剥掉后缀的安装名
            val display = meta.sAny("name", "title")
                .ifEmpty { o.sAny("display_name", "title") }
                .ifEmpty { stripExtForDisplay(o.sAny("name")) }
                .ifEmpty { pub }

            val flag = o.s("installed").let { it == "true" || it == "1" } ||
                meta.s("enabled").let { it == "true" || it == "1" }

            return StorePlugin(
                publicName = pub,
                displayName = display,
                version = meta.sAny("version", "ver").ifEmpty { o.sAny("version", "ver") },
                author = meta.sAny("author", "uploader").ifEmpty { o.sAny("author", "uploader") },
                description = meta.sAny("description", "desc", "intro")
                    .ifEmpty { o.sAny("description", "desc", "intro") },
                type = meta.sAny("category", "type").ifEmpty { o.sAny("type", "category") },
                installed = flag || pub in installedNames || installName in installedNames,
                installName = installName,
            )
        }

        /** 剥掉一层文件扩展名：`5G信号监控.txt` → `5G信号监控`（仅用于显示名兜底） */
        private fun stripExtForDisplay(s: String): String {
            val i = s.lastIndexOf('.')
            // 只剥看起来像扩展名的（`.txt` / `.js` / `.lua`），且不把纯数字名剥没了
            if (i <= 0 || i < s.length - 8) return s
            return s.substring(0, i).ifEmpty { s }
        }

        /** 从 store / list 响应里收集，兼容多个可能的数组键名 */
        fun collect(root: JsonObject, installedNames: Set<String> = emptySet()): List<StorePlugin> {
            val arr = sequenceOf("plugins", "list", "data", "store", "items")
                .mapNotNull { root.get(it)?.takeIf { e -> e.isJsonArray }?.asJsonArray }
                .firstOrNull()
                ?: root.entrySet().firstOrNull { it.value.isJsonArray }?.value?.asJsonArray
                ?: return emptyList()
            return arr.mapNotNull { from(it, installedNames) }
        }

        private fun JsonObject.s(key: String): String =
            get(key)?.let {
                when {
                    it.isJsonNull -> ""
                    it.isJsonPrimitive -> (it as JsonPrimitive).asString
                    else -> it.toString()
                }
            } ?: ""

        private fun JsonObject.sAny(vararg keys: String): String =
            keys.firstNotNullOfOrNull { k -> s(k).takeIf { v -> v.isNotEmpty() } } ?: ""
    }
}

/**
 * 设备上**已安装**的一个插件。
 *
 * 数据来源与"商店列表"完全不同（实测确认）：
 *
 * - 商店列表来自 `/api/plugin/list`，返回的是**远端全量**，与设备状态无关。
 * - 已安装列表来自 `/api/get_custom_head` 返回的 `text`，里面每个插件是一段
 *   `<!-- [KANO_PLUGIN_START] 名字 -->` … `<!-- [KANO_PLUGIN_END] 名字 -->` 包裹的脚本块。
 * - 块首行可能带一行台账元信息（新版插件才有，老插件没有）：
 *   `<!-- [KANO_META] sid=<原名>;v=<版本>;no=<商店编号>;pub=<公开名> -->`
 *
 * ⚠️ `no` / `pub` / `v` **只对装了新版商店插件的设备存在**。老插件（如自制脚本）
 * 只有块名，这时 [version] 为空、[pluginNo] 为 0 —— 界面要能显示这种"无版本信息"的条目，
 * 不能因为它没版本号就把它藏起来。
 *
 * 官方的做法是在浏览器 localStorage 里另存一份台账做版本比对；我们不这么做——
 * `custom_head` 里的 `[KANO_META]` 才是随插件本体持久化在**设备上**的权威来源，
 * 换台手机登录同一设备也能读到，比 localStorage 可靠。
 */
data class InstalledPlugin(
    /** 块名（`[KANO_PLUGIN_START]` 后的字符串），卸载时按它匹配 */
    val name: String,
    /** 台账里的原始文件名（`sid`），跨版本稳定，作为排序与去重的主键 */
    val sid: String = "",
    val version: String = "",
    /** 商店编号，每次发版都会变；0 表示没有台账信息 */
    val pluginNo: Int = 0,
    val publicName: String = "",
    /** 正则解析时块在 custom_head 里的原始顺序，仅用于稳定排序 */
    val order: Int = 0,
) {
    /** 有 `[KANO_META]` 台账行 = 是走商店装的新版插件，能参与更新比对 */
    val hasMeta: Boolean get() = sid.isNotBlank()

    /** 能参与"商店是否有新版"比对的稳定标识 */
    val stableKey: String get() = sid.ifBlank { name }

    /**
     * 判断商店里的 [cur] 相比本条目是否有更新。
     *
     * 判定顺序与官方 `pluginHasUpdate` 略有不同：**先比版本号**。
     * 官方踩过的坑是"商店升了版本但 plugin_no / public_name 没变"，
     * 若先比 `no` 就会提前返回 false 而漏报，所以版本号必须排在最前面。
     */
    fun hasUpdate(cur: StorePlugin): Boolean {
        val a = version.trim().removePrefix("v").removePrefix("V")
        val b = cur.version.trim().removePrefix("v").removePrefix("V")
        if (a.isNotEmpty() && b.isNotEmpty() && a != b) return true

        val curPub = cur.publicName
        // 没有台账信息的老插件：只能靠块名与商店公开名对上，此时不声称"有更新"，
        // 免得把一堆自制脚本全标成可更新。
        if (!hasMeta) return false
        if (pluginNo > 0 && curPub.isNotEmpty()) {
            // 商店列表里没有 plugin_no 的直出字段，改用 public_name 比对
            if (publicName.isNotEmpty() && publicName != curPub) return true
        }
        return false
    }

    companion object {
        /** 块起止标记；`\1` 反向引用保证起止名字一致，与设备 Web 端正则同源 */
        private val BLOCK_RE = Regex(
            "<!--\\s*\\[KANO_PLUGIN_START\\]\\s*(.*?)\\s*-->([\\s\\S]*?)<!--\\s*\\[KANO_PLUGIN_END\\]\\s*\\1\\s*-->"
        )

        /** 台账行：`<!-- [KANO_META] sid=..;v=..;no=..;pub=.. -->` */
        private val META_RE = Regex("<!--\\s*\\[KANO_META\\]\\s*([^>]*?)\\s*-->")

        /**
         * 从 `get_custom_head` 返回的整段文本里解析出所有已安装插件。
         *
         * 同名块只保留**最后**一个（官方 `dedupePluginBlocksLastWins` 同款策略）：
         * 更新插件时新块追加在末尾，旧块可能还没被清掉，保留后面的才是新版本。
         */
        fun parseAll(text: String?): List<InstalledPlugin> {
            if (text.isNullOrBlank()) return emptyList()
            val out = ArrayList<InstalledPlugin>()
            BLOCK_RE.findAll(text).forEachIndexed { idx, m ->
                val name = m.groupValues[1].trim()
                if (name.isEmpty()) return@forEachIndexed
                val body = m.groupValues[2]
                val meta = META_RE.find(body)?.groupValues?.getOrNull(1).orEmpty()
                out += InstalledPlugin(
                    name = name,
                    sid = metaField(meta, "sid"),
                    version = metaField(meta, "v"),
                    pluginNo = metaField(meta, "no").toIntOrNull() ?: 0,
                    publicName = metaField(meta, "pub"),
                    order = idx,
                )
            }
            // 后出现的同名块覆盖先出现的
            return out.groupBy { it.name }.map { (_, list) -> list.last() }
                .sortedBy { it.order }
        }

        private fun metaField(meta: String, key: String): String =
            meta.split(';')
                .firstOrNull { it.substringBefore('=').trim() == key }
                ?.substringAfter('=', "")
                ?.trim()
                .orEmpty()
    }
}

/**
 * 内网（LAN）设置。写入走 goform `DHCP_SETTING`。
 *
 * ⚠️ 设备侧读取字段名与写入字段名**不一致**，必须各按各的来：
 * - 读：`lan_ipaddr` / `lan_netmask` / `dhcpEnabled` / `dhcpStart` / `dhcpEnd` / `dhcpLease_hour`
 * - 写：`lanIp` / `lanNetmask` / `lanDhcpType`(`SERVER`|`DISABLE`) / `dhcpStart` / `dhcpEnd` / `dhcpLease`
 *
 * `dhcpLease_hour` 读回来带单位（`"24h"`），写入却要是纯数字。
 */
data class LanSetting(
    val gateway: String = "",
    val netmask: String = "",
    val dhcpEnabled: Boolean = true,
    val dhcpStart: String = "",
    val dhcpEnd: String = "",
    /** 纯数字小时数，已剥掉设备回读里的 `h` 后缀 */
    val dhcpLeaseHour: String = "",
) {
    companion object {
        fun from(o: JsonObject): LanSetting? {
            fun s(k: String): String {
                val v = o.get(k) ?: return ""
                return if (v.isJsonNull || !v.isJsonPrimitive) "" else v.asString
            }
            val gw = s("lan_ipaddr")
            // 拿不到网关地址就认为没读到，交给上层保留旧值 / 报错
            if (gw.isBlank()) return null
            return LanSetting(
                gateway = gw,
                netmask = s("lan_netmask"),
                dhcpEnabled = s("dhcpEnabled") == "1",
                dhcpStart = s("dhcpStart"),
                dhcpEnd = s("dhcpEnd"),
                dhcpLeaseHour = s("dhcpLease_hour").removeSuffix("h").removeSuffix("H"),
            )
        }
    }
}

/**
 * IP / 掩码校验，规则与设备自带 Web 端 `utils.js` 完全对齐。
 *
 * 这些校验**不是可选的**：设备对本类 goform 一律"投递即成功"，
 * 参数非法也回 `success`，只能靠 App 侧挡住。官方 Web 端挡了六条，
 * 这里逐条搬过来，避免"提示成功但设备其实没改"。
 */
object IpValidator {

    private val IP_RE = Regex(
        "^(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)){3}$"
    )

    /** 合法掩码白名单（与官方一致，只认连续 1 的掩码） */
    private val MASKS = setOf(
        "255.0.0.0", "255.128.0.0",
        "255.192.0.0", "255.224.0.0", "255.240.0.0", "255.248.0.0", "255.252.0.0",
        "255.254.0.0", "255.255.0.0", "255.255.128.0", "255.255.192.0",
        "255.255.224.0", "255.255.240.0", "255.255.248.0", "255.255.252.0",
        "255.255.254.0", "255.255.255.0", "255.255.255.128", "255.255.255.192",
        "255.255.255.224", "255.255.255.240", "255.255.255.248",
        "255.255.255.252", "255.255.255.254"
    )

    fun isValidIp(ip: String): Boolean = IP_RE.matches(ip)

    fun isValidMask(mask: String): Boolean = mask in MASKS

    /** IPv4 → 32 位无符号整数；非法输入返回 -1 */
    fun toInt(ip: String): Long {
        if (!isValidIp(ip)) return -1L
        return ip.split('.').fold(0L) { acc, s -> (acc shl 8) or s.toLong() }
    }

    fun sameSubnet(a: String, b: String, mask: String): Boolean {
        val m = toInt(mask)
        if (m < 0) return false
        return (toInt(a) and m) == (toInt(b) and m)
    }

    fun networkAddress(ip: String, mask: String): String = intToIp(toInt(ip) and toInt(mask))

    fun broadcastAddress(ip: String, mask: String): String =
        intToIp((toInt(ip) and toInt(mask)) or (toInt(mask).inv() and 0xFFFFFFFFL))

    private fun intToIp(v: Long): String =
        listOf((v shr 24) and 255, (v shr 16) and 255, (v shr 8) and 255, v and 255)
            .joinToString(".")

    /**
     * 全套校验，返回第一条错误文案；全部通过返回 null。
     *
     * 顺序有意为之：先单项格式，再跨字段关系（同网段 / 大小关系 / 网关不在池内）。
     */
    fun validate(s: LanSetting): String? {
        if (!isValidIp(s.gateway)) return "网关地址格式不对，应形如 192.168.0.1"
        if (!isValidMask(s.netmask)) return "子网掩码不是合法的掩码，如 255.255.255.0"

        val net = networkAddress(s.gateway, s.netmask)
        val bcast = broadcastAddress(s.gateway, s.netmask)
        // 网关自己不能是网络地址 / 广播地址，否则整段网络不可用
        if (s.gateway == net) return "网关不能是网络地址 $net"
        if (s.gateway == bcast) return "网关不能是广播地址 $bcast"

        if (!s.dhcpEnabled) return null

        if (!isValidIp(s.dhcpStart)) return "DHCP 起始地址格式不对"
        if (!isValidIp(s.dhcpEnd)) return "DHCP 结束地址格式不对"

        if (!sameSubnet(s.dhcpStart, s.gateway, s.netmask)) return "DHCP 起始地址不在网关所在网段"
        if (!sameSubnet(s.dhcpEnd, s.gateway, s.netmask)) return "DHCP 结束地址不在网关所在网段"

        if (s.dhcpStart == net || s.dhcpStart == bcast) return "DHCP 起始地址不能是网络地址或广播地址"
        if (s.dhcpEnd == net || s.dhcpEnd == bcast) return "DHCP 结束地址不能是网络地址或广播地址"

        val start = toInt(s.dhcpStart)
        val end = toInt(s.dhcpEnd)
        val gw = toInt(s.gateway)
        if (start == end) return "DHCP 起止地址不能相同"
        if (start > end) return "DHCP 起始地址不能大于结束地址"
        if (start <= gw) return "DHCP 起始地址要大于网关地址"
        // 网关落在池里会导致地址冲突
        if (gw in start..end) return "网关地址不能落在 DHCP 分配范围内"

        val lease = s.dhcpLeaseHour.toIntOrNull() ?: 0
        if (lease <= 0) return "DHCP 租期必须是大于 0 的小时数"

        return null
    }
}

/**
 * 设备的一个 AP（2.4G / 5G 各一个），来自 goform `queryAccessPointInfo` 的 `ResponseList`。
 *
 * ⚠️ **`Password` 读回是 base64**（实测 `cXE4NjE0MTU2MzA=` 解码为 `qq861415630`）。
 * [password] 存的是**解码后的明文**；写回时同样要 base64（见 MainViewModel.setWifiAp）。
 */
data class WifiAp(
    /** 0 = 2.4G（chip1），1 = 5G（chip2） */
    val chipIndex: Int,
    /** `main_2g` / `main_5g`，提交 `setAccessPointInfo` 时原样回传 */
    val apIndex: String,
    val ssid: String,
    /** 解码后的明文密码；开放网络（AuthMode=none）下无意义 */
    val password: String,
    /** 与设备网页版下拉同值域：`none` / `psk2+ccmp` / `psk-mixed+tkip+ccmp` / `sae-mixed` / `sae` */
    val authMode: String,
    /** true = 隐藏 SSID（网页版「隐藏SSID」勾选，字段 `ApBroadcastDisabled`） */
    val broadcastDisabled: Boolean,
    /** `"0"` = 关闭，`"1"` = 开启（网页版 PMF 下拉只有这两档） */
    val pmf: String,
    /** 该频段 AP 的开关状态（`AccessPointSwitchStatus`），关闭的频段不显示编辑栏 */
    val switchOn: Boolean,
) {
    val bandLabel: String get() = if (chipIndex == 1) "5 GHz" else "2.4 GHz"

    /** 开放网络没有密码框 —— 与网页版 `showable` 的隐藏行为一致 */
    val isOpen: Boolean get() = authMode == "none"

    companion object {
        fun collect(arr: JsonArray?): List<WifiAp> =
            arr?.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val chip = o.str("ChipIndex").toIntOrNull() ?: return@mapNotNull null
                val apIndex = o.str("AccessPointIndex")
                if (apIndex.isEmpty()) return@mapNotNull null
                // base64 解不开时按原文用：个别固件可能不编码直接给明文
                val raw = o.str("Password")
                val pwd = runCatching {
                    String(java.util.Base64.getDecoder().decode(raw))
                }.getOrDefault(raw)
                WifiAp(
                    chipIndex = chip,
                    apIndex = apIndex,
                    ssid = o.str("SSID"),
                    password = pwd,
                    authMode = o.str("AuthMode").ifEmpty { "psk2+ccmp" },
                    broadcastDisabled = o.str("ApBroadcastDisabled") == "1",
                    pmf = o.str("Pmf_switch").ifEmpty { "0" },
                    switchOn = o.str("AccessPointSwitchStatus") == "1",
                )
            } ?: emptyList()

        private fun JsonObject.str(key: String): String =
            get(key)?.let {
                when {
                    it.isJsonNull -> ""
                    it.isJsonPrimitive -> (it as JsonPrimitive).asString.trim()
                    else -> it.toString()
                }
            } ?: ""
    }
}
