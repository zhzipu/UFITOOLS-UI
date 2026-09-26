package com.ufitools.client.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ufitools.client.data.DeviceConfig
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
     */
    suspend fun get(
        cmds: List<String>,
        multiData: Boolean = true,
        extra: Map<String, String> = emptyMap()
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

        val req = Request.Builder().url(sb.toString()).get().build()
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
