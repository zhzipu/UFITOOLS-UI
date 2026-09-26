package com.ufitools.client.network

import android.os.Build
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
        try {
            val req = Request.Builder()
                .url(LATEST_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "UFITOOLS-UI")
                .build()

            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            } ?: return@withContext null

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
        } catch (_: Exception) {
            null
        }
    }
}
