package com.ufitools.client.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ufitools.client.data.ClashConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * mihomo external-controller 客户端。
 *
 * 与 [ApiClient] **完全独立**，原因有三：
 * 1. **认证不同**：走 `Authorization: Bearer <secret>`，不带设备那套 kano-sign/X-Device-Token；
 * 2. **端口不同**：内核监听自己的 9090，与设备自研层的 2333 无关；
 * 3. **失败语义不同**：内核返回标准 HTTP 状态码（401 密码错、404 路径错），
 *    可以直接把状态码翻译成人话。
 *
 * 只有 3 个超时都很短——面板是即时交互，卡住不如快速失败让用户重试。
 */
class ClashClient(private val configProvider: () -> ClashConfig) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        // 面板通常部署在局域网设备的自签/明文 HTTP 上，不跟随重定向：
        // 跟随时容易把带 secret 的请求转去外部站点
        .followRedirects(false)
        .build()

    // ------------------------------------------------------------------ 基础

    private fun apiBase(): String = configProvider().apiBaseUrl

    private fun Request.Builder.auth(): Request.Builder {
        val secret = configProvider().secret
        // 内核 secret 为空时不下发 Authorization 头，避免被当成空密码拒掉
        if (secret.isNotBlank()) header("Authorization", "Bearer $secret")
        return this
    }

    /**
     * 执行请求并返回响应体文本。
     *
     * 全部非 2xx 都转成 [ClashException]，`message` 直接可展示给用户。
     */
    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val resp = try {
            client.newCall(request).execute()
        } catch (e: java.net.SocketTimeoutException) {
            throw ClashException("面板响应超时，请检查地址与网络", 0)
        } catch (e: java.net.ConnectException) {
            throw ClashException("无法连接面板，请确认内核已启动且地址正确", 0)
        } catch (e: java.net.UnknownHostException) {
            throw ClashException("地址无法解析：${request.url.host}", 0)
        } catch (e: Exception) {
            throw ClashException("连接失败：${e.message ?: "未知错误"}", 0)
        }

        resp.use {
            val body = it.body?.string() ?: ""
            when {
                // 204 / 2xx 无正文都算成功
                it.isSuccessful -> body
                it.code == 401 -> throw ClashException("认证失败：secret 不正确", 401)
                it.code == 403 -> throw ClashException("面板拒绝访问（403），请检查 secret", 403)
                it.code == 404 -> throw ClashException(
                    "接口不存在（404）：${request.url.encodedPath}\n" +
                        "请确认填的是内核 external-controller 的地址与端口",
                    404
                )
                else -> throw ClashException(
                    "请求失败(${it.code})${body.take(120).let { b -> if (b.isBlank()) "" else "：$b" }}",
                    it.code
                )
            }
        }
    }

    /** 解析成 JsonObject；内核 204 无正文时返回空对象 */
    private fun parse(body: String): JsonObject =
        if (body.isBlank()) JsonObject()
        else try {
            JsonParser.parseString(body).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }

    private suspend fun get(path: String): JsonObject {
        val req = Request.Builder().url(apiBase() + path).auth().get().build()
        return parse(execute(req))
    }

    private suspend fun del(path: String): JsonObject {
        val req = Request.Builder().url(apiBase() + path).auth().delete().build()
        return parse(execute(req))
    }

    private suspend fun patch(path: String, json: String): JsonObject {
        val req = Request.Builder().url(apiBase() + path).auth()
            .patch(json.toRequestBody(jsonMedia))
            .build()
        return parse(execute(req))
    }

    private suspend fun put(path: String, json: String): JsonObject {
        val req = Request.Builder().url(apiBase() + path).auth()
            .put(json.toRequestBody(jsonMedia))
            .build()
        return parse(execute(req))
    }

    private fun seg(raw: String): String =
        URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    // ------------------------------------------------------------------ 端点

    /** `GET /version`：既用来探活，也用来判断内核类型 */
    suspend fun version(): JsonObject = get("/version")

    suspend fun configs(): JsonObject = get("/configs")

    /** 切换模式：只提交 `mode` 一个字段，其余运行配置不动 */
    suspend fun setMode(mode: String) {
        patch("/configs", """{"mode":"$mode"}""")
    }

    /** TUN 开关：`tun` 以对象形式提交，避免把其它 tun 子项抹掉 */
    suspend fun setTun(enable: Boolean) {
        patch("/configs", """{"tun":{"enable":$enable}}""")
    }

    suspend fun proxies(): JsonObject = get("/proxies")

    /** 策略组切换到指定节点 */
    suspend fun selectProxy(group: String, node: String) {
        put("/proxies/${seg(group)}", """{"name":${quote(node)}}""")
    }

    /**
     * 单节点测速。
     *
     * @param testUrl 测速目标地址，空串时用内核默认（一般是 gstatic 204）
     * @return 延迟毫秒数；内核返回 `{"delay":0}` 表示测速失败
     */
    suspend fun delay(name: String, testUrl: String = "", timeoutMs: Int = 5000): Int {
        val extra = if (testUrl.isBlank()) "" else "&url=${seg(testUrl)}"
        val o = get("/proxies/${seg(name)}/delay?timeout=$timeoutMs$extra")
        return o.get("delay")?.takeIf { !it.isJsonNull }?.asInt ?: 0
    }

    /** 整组测速：返回 `{节点名: 延迟}` */
    suspend fun groupDelay(group: String, testUrl: String = "", timeoutMs: Int = 5000): Map<String, Int> {
        val extra = if (testUrl.isBlank()) "" else "&url=${seg(testUrl)}"
        val o = get("/group/${seg(group)}/delay?timeout=$timeoutMs$extra")
        return o.entrySet().associate { (k, v) ->
            k to runCatching { v.asInt }.getOrDefault(0)
        }
    }

    suspend fun connections(): JsonObject = get("/connections")

    suspend fun closeAllConnections() {
        del("/connections")
    }

    suspend fun closeConnection(id: String) {
        del("/connections/${seg(id)}")
    }

    suspend fun providers(): JsonObject = get("/providers/proxies")

    /** 规则集订阅（`GET /providers/rules`）：与代理集分开，界面单独一段 */
    suspend fun ruleProviders(): JsonObject = get("/providers/rules")

    /** 全部分流规则（`GET /rules`） */
    suspend fun rules(): JsonObject = get("/rules")

    /** 规则集订阅更新 */
    suspend fun updateRuleProvider(name: String) {
        val base = "/providers/rules/${seg(name)}"
        try {
            put(base, "{}")
        } catch (e: ClashException) {
            if (e.code == 404 || e.code == 405) put("$base/update", "{}") else throw e
        }
    }

    /** 重启内核（部分内核需要，用于使配置生效） */
    suspend fun restartCore() {
        val req = Request.Builder().url("${apiBase()}/restart").auth()
            .post(ByteArray(0).toRequestBody(null)).build()
        execute(req)
    }

    /**
     * 升级内核（`POST /upgrade`）。
     *
     * ⚠️ 只有「内核自身带 UI/自身实现了升级」时才存在；多数 Android 端
     * mihomo 没有这个端点，会回 404，由调用方兜底提示。
     */
    suspend fun upgradeCore() {
        val req = Request.Builder().url("${apiBase()}/upgrade").auth()
            .post(ByteArray(0).toRequestBody(null)).build()
        execute(req)
    }

    /** 更新订阅：`PUT /providers/proxies/{name}`，部分内核走 `/update` 子路径 */
    suspend fun updateProvider(name: String) {
        val base = "/providers/proxies/${seg(name)}"
        try {
            put(base, "{}")
        } catch (e: ClashException) {
            // 老版本内核只有 /update 子路径，退化重试一次
            if (e.code == 404 || e.code == 405) {
                put("$base/update", "{}")
            } else {
                throw e
            }
        }
    }

    /** 触发整组健康检查（不返回延迟，结果异步写入内核） */
    suspend fun healthCheck(group: String) {
        val req = Request.Builder()
            // 该接口是 GET，但会触发副作用；部分内核要求带 ?url=
            .url("${apiBase()}/providers/proxies/${seg(group)}/healthcheck")
            .auth().get().build()
        execute(req)
    }

    suspend fun flushFakeIp() {
        val req = Request.Builder().url("${apiBase()}/cache/fakeip/flush").auth()
            .post(ByteArray(0).toRequestBody(null)).build()
        execute(req)
    }

    suspend fun flushDns() {
        val req = Request.Builder().url("${apiBase()}/cache/dns/flush").auth()
            .post(ByteArray(0).toRequestBody(null)).build()
        execute(req)
    }

    /** JSON 字符串转义：节点名里可能出现引号或反斜杠 */
    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }

    // ------------------------------------------------------------------ 供日志流复用

    /**
     * 供 [ClashLogStream] 复用的底层能力。
     *
     * 之所以要把 OkHttpClient / 地址 / 鉴权暴露出来，而不是让日志流自己
     * 再建一套——两套 client 会各自持有连接池和线程池，日志流断开重连时
     * 无法复用已经建立的 TCP，也没法统一超时策略。
     */
    internal fun rawClient(): OkHttpClient = client

    internal fun rawApiBase(): String = apiBase()

    /** 在构建 WebSocket 请求时挂上鉴权头（WebSocket 握手期就要求 secret） */
    internal fun applyAuth(builder: Request.Builder): Request.Builder = builder.auth()

    /** /logs 的 WebSocket 地址：把 http(s) 换成 ws(s) */
    internal fun logStreamUrl(level: String): String {
        val base = apiBase().replace(Regex("^http"), "ws")
        // 只订阅 warning 及以上会漏掉上下文，默认按调用方给的最低等级订阅
        return "$base/logs?level=${seg(level)}"
    }
}

/** Clash 面板请求异常，message 直接展示给用户 */
class ClashException(message: String, val code: Int = 0) : Exception(message)
