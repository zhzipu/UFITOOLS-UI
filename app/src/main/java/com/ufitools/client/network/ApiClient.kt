package com.ufitools.client.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ufitools.client.data.DeviceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * UFI-TOOLS 自身 `/api/` 接口客户端。
 * 所有请求通过 SigningInterceptor 自动签名。
 */
class ApiClient(private val configProvider: () -> DeviceConfig) {

    private val gson = Gson()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** 设备令牌：由 refreshDeviceToken() 从 /api/need_token 获取，供 X-Device-Token 头使用 */
    @Volatile
    private var deviceToken: String = ""

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(SigningInterceptor({ configProvider().token }, { deviceToken }))
        .build()

    /**
     * 拉取设备令牌写入 [deviceToken]。
     * `/api/need_token` 在免鉴权白名单内，返回形如
     * `{"device_token":"<64位hex>","need_token":true}`。
     * 该值必须放进后续所有请求的 `X-Device-Token` 头，否则服务端返回 401。
     */
    suspend fun refreshDeviceToken() {
        try {
            // 注意：这一步必须在白名单接口上执行，不能依赖已有 deviceToken
            val req = Request.Builder()
                .url(configProvider().baseUrl + "/api/need_token")
                .get()
                .build()
            val body = withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { it.body?.string() ?: "" }
            }
            val obj = parse(body)
            val dt = obj.get("device_token")?.takeIf { !it.isJsonNull }?.asString
            if (!dt.isNullOrBlank()) deviceToken = dt
        } catch (_: Exception) {
            // 保持原值；后续请求会因 device token 校验失败而报错，由上层提示
        }
    }

    /** 当前设备令牌（调试/展示用） */
    fun currentDeviceToken(): String = deviceToken

    private fun baseUrl(): String = configProvider().baseUrl

    // ------------------------------------------------------------------ 基础

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (resp.code == 401) {
                // 服务端会在 body 里给出精确原因，例如：
                // bad credential / bad device token / bad signature /
                // missing X-Device-Token header / missing kano-sign header ...
                val reason = try {
                    JsonParser.parseString(body).asJsonObject
                        .get("reason")?.takeIf { !it.isJsonNull }?.asString
                } catch (_: Exception) {
                    null
                }
                throw ApiException(reason?.let { reasonToMessage(it) } ?: "认证失败(401)", 401)
            }
            if (!resp.isSuccessful) {
                throw ApiException("请求失败(${resp.code})：$body", resp.code)
            }
            body
        }
    }

    /** 把服务端的英文 reason 翻译成可读提示 */
    private fun reasonToMessage(reason: String): String = when (reason) {
        "missing kano-t header" -> "请求缺少时间戳"
        "missing kano-sign header" -> "请求缺少签名"
        "bad signature" -> "请求签名校验失败（可能是设备时间不准）"
        "missing X-Device-Token header" -> "缺少设备令牌，请重新连接以获取"
        "bad device token" -> "设备令牌无效，请重新连接"
        "missing Authorization header" -> "缺少控制台口令"
        "bad credential" -> "UFI 控制台口令错误"
        else -> "认证失败：$reason"
    }

    private fun parse(body: String): JsonObject = try {
        JsonParser.parseString(body).asJsonObject
    } catch (e: Exception) {
        JsonObject()
    }

    suspend fun getJson(path: String): JsonObject {
        val req = Request.Builder().url(baseUrl() + path).get().build()
        return parse(execute(req))
    }

    suspend fun postJson(path: String, json: String): JsonObject {
        val req = Request.Builder().url(baseUrl() + path)
            .post(json.toRequestBody(jsonMedia))
            .build()
        return parse(execute(req))
    }

    suspend fun post(path: String): JsonObject {
        val req = Request.Builder().url(baseUrl() + path)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return parse(execute(req))
    }

    // ------------------------------------------------------------------ 设备信息（扩展）

    /**
     * 在设备上执行一条 shell 命令，返回 stdout 文本。
     *
     * 请求体为 `{"cmd":"..."}`，响应形如 `{"content":"...","success":true}`。
     * `success=false` 时（命令不存在、被拒绝等）返回空串，由调用方降级处理。
     *
     * 用途：该固件的 goform / `/api/` 都不提供"每台已连接设备的流量与连接时长"，
     * 只能借 `iw dev <iface> station dump` 取得，见
     * [com.ufitools.client.model.IW_STATION_DUMP_CMD]。
     */
    suspend fun runShell(cmd: String): String = runShellResult(cmd).second

    /**
     * 与 [runShell] 相同，但**保留命令是否成功**这一信息。
     *
     * 为什么需要：`ubus call` 这类命令成功时也可能**没有任何输出**（例如写设置），
     * 只返回 content 就会把"成功但无输出"和"命令失败"混成同一个空串，
     * 调用方无法判断写入到底有没有执行。这里把 `success` 一并交出去。
     *
     * @return `first` = 命令是否成功执行；`second` = stdout（失败时通常为空）
     */
    suspend fun runShellResult(cmd: String): Pair<Boolean, String> {
        val o = postJson("/api/run_shell", gson.toJson(mapOf("cmd" to cmd)))
        val ok = o.get("success")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        val content = o.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
        return ok to content
    }

    /** 检查响应是否成功（无 error 字段即视为成功） */
    private fun JsonObject.isOk(): Boolean = !has("error")

    private fun JsonObject.errMsg(fallback: String): String =
        get("error")?.takeIf { !it.isJsonNull }?.asString ?: fallback

    // ------------------------------------------------------------------ 连接 / 版本

    suspend fun needToken(): Boolean {
        val o = getJson("/api/need_token")
        return o.get("need_token")?.asBoolean
            ?: o.get("enabled")?.asBoolean
            ?: o.get("result")?.asBoolean
            ?: false
    }

    suspend fun versionInfo(): JsonObject = getJson("/api/version_info")

    suspend fun baseDeviceInfo(): JsonObject = getJson("/api/baseDeviceInfo")

    suspend fun connInfo(): JsonObject = getJson("/api/connInfo")

    suspend fun usbStatus(): JsonObject = getJson("/api/usb_status")

    suspend fun selinux(): JsonObject = getJson("/api/SELinux")

    suspend fun deviceId(): JsonObject = getJson("/api/device_id")

    // ------------------------------------------------------------------ 设置（UFI 自身）

    suspend fun setNickname(nickname: String): String {
        val o = postJson("/api/set_nickname", gson.toJson(mapOf("nickname" to nickname)))
        return if (o.isOk()) "success" else o.errMsg("修改别名失败")
    }

    suspend fun setToken(newToken: String): String {
        val o = postJson("/api/set_token", gson.toJson(mapOf("token" to newToken)))
        return if (o.isOk()) "success" else o.errMsg("修改口令失败")
    }

    suspend fun isWeakToken(): JsonObject = getJson("/api/is_weak_token")

    suspend fun getDataLimit(): JsonObject = getJson("/api/get_data_limit")

    suspend fun setDataLimit(params: Map<String, Any>): String {
        val o = postJson("/api/set_data_limit", gson.toJson(params))
        return if (o.isOk()) "success" else o.errMsg("设置流量阈值失败")
    }

    suspend fun setWakelock(enabled: Boolean): String {
        val o = postJson("/api/set_wakelock_status", gson.toJson(mapOf("wakelock_enabled" to enabled)))
        return if (o.isOk()) "success" else o.errMsg("设置唤醒锁失败")
    }

    suspend fun getLogStatus(): JsonObject = getJson("/api/get_log_status")

    suspend fun setLogStatus(enabled: Boolean): String {
        val o = postJson("/api/set_log_status", gson.toJson(mapOf("debug_log_enabled" to enabled)))
        return if (o.isOk()) "success" else o.errMsg("设置日志开关失败")
    }

    suspend fun adbWifiSetting(): JsonObject = getJson("/api/adb_wifi_setting")

    suspend fun setAdbWifi(enabled: Boolean, password: String): String {
        val o = postJson(
            "/api/adb_wifi_setting",
            gson.toJson(mapOf("enabled" to enabled, "password" to password))
        )
        return if (o.isOk()) "success" else o.errMsg("设置无线 ADB 失败")
    }

    suspend fun adbAlive(): JsonObject = getJson("/api/adb_alive")

    // ------------------------------------------------------------------ 语音 / 网络

    suspend fun volteStatus(slot: Int = 0): JsonObject = getJson("/api/volte_status?slot=$slot")

    suspend fun setVolte(enabled: Boolean, slot: Int = 0): String {
        val o = postJson(
            "/api/volte_status",
            gson.toJson(mapOf("enabled" to if (enabled) "1" else "0", "slot" to slot.toString()))
        )
        return if (o.isOk()) "success" else o.errMsg("设置 VoLTE 失败")
    }

    suspend fun vonrStatus(slot: Int = 0): JsonObject = getJson("/api/vonr_status?slot=$slot")

    suspend fun setVonr(enabled: Boolean, slot: Int = 0): String {
        val o = postJson(
            "/api/vonr_status",
            gson.toJson(mapOf("enabled" to if (enabled) "1" else "0", "slot" to slot.toString()))
        )
        return if (o.isOk()) "success" else o.errMsg("设置 VoNR 失败")
    }

    suspend fun supportNrBandList(slot: Int = 0): JsonObject =
        getJson("/api/getSupportNrBandList?slot=$slot")

    suspend fun atCommand(command: String, slot: Int = 0): JsonObject {
        val encoded = URLEncoder.encode(command, "UTF-8")
        return getJson("/api/AT?command=$encoded&slot=$slot")
    }

    // ------------------------------------------------------------------ 短信转发

    suspend fun smsForwardEnabled(): JsonObject = getJson("/api/sms_forward_enabled")

    suspend fun setSmsForwardEnabled(enabled: Boolean): String {
        val o = post("/api/sms_forward_enabled?enable=${if (enabled) 1 else 0}")
        return if (o.isOk()) "success" else o.errMsg("设置短信转发失败")
    }

    suspend fun smsForwardMethod(): JsonObject = getJson("/api/sms_forward_method")
}
