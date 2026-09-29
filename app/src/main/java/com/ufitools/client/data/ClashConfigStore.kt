package com.ufitools.client.data

import android.content.Context

/**
 * 猫猫（mihomo external-controller）连接配置。
 *
 * ## 为什么地址不可配
 *
 * 猫猫内核随设备固件一起跑，**永远监听在设备的 LAN 地址上**，
 * 端口也固定是 `9090`。用户填别的地址没有任何意义——
 * 内核不在别的地方。所以地址直接写死成 [DEFAULT_BASE_URL]，
 * 界面上不再提供输入框。
 *
 * 早期版本允许手填地址（还带过 `/ui` 后缀），实践下来只会带来两类问题：
 * 用户把 `/ui` 带上导致请求 404，以及不知道填什么。现在这些都没有了。
 *
 * ## secret 为什么也不填
 *
 * `secret` 写在设备上的 `mihomo/config.yaml` 里，用户无从得知，
 * 只能靠 App 通过 `/api/run_shell` 读出来（见 `ClashProbe`）。
 * 因此界面同样不提供输入框，改为**自动读取 + 自动重试**。
 *
 * @param baseUrl external-controller 的根地址，固定为 [DEFAULT_BASE_URL]。
 *   保留字段（而非直接常量）是为了兼容早期版本留在本地存储里的旧值，
 *   避免升级后读取时出现缺键。
 * @param secret  external-controller 的 `secret`，由 App 自动从设备读出；
 *   留空表示尚未读到（内核未设密码时也是空）。
 */
data class ClashConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val secret: String = "",
) {
    val apiBaseUrl: String get() = baseUrl

    val isValid: Boolean get() = baseUrl.isNotBlank()

    companion object {
        /** 猫猫内核在本机的固定监听地址 */
        const val DEFAULT_BASE_URL = "http://192.168.0.1:9090"

        /**
         * 历史遗留的 UI 路径段。
         *
         * 老版本默认值带过 `/ui`，本地存储里可能还留着。虽然现在地址已经写死，
         * 但首次升级时读到的旧值仍需要剥一次，否则界面上会显示一条多余后缀。
         */
        private val LEGACY_UI_SUFFIXES = listOf("/ui", "/dashboard")

        /** 剥掉历史遗留的 UI 路径段；新填的地址本就不该带，这里只做兜底 */
        fun stripUiSuffix(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (url.isEmpty()) return DEFAULT_BASE_URL
            var changed = true
            while (changed) {
                changed = false
                for (suffix in LEGACY_UI_SUFFIXES) {
                    // 必须保证剥完还剩 `协议 + 主机`，否则 `http://ui` 这种会被剥空
                    val prefixLen = url.indexOf("//").let { if (it < 0) 0 else it + 2 }
                    if (url.length - suffix.length > prefixLen &&
                        url.endsWith(suffix, ignoreCase = true)
                    ) {
                        url = url.dropLast(suffix.length)
                        changed = true
                        break
                    }
                }
            }
            return url.trimEnd('/').ifEmpty { DEFAULT_BASE_URL }
        }

        /** 规范化：补协议头、去尾部斜杠 */
        fun normalize(input: String): String {
            var v = input.trim()
            if (v.isEmpty()) return DEFAULT_BASE_URL
            if (!v.startsWith("http://", true) && !v.startsWith("https://", true)) {
                v = "http://$v"
            }
            return v.trimEnd('/')
        }
    }
}

/**
 * 猫猫面板配置的本地持久化（SharedPreferences）。
 *
 * 现在只剩 `secret` 一个需要持久化的字段——地址是常量，
 * 读到了 secret 就存下来，重连时不必再跑一次 shell。
 */
class ClashConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("ufi_clash_config", Context.MODE_PRIVATE)

    fun load(): ClashConfig {
        // 老版本存过带 `/ui` 的地址，虽然现在地址写死、读出来也不用了，
        // 但历史键留着会让「读旧值」这件事变得含糊，这里顺手清一次。
        if (prefs.contains("base_url")) {
            prefs.edit().remove("base_url").apply()
        }
        return ClashConfig(
            baseUrl = ClashConfig.DEFAULT_BASE_URL,
            secret = prefs.getString("secret", "") ?: "",
        )
    }

    fun save(config: ClashConfig) {
        prefs.edit()
            .putString("secret", config.secret.trim())
            .apply()
    }

    /** 只更新 secret（自动从设备读到后调用），其余字段不变 */
    fun saveSecret(secret: String) {
        prefs.edit().putString("secret", secret.trim()).apply()
    }

    /** 是否已经成功探测过一次面板（用于决定首帧是否显示引导） */
    fun wasConnected(): Boolean = prefs.getBoolean("was_connected", false)

    fun setWasConnected(value: Boolean) {
        prefs.edit().putBoolean("was_connected", value).apply()
    }
}
