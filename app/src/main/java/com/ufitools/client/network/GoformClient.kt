package com.ufitools.client.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ufitools.client.data.DeviceConfig
import com.ufitools.client.model.AclState
import com.ufitools.client.model.LanSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 中兴官方后台 goform 接口客户端。
 *
 * - 读取：GET /goform/goform_get_cmd_process?isTest=false&cmd=...&multi_data=1
 * - 写入：POST /goform/goform_set_cmd_process，需登录 Cookie + AD 签名
 *
 * 注意：goform 路径不属于 UFI-TOOLS 的 `/api/` 鉴权范围，
 * 因此 X-Device-Token 传空即可（SigningInterceptor 会跳过该头）。
 */
class GoformClient(private val configProvider: () -> DeviceConfig) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(SigningInterceptor({ configProvider().token }, { "" }))
        .build()

    private fun base(): String = configProvider().baseUrl

    private fun parse(body: String): JsonObject = try {
        JsonParser.parseString(body).asJsonObject
    } catch (e: Exception) {
        JsonObject()
    }

    /**
     * goform 读取。返回值是 field -> 字符串值 的映射（部分值为 JSON 字符串）。
     * @param extra 附加查询参数（如短信列表的 page/data_per_page 等）
     * @param cookie 部分字段（如接入控制黑名单）需要登录 Cookie；免鉴权字段传 null 即可
     */
    suspend fun get(
        cmds: List<String>,
        multiData: Boolean = true,
        extra: Map<String, String> = emptyMap(),
        cookie: String? = null
    ): JsonObject = withContext(Dispatchers.IO) {
        val cmd = URLEncoder.encode(cmds.joinToString(","), "UTF-8")
        val sb = StringBuilder(base())
            .append("/goform/goform_get_cmd_process?isTest=false&cmd=").append(cmd)
        if (multiData) sb.append("&multi_data=1")
        extra.forEach { (k, v) ->
            sb.append('&').append(URLEncoder.encode(k, "UTF-8"))
                .append('=').append(URLEncoder.encode(v, "UTF-8"))
        }
        sb.append("&_=").append(System.currentTimeMillis())

        val req = Request.Builder().url(sb.toString()).get().apply {
            if (!cookie.isNullOrBlank()) header("Cookie", cookie)
        }.build()
        client.newCall(req).execute().use { resp ->
            parse(resp.body?.string() ?: "")
        }
    }

    /**
     * goform 写入。自动计算 AD 签名并附带登录 Cookie。
     */
    suspend fun post(goformId: String, params: Map<String, String>, cookie: String): JsonObject =
        withContext(Dispatchers.IO) {
            val ad = computeAD(cookie)
            val form = FormBody.Builder()
            form.add("goformId", goformId)
            form.add("isTest", "false")
            form.add("AD", ad)
            params.forEach { (k, v) -> form.add(k, v) }

            val req = Request.Builder().url(base() + "/goform/goform_set_cmd_process")
                .header("Cookie", cookie)
                .post(form.build())
                .build()
            client.newCall(req).execute().use { resp ->
                parse(resp.body?.string() ?: "")
            }
        }

    /**
     * 读取已连接终端列表（无线 `station_list` + 有线 `lan_station_list`）。
     *
     * 每项包含 `hostname` / `ip_addr` / `mac_addr` / `conn_type` / `devtype` / `vendor`。
     * 该接口**无需鉴权**（与 `/api/` 不同），因此设备令牌失效时本列表仍可用。
     *
     * 注意：该固件不返回每台终端的流量与连接时长，那两项需另经
     * `/api/run_shell` 执行 `iw ... station dump` 获得，由上层按 MAC 合并。
     */
    suspend fun stationList(): List<JsonObject> {
        val o = get(listOf("station_list", "lan_station_list"), multiData = true)
        return STATION_LIST_KEYS.flatMap { key ->
            val el = o.get(key) ?: return@flatMap emptyList()
            if (!el.isJsonArray) emptyList()
            else el.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
        }
    }

    /** 读取单个命令并返回字符串值 */
    suspend fun getString(cmd: String): String? {
        val o = get(listOf(cmd), multiData = false)
        val v = o.get(cmd) ?: return null
        return if (v.isJsonNull) null else v.asString
    }

    // -------------------------------------------------------------- 接入控制 / 黑名单

    /**
     * 读取设备接入控制（黑名单）状态。
     *
     * ⚠️⚠️ **`cmd` 必须逐字使用下面这四个的完整组合，一个都不能少、顺序也不能变**：
     * ```
     * station_list,lan_station_list,queryDeviceAccessControlList,hostNameList
     * ```
     * 这是本固件（U60Pro / BD_CNMU5250V1.0.0B31）最反直觉的一处：
     *
     * - `queryDeviceAccessControlList` 是个**纯触发器**，它自己**不会**出现在响应里，
     *   但**只要请求里没有它，设备就完全不返回** `AclMode` / `BlackMacList` / `BlackNameList`。
     * - 所以「只要 `AclMode,BlackMacList,BlackNameList`」会拿到 `{}`，
     *   看起来像"这台设备不支持黑名单"，实际只是没带上触发器。
     * - `hostNameList` 同理属于这一组（官方 Web 端一并请求）。
     *
     * 另外两条实测结论：
     * - **不能带 `multi_data=1`**：带上会退化成 `{}`（官方 `getData` 也不带）。
     * - **必须带登录 Cookie**（与免鉴权的 `station_list` 不同）。
     *
     * @param cookie 登录会话 Cookie
     * @return 读成功返回 [AclState]；未拿到 `AclMode`（含未登录/固件差异）返回 null，
     *         由调用方决定保留旧值还是提示——**不要把"读不到"当成"黑名单为空"**。
     */
    suspend fun deviceAccessControlList(cookie: String?): AclState? {
        val o = get(cmds = ACL_QUERY_CMDS, multiData = false, cookie = cookie)
        val mode = o.get("AclMode")
        if (mode == null || mode.isJsonNull) return null

        fun str(key: String): String {
            val v = o.get(key) ?: return ""
            return if (v.isJsonNull) "" else v.asString
        }
        return AclState(
            aclMode = str("AclMode").ifBlank { "0" },
            blackMacs = AclState.parseList(str("BlackMacList")),
            blackNames = AclState.parseList(str("BlackNameList"))
        )
    }

    /**
     * 写入接入控制（黑名单）。
     *
     * 与设备 Web 端 `setOrRemoveDeviceFromBlackList` 完全对齐：
     * `AclMode` 原样透传（**不做语义解释、不擅自改值**），
     * `WhiteMacList` / `WhiteNameList` 传空串（设备不支持这两项）。
     *
     * @return 设备返回的 `result` 字符串（原厂判据：`"success"` 为成功）
     */
    suspend fun setDeviceAccessControlList(
        cookie: String,
        state: AclState
    ): String = withContext(Dispatchers.IO) {
        val res = post(
            goformId = "setDeviceAccessControlList",
            params = mapOf(
                "AclMode" to state.aclMode.trim(),
                "WhiteMacList" to "",
                "BlackMacList" to AclState.join(state.blackMacs).trim(),
                "WhiteNameList" to "",
                "BlackNameList" to AclState.join(state.blackNames).trim()
            ),
            cookie = cookie
        )
        val v = res.get("result") ?: return@withContext ""
        if (v.isJsonNull) "" else v.asString
    }

    // -------------------------------------------------------------- 休眠 / 内网 / 数据 / NFC

    /**
     * 读取系统休眠（无操作自动睡眠）时间，单位分钟。
     *
     * `-1` 表示**从不休眠**，其余为分钟数（5/10/20/30/60/120）。
     * 设备读不到该字段时会回空串，这时返回 null 由上层决定是否隐藏入口
     * ——官方 Web 端同样是"读到空就把这个下拉框藏起来"，因为不支持该特性的机型
     * 压根没有这个能力。
     */
    suspend fun sleepMinutes(): Int? {
        val raw = getString("sleep_sysIdleTimeToSleep") ?: return null
        if (raw.isBlank()) return null
        return raw.trim().toIntOrNull()
    }

    /**
     * 写入休眠时间。`minutes` 传 `-1` 表示从不休眠。
     * @return 设备返回的 `result`（`"success"` 为成功）
     */
    suspend fun setSleepMinutes(cookie: String, minutes: Int): String {
        val res = post(
            goformId = "SET_WIFI_SLEEP_INFO",
            params = mapOf("sleep_sysIdleTimeToSleep" to minutes.toString()),
            cookie = cookie
        )
        return resultOf(res)
    }

    /**
     * 读取内网设置。
     *
     * ⚠️ 读取字段名与写入字段名不同（见 [LanSetting] 的说明），这里用的是**读取**那一套。
     * 该组合照抄设备 Web 端 `initLANSettings` 的 `cmd`，顺序也一致。
     */
    suspend fun lanSetting(): LanSetting? {
        val o = get(cmds = LAN_QUERY_CMDS, multiData = true)
        return LanSetting.from(o)
    }

    /**
     * 写入内网设置。
     *
     * ⚠️⚠️ **该操作会让设备重启网络服务**，官方 Web 端写完后 30 秒跳转到新网关地址。
     * 调用方必须先取得用户明确确认；在设备上的表现是 WiFi 短暂断开后重连。
     *
     * 固定附带两个标志位（照抄官方）：
     * - `dhcp_reboot_flag=1`：让设备应用改动后重启 DHCP 服务；
     * - `mac_ip_reset`：开 DHCP 时传 `1`（按新网段重新分配），关时传 `0`。
     *
     * @return 设备返回的 `result`
     */
    suspend fun setLanSetting(cookie: String, s: LanSetting): String {
        val params = linkedMapOf(
            "lanIp" to s.gateway.trim(),
            "lanNetmask" to s.netmask.trim(),
            "lanDhcpType" to if (s.dhcpEnabled) "SERVER" else "DISABLE",
            "dhcpStart" to if (s.dhcpEnabled) s.dhcpStart.trim() else "",
            "dhcpEnd" to if (s.dhcpEnabled) s.dhcpEnd.trim() else "",
            "dhcpLease" to if (s.dhcpEnabled) s.dhcpLeaseHour.trim() else "",
            "dhcp_reboot_flag" to "1",
            "mac_ip_reset" to if (s.dhcpEnabled) "1" else "0"
        )
        return resultOf(post(goformId = "DHCP_SETTING", params = params, cookie = cookie))
    }

    /**
     * 读取蜂窝数据开关。
     *
     * 判据取设备 Web 端的 `readCellularState`：
     * 1. 优先看 `cellular_data_switch`（可能回 `on`/`off` 或 `1`/`0`）；
     * 2. 该字段缺失或不可解析时，退化为从 `ppp_status` 猜——
     *    只要没出现 `disconnect` / `idle` / `init` / `failed` 就认为数据是开着的。
     *
     * 返回 null 表示两项都读不到。
     */
    suspend fun cellularDataOn(): Boolean? {
        val o = get(cmds = CELLULAR_QUERY_CMDS, multiData = true)
        val explicit = parseSwitch(o.strOrNull("cellular_data_switch"))
        if (explicit != null) return explicit
        val ppp = o.strOrNull("ppp_status")?.trim()?.lowercase().orEmpty()
        if (ppp.isEmpty()) return null
        return !ppp.contains("disconnect") &&
            ppp !in setOf("idle", "init", "failed", "failure")
    }

    /**
     * 开关蜂窝数据。
     *
     * ⚠️ 与 goform 的一般行为不同，这两个 goformId **确实有成功/失败之分**，
     * 官方 Web 端写完还会轮询 `waitForCellularState` 确认真的切过去了。
     * 这里只做一次写入，回读确认交给上层（避免 App 侧也卡 8 秒）。
     */
    suspend fun setCellularData(cookie: String, on: Boolean): String =
        resultOf(
            post(
                goformId = if (on) "CONNECT_NETWORK" else "DISCONNECT_NETWORK",
                params = emptyMap(),
                cookie = cookie
            )
        )

    /**
     * 读取 NFC 开关状态。
     *
     * ⚠️ 这里又是一个"触发器"字段（与 `queryDeviceAccessControlList` 同理，但**方向相反**）：
     * **只请求 `web_wifi_nfc_switch` 一个字段**时，设备会把 `web_wifi_nfc_flag` **一并带回**；
     * 一旦把两个字段同时写进 `cmd`，设备反而回 `{}`。实测确认，不要"顺手"把 flag 加进去。
     *
     * @return [NfcState]；读到 `is_support_nfc_functions != "1"` 时 [NfcState.supported] 为 false
     */
    suspend fun nfcState(): NfcState {
        val support = get(cmds = listOf("is_support_nfc_functions"), multiData = false)
            .strOrNull("is_support_nfc_functions")
        if (support != "1") return NfcState(supported = false)

        val o = get(cmds = listOf("web_wifi_nfc_switch"), multiData = false)
        val sw = o.strOrNull("web_wifi_nfc_switch")
        val flag = o.strOrNull("web_wifi_nfc_flag")
        return NfcState(
            supported = true,
            enabled = sw == "1",
            // 设备不给 flag 时按官方的默认值 2（5G 主 WiFi）处理
            ap = flag?.trim()?.takeIf { it.isNotEmpty() } ?: "2"
        )
    }

    /**
     * 写入 NFC 开关 / 配对 WiFi。
     *
     * 与官方 `toggleNFC` / `changeNFCAp` 同一个 goform：两个动作都走 `WIFI_NFC_SET`，
     * 且**每次都要把 switch 与 flag 一起传全**（改配对 WiFi 时 switch 固定传 `'1'`）。
     *
     * @param ap 配对 WiFi：1=2.4G 主 / 2=5G 主 / 3=2.4G 访客 / 4=5G 访客
     */
    suspend fun setNfc(cookie: String, enabled: Boolean, ap: String): String =
        resultOf(
            post(
                goformId = "WIFI_NFC_SET",
                params = mapOf(
                    "web_wifi_nfc_switch" to if (enabled) "1" else "0",
                    "web_wifi_nfc_flag" to ap
                ),
                cookie = cookie
            )
        )

    /** 从响应里取 `result` 字段，统一处理 null / 缺失 */
    private fun resultOf(res: JsonObject): String {
        val v = res.get("result") ?: return ""
        return if (v.isJsonNull) "" else v.asString
    }

    /** 取字段字符串值，缺失或非原始类型返回 null（与 `""` 区分开） */
    private fun JsonObject.strOrNull(key: String): String? {
        val v = get(key) ?: return null
        return if (v.isJsonNull || !v.isJsonPrimitive) null else v.asString
    }

    /** 开关字段解析：`1/on/true/enabled` → true，`0/off/false/disabled` → false，其余 null */
    private fun parseSwitch(value: String?): Boolean? =
        when (value?.trim()?.lowercase()) {
            "1", "on", "true", "enabled" -> true
            "0", "off", "false", "disabled" -> false
            else -> null
        }

    // ------------------------------------------------------------------ 登录

    /**
     * 完整登录流程，返回会话 Cookie（kano-cookie 的会话片段）。
     * 依次尝试 LOGIN_MULTI_USER 与 LOGIN 两种 goformId。
     *
     * 判据取自设备自带 Web 端的实现：登录失败会返回 `result:"1"`（本服务/U60Pro）
     * 或 `"3"`（老固件）**并且不下发 kano-cookie**；只有 `result:"0"` 才带 cookie。
     * 所以这里不以 result 为准，而是**只看有没有拿到 kano-cookie**——这样
     * 无论固件用哪个失败码，都不会把"没登上"误判成"登上了"。
     *
     * 口令必须是**大写 hex**的双重 SHA256（见 [Crypto.sha256HexUpper]），否则必然失败。
     */
    suspend fun login(adminPassword: String): String? = withContext(Dispatchers.IO) {
        try {
            val ld = getString("LD") ?: return@withContext null
            // 大写 hex：设备端按大写比对（见 Crypto.sha256HexUpper 的说明）
            val pwd = Crypto.sha256HexUpper(Crypto.sha256HexUpper(adminPassword) + ld)

            for (goformId in listOf("LOGIN_MULTI_USER", "LOGIN")) {
                val form = FormBody.Builder()
                    .add("goformId", goformId)
                    .add("isTest", "false")
                    .add("password", pwd)
                    .add("user", "admin")
                if (goformId == "LOGIN_MULTI_USER") {
                    form.add("IP", "localhost")
                }
                val req = Request.Builder()
                    .url(base() + "/goform/goform_set_cmd_process")
                    .post(form.build())
                    .build()

                // use {} 是 inline 作用域，内部不能使用 continue；
                // 改为把结果作为表达式返回，在外层判断后决定是否重试。
                val session: String? = client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val obj = try {
                        JsonParser.parseString(body).asJsonObject
                    } catch (e: Exception) {
                        JsonObject()
                    }
                    val result = obj.get("result")?.asString ?: ""

                    // 登录失败的两张脸：本服务(U60Pro)返回 "1" 且不下发 kano-cookie，
                    // 老 ZTE 固件返回 "3"。两者都换下一个 goformId 再试一次。
                    // 最终以「是否下发 kano-cookie」为成功标志——与设备自带 Web 端的约定一致。
                    if (result == "1" || result == "3") {
                        null
                    } else {
                        val cookie = resp.header("kano-cookie")?.substringBefore(';')
                        if (!cookie.isNullOrBlank()) {
                            cookie
                        } else {
                            // 有些固件返回 Set-Cookie，尝试从中提取会话
                            resp.header("Set-Cookie")?.substringBefore(';')
                        }
                    }
                }
                if (!session.isNullOrBlank()) {
                    return@withContext session
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 计算 goform 写入所需的 AD 签名：`SHA256(SHA256(wa_inner_version + cr_version) + RD)`。
     * 与登录口令同理，**必须大写 hex**（见 [Crypto.sha256HexUpper]）。
     */
    private suspend fun computeAD(cookie: String): String = withContext(Dispatchers.IO) {
        val ver = get(listOf("Language", "cr_version", "wa_inner_version"), multiData = true)
        val wa = ver.get("wa_inner_version")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val cr = ver.get("cr_version")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val parsed = Crypto.sha256HexUpper(wa + cr)
        val rd = getString("RD") ?: ""
        Crypto.sha256HexUpper(parsed + rd)
    }

    // ------------------------------------------------------------------ 短信

    /** 短信正文编码为 UTF-16(大端) hex，供 SEND_SMS 的 MessageBody 使用 */
    fun smsBodyToHex(text: String): String {
        val sb = StringBuilder(text.length * 4)
        for (unit in text) {
            sb.append(String.format("%04X", unit.code))
        }
        return sb.toString()
    }

    suspend fun sendSms(number: String, content: String, cookie: String): JsonObject =
        post("SEND_SMS", mapOf("Number" to number, "MessageBody" to smsBodyToHex(content)), cookie)

    suspend fun deleteSms(msgId: String, cookie: String): JsonObject =
        post("DELETE_SMS", mapOf("msg_id" to msgId, "notCallback" to "true"), cookie)

    suspend fun markSmsRead(msgId: String, cookie: String): JsonObject =
        post("SET_MSG_READ", mapOf("msg_id" to msgId, "notCallback" to "true"), cookie)

    /** 读取短信列表（content 为 base64，tag=1 未读） */
    suspend fun smsList(page: Int = 0, perPage: Int = 500): JsonObject = get(
        cmds = listOf("sms_data_total"),
        multiData = false,
        extra = mapOf(
            "page" to page.toString(),
            "data_per_page" to perPage.toString(),
            "mem_store" to "1",
            "tags" to "100",
            "order_by" to "order by id desc"
        )
    )
}

/** 终端列表的两个字段名：无线在前、有线在后 */
private val STATION_LIST_KEYS = listOf("station_list", "lan_station_list")

/**
 * 读取接入控制黑名单所需的 `cmd` 组合，**必须整体、按此顺序发送**。
 *
 * `queryDeviceAccessControlList` 是触发器：不带上它，设备不会返回
 * `AclMode` / `BlackMacList` / `BlackNameList`（详见 [GoformClient.deviceAccessControlList]）。
 */
private val ACL_QUERY_CMDS = listOf(
    "station_list",
    "lan_station_list",
    "queryDeviceAccessControlList",
    "hostNameList"
)

/**
 * 读取内网设置所需的 `cmd` 组合，照抄设备 Web 端 `initLANSettings`。
 *
 * 注意读取用 `lan_ipaddr` / `dhcpEnabled`，写入却要用 `lanIp` / `lanDhcpType`，
 * 设备两侧字段名不一致，不能想当然复用。
 */
private val LAN_QUERY_CMDS = listOf(
    "lan_ipaddr",
    "lan_netmask",
    "mac_address",
    "dhcpEnabled",
    "dhcpStart",
    "dhcpEnd",
    "dhcpLease_hour",
    "mtu",
    "tcp_mss"
)

/** 读取蜂窝数据开关所需的字段（照抄设备 Web 端 `readCellularState`） */
private val CELLULAR_QUERY_CMDS = listOf(
    "cellular_data_switch",
    "cellular_connect_status",
    "ppp_status"
)

/**
 * NFC 开关状态。
 *
 * @param supported 设备是否具备 NFC 能力（`is_support_nfc_functions == "1"`）
 * @param enabled NFC 总开关
 * @param ap 配对 WiFi：`1` 2.4G 主 / `2` 5G 主 / `3` 2.4G 访客 / `4` 5G 访客
 */
data class NfcState(
    val supported: Boolean,
    val enabled: Boolean = false,
    val ap: String = "2"
)
