package com.ufitools.client.network

import android.os.Build
import android.util.Log
import com.google.gson.JsonParser
import com.ufitools.client.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 检查 GitHub 上的新版本。
 *
 * **不走** [ApiClient]：那是打给 UFI 设备的（带 kano 签名、baseUrl 指向设备）。
 * 这里要访问 `api.github.com`，所以用独立的 OkHttpClient 与固定 URL。
 *
 * GitHub 公开仓库无需鉴权，但有 60 次/小时的 IP 限流；失败一律返回 null，
 * 界面按「检查失败」处理，不影响任何主流程。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /**
     * 最近一次检查失败的**具体原因**（异常类型 + 消息，或 `HTTP <code>`）。
     *
     * 存在的意义：以前所有失败都一律返回 null，界面只能笼统说一句"检查失败"，
     * 分不清是 DNS 没就绪、超时、还是被 GitHub 限流（403）。
     * 现在把它带回界面/日志，排查才有的放矢。
     */
    @Volatile
    var lastError: String? = null
        private set

    private const val LATEST_API =
        "https://api.github.com/repos/zhzipu/UFITOOLS-UI/releases/latest"

    /** Release 页面；只在「已是最新」时给用户一个可点的去处（可选） */
    const val RELEASES_PAGE = "https://github.com/zhzipu/UFITOOLS-UI/releases"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * 拉取最新 Release 并与 [currentVersion] 比较。
     *
     * @return 成功返回 [UpdateInfo]；网络异常 / 解析失败 / 尚无 Release 时返回 null
     */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        lastError = null
        try {
            val req = Request.Builder()
                .url(LATEST_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "UFITOOLS-UI")
                .build()

            val response = client.newCall(req).execute()
            val body = response.use { resp ->
                if (!resp.isSuccessful) {
                    // ⚠️ 非 2xx 也走"检查失败"，**必须把状态码记下来** ——
                    // 否则 403（GitHub 限流）/502/DNS 失败在界面上长得一模一样，
                    // 完全无从判断是"网络还没好"还是"被限流了"。
                    lastError = "HTTP ${resp.code}"
                    Log.w(TAG, "检查更新失败：HTTP ${resp.code}")
                    ""
                } else {
                    resp.body?.string().orEmpty()
                }
            }
            if (body.isBlank()) {
                if (lastError == null) lastError = "响应体为空"
                return@withContext null
            }

            val o = JsonParser.parseString(body).asJsonObject

            val tag = o.get("tag_name")?.takeIf { !it.isJsonNull }?.asString
                ?: return@withContext null
            val version = UpdateInfo.normalize(tag)

            val url = o.get("html_url")?.takeIf { !it.isJsonNull }?.asString
                ?: RELEASES_PAGE
            val notes = o.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            val published = o.get("published_at")?.takeIf { !it.isJsonNull }?.asString.orEmpty()

            // assets: name / browser_download_url
            val assets = mutableListOf<Triple<String, String, String>>()
            o.getAsJsonArray("assets")?.forEach { el ->
                val a = el.asJsonObject
                val name = a.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                val dl = a.get("browser_download_url")?.takeIf { !it.isJsonNull }?.asString
                    ?: return@forEach
                assets.add(Triple(name, dl, ""))
            }

            val abi = Build.SUPPORTED_ABIS.firstOrNull()
            val picked = UpdateInfo.pickAsset(assets, abi)

            UpdateInfo(
                tagName = tag,
                version = version,
                releaseUrl = url,
                apkUrl = picked?.second,
                apkName = picked?.first,
                notes = notes,
                publishedAt = published,
                hasUpdate = UpdateInfo.isNewer(version, currentVersion)
            )
        } catch (e: Exception) {
            // 冷启动阶段失败多半是网络/DNS 未就绪，但**不能想当然**：
            // 记录异常类型与消息，才能判断到底是 DNS、超时还是 TLS 问题。
            lastError = "${e.javaClass.simpleName}: ${e.message ?: "无消息"}"
            Log.w(TAG, "检查更新失败：$lastError", e)
            null
        }
    }
}
