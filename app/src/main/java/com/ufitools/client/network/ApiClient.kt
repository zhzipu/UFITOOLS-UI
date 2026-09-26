package com.ufitools.client.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ufitools.client.data.DeviceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
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

    /** 上传/下载用长超时客户端：设备写入 500MB 文件可能远超 10s */
    private val ioClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
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

    suspend fun putJson(path: String, json: String): JsonObject {
        val req = Request.Builder().url(baseUrl() + path)
            .put(json.toRequestBody(jsonMedia))
            .build()
        return parse(execute(req))
    }

    suspend fun deleteJson(path: String, json: String? = null): JsonObject {
        val body = (json ?: "").toRequestBody(jsonMedia)
        val req = Request.Builder().url(baseUrl() + path).delete(body).build()
        return parse(execute(req))
    }

    /**
     * 拉取原始字节（用于 PNG 二维码、上传文件的图片本体等）。
     * 失败返回 null，由调用方降级。
     */
    suspend fun getBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(baseUrl() + path).get().build()
            ioClient.newCall(req).execute().use { resp ->
                // 用 if/else 而不是 return@use：use 的作用域返回值就是这里的结果，
                // 直接从 lambda 里 return 会把 null 与字节混在一起不容易看清
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun upload(
        path: String,
        file: File,
        fieldName: String = "file",
        extraFields: Map<String, String> = emptyMap(),
        contentType: String = "application/octet-stream"
    ): JsonObject = withContext(Dispatchers.IO) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(fieldName, file.name, file.asRequestBody(contentType.toMediaType()))
        extraFields.forEach { (k, v) -> builder.addFormDataPart(k, v) }
        val req = Request.Builder().url(baseUrl() + path).post(builder.build()).build()
        parse(ioClient.newCall(req).execute().use { it.body?.string() ?: "" })
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

    /**
     * 设置转发方式。
     *
     * ⚠️ 字段名以 **`sms_forward_method`** 为准（API 文档 §6.3：GET 返回
     * `{"sms_forward_method":"SMTP"/"CURL"/"DINGTALK"}`）。文档没写 POST body 的字段名，
     * 故**两个键都发**，兼容服务端任意一种取值方式。
     */
    suspend fun setSmsForwardMethod(method: String): String {
        val o = postJson(
            "/api/sms_forward_method",
            gson.toJson(mapOf("sms_forward_method" to method, "method" to method))
        )
        return if (o.isOk()) "success" else o.errMsg("设置转发方式失败")
    }

    /** SMTP 邮件转发配置，[params] 字段见 API 文档 §6.3 */
    suspend fun smsForwardMail(): JsonObject = getJson("/api/sms_forward_mail")

    suspend fun setSmsForwardMail(params: Map<String, Any>): String {
        val o = postJson("/api/sms_forward_mail", gson.toJson(params))
        return if (o.isOk()) "success" else o.errMsg("保存邮件转发配置失败")
    }

    /** CURL 转发配置，[params] 至少含 curl_text 且须包含 {{sms-body}} */
    suspend fun smsForwardCurl(): JsonObject = getJson("/api/sms_forward_curl")

    suspend fun setSmsForwardCurl(params: Map<String, Any>): String {
        val o = postJson("/api/sms_forward_curl", gson.toJson(params))
        return if (o.isOk()) "success" else o.errMsg("保存 CURL 转发配置失败")
    }

    /** 钉钉机器人转发配置 */
    suspend fun smsForwardDingtalk(): JsonObject = getJson("/api/sms_forward_dingtalk")

    suspend fun setSmsForwardDingtalk(params: Map<String, Any>): String {
        val o = postJson("/api/sms_forward_dingtalk", gson.toJson(params))
        return if (o.isOk()) "success" else o.errMsg("保存钉钉转发配置失败")
    }

    /** 转发黑名单：phone 为号码列表，keywords 为关键词列表 */
    suspend fun smsForwardBlacklist(): JsonObject = getJson("/api/sms_forward_blacklist")

    suspend fun setSmsForwardBlacklist(params: Map<String, Any>): String {
        val o = postJson("/api/sms_forward_blacklist", gson.toJson(params))
        return if (o.isOk()) "success" else o.errMsg("保存黑名单失败")
    }

    suspend fun powerForwardEnabled(): JsonObject = getJson("/api/power_status_forward_enabled")

    suspend fun setPowerForwardEnabled(enabled: Boolean): String {
        val o = post("/api/power_status_forward_enabled?enable=${if (enabled) 1 else 0}")
        return if (o.isOk()) "success" else o.errMsg("设置电量转发失败")
    }

    // ------------------------------------------------------------------ 蜂窝流量历史（§8）

    /**
     * 查询蜂窝流量用量。
     * @param method `date-range` 按天返回数组；`mills-range` 返回区间总量字符串
     */
    suspend fun cellularUsage(startMs: Long, endMs: Long, method: String): JsonObject =
        getJson("/api/cellularUsage?startTime=$startMs&endTime=$endMs&method=$method")

    // ------------------------------------------------------------------ APN（§7）

    suspend fun apnAll(): JsonObject = getJson("/api/apn/all")

    suspend fun apnMode(): JsonObject = getJson("/api/apn/mode")

    suspend fun setApnMode(mode: Int): String {
        val o = postJson("/api/apn/mode", gson.toJson(mapOf("apn_mode" to mode)))
        return if (o.isOk()) "success" else o.errMsg("切换 APN 模式失败")
    }

    suspend fun apnAuto(): JsonObject = getJson("/api/apn/auto")

    suspend fun apnManual(): JsonObject = getJson("/api/apn/manual")

    suspend fun addApnManual(params: Map<String, Any>): String {
        val o = postJson("/api/apn/manual", gson.toJson(params))
        return if (o.isOk()) "success" else o.errMsg("新增 APN 失败")
    }

    suspend fun updateApnManual(params: Map<String, Any>): String {
        val o = putJson("/api/apn/manual", gson.toJson(params))
        return if (o.isOk()) "success" else o.errMsg("修改 APN 失败")
    }

    suspend fun deleteApnManual(profileId: String): String {
        val o = deleteJson("/api/apn/manual", gson.toJson(mapOf("profileId" to profileId)))
        return if (o.isOk()) "success" else o.errMsg("删除 APN 失败")
    }

    suspend fun enableApnManual(profileId: String): String {
        val o = postJson("/api/apn/manual/enable", gson.toJson(mapOf("profileId" to profileId)))
        return if (o.isOk()) "success" else o.errMsg("启用 APN 失败")
    }

    suspend fun apnManualEnabled(): JsonObject = getJson("/api/apn/manual/enabled")

    suspend fun apnCid(cid: Int): JsonObject = getJson("/api/apn/cid?cid=$cid")

    // ------------------------------------------------------------------ 定时任务（§9）

    suspend fun listTasks(): JsonObject = getJson("/api/list_tasks")

    suspend fun getTask(id: String): JsonObject = getJson("/api/get_task?id=$id")

    suspend fun addTask(json: String): String {
        val o = postJson("/api/add_task", json)
        return if (o.isOk()) "success" else o.errMsg("新增定时任务失败")
    }

    suspend fun removeTask(id: String): String {
        val o = postJson("/api/remove_task", gson.toJson(mapOf("id" to id)))
        val r = o.get("result")?.takeIf { !it.isJsonNull }?.asString ?: ""
        return if (o.isOk() && r != "not_found") "success" else o.errMsg("删除定时任务失败")
    }

    suspend fun clearTasks(): String {
        val o = postJson("/api/clear_task", "{}")
        return if (o.isOk()) "success" else o.errMsg("清空定时任务失败")
    }

    // ------------------------------------------------------------------ WiFi 二维码（§10）

    /** chip: 0=2.4G，1=5G。返回 PNG 字节，失败为 null */
    suspend fun wifiQrcode(chip: Int): ByteArray? = getBytes("/api/wifi/qrcode?chip=$chip")

    // ------------------------------------------------------------------ 上传管理（§11）

    suspend fun uploadImage(file: File): JsonObject = upload("/api/upload_img", file)

    suspend fun uploadFile(file: File, path: String = ""): JsonObject {
        val extra = if (path.isBlank()) emptyMap() else mapOf("path" to path)
        return upload("/api/upload_file", file, extraFields = extra)
    }

    suspend fun listUploads(): JsonObject = getJson("/api/list_uploads")

    suspend fun deleteImage(fileName: String): String {
        val o = postJson("/api/delete_img", gson.toJson(mapOf("file_name" to fileName)))
        return if (o.isOk()) "success" else o.errMsg("删除文件失败")
    }

    suspend fun deleteAllUploads(): String {
        val o = post("/api/delete_all_uploads_data")
        return if (o.isOk()) "success" else o.errMsg("清空上传目录失败")
    }

    // ------------------------------------------------------------------ 主题 / 自定义头部（§12）

    suspend fun getTheme(): JsonObject = getJson("/api/get_theme")

    suspend fun setTheme(json: String): String {
        val o = postJson("/api/set_theme", json)
        return if (o.isOk()) "success" else o.errMsg("保存主题失败")
    }

    suspend fun getCustomHead(): JsonObject = getJson("/api/get_custom_head")

    suspend fun setCustomHead(text: String): String {
        val o = postJson("/api/set_custom_head", gson.toJson(mapOf("text" to text)))
        return if (o.isOk()) "success" else o.errMsg("保存自定义头部失败")
    }

    // ------------------------------------------------------------------ 插件商店（§14）

    suspend fun pluginsStore(): JsonObject = getJson("/api/plugins_store")

    suspend fun pluginList(type: String = "", search: String = ""): JsonObject {
        val q = buildString {
            if (type.isNotBlank()) append("type=${URLEncoder.encode(type, "UTF-8")}&")
            if (search.isNotBlank()) append("search=${URLEncoder.encode(search, "UTF-8")}")
        }.trimEnd('&')
        return getJson("/api/plugin/list" + if (q.isBlank()) "" else "?$q")
    }

    suspend fun pluginInstall(publicName: String, installDir: String = ""): String {
        val o = postJson(
            "/api/plugin/install",
            gson.toJson(mapOf("public_name" to publicName, "install_dir" to installDir))
        )
        return if (o.isOk()) "success" else o.errMsg("安装插件失败")
    }

    suspend fun pluginUninstall(name: String): String {
        val o = postJson("/api/plugin/uninstall", gson.toJson(mapOf("name" to name)))
        return if (o.isOk()) "success" else o.errMsg("卸载插件失败")
    }

    suspend fun pluginNotifications(): JsonObject = getJson("/api/plugin/notifications")

    suspend fun readPluginNotification(json: String = "{}"): String {
        val o = postJson("/api/plugin/notification/read", json)
        return if (o.isOk()) "success" else o.errMsg("标记通知已读失败")
    }

    suspend fun pluginUpdateCheck(): JsonObject = getJson("/api/plugin/update/check")

    suspend fun pluginChangelog(): JsonObject = getJson("/api/plugin/changelog")

    // ------------------------------------------------------------------ ttyd / ADB（§15）

    suspend fun hasTtyd(port: Int = 1146): JsonObject = getJson("/api/hasTTYD?port=$port")

    suspend fun ttydStatus(): JsonObject = getJson("/api/ttyd/status")

    suspend fun ttydStart(port: Int = 1146): String {
        val o = postJson("/api/ttyd/start", gson.toJson(mapOf("port" to port)))
        return if (o.isOk()) "success" else o.errMsg("启动 ttyd 失败")
    }

    suspend fun ttydStop(): String {
        val o = post("/api/ttyd/stop")
        return if (o.isOk()) "success" else o.errMsg("停止 ttyd 失败")
    }

    suspend fun adbStatus(): JsonObject = getJson("/api/adb/status")

    /** mode 取值如 `debug` / `normal`，见 API 文档 §15 */
    suspend fun setAdbMode(mode: String): String {
        val o = postJson("/api/adb/mode", gson.toJson(mapOf("mode" to mode)))
        return if (o.isOk()) "success" else o.errMsg("切换 ADB 模式失败")
    }

    // ------------------------------------------------------------------ 后台管理（§3）

    suspend fun updateAdminPwd(password: String): String {
        val o = postJson("/api/update_admin_pwd", gson.toJson(mapOf("password" to password)))
        return if (o.isOk()) "success" else o.errMsg("修改后台密码失败")
    }

    suspend fun getResServer(): JsonObject = getJson("/api/get_res_server")

    suspend fun setResServer(url: String): String {
        val o = postJson("/api/set_res_server", gson.toJson(mapOf("res_server" to url)))
        return if (o.isOk()) "success" else o.errMsg("保存资源服务器失败")
    }
}
