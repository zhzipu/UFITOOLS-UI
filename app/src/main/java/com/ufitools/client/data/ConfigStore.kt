package com.ufitools.client.data

import android.content.Context

/** 连接配置的本地持久化（SharedPreferences） */
class ConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("ufi_client_config", Context.MODE_PRIVATE)

    fun load(): DeviceConfig = DeviceConfig(
        host = prefs.getString("host", "192.168.0.1") ?: "192.168.0.1",
        port = prefs.getInt("port", 2333),
        token = prefs.getString("token", "admin") ?: "admin",
        adminPassword = prefs.getString("admin_password", "admin") ?: "admin"
    )

    fun save(config: DeviceConfig) {
        prefs.edit()
            .putString("host", config.host.trim())
            .putInt("port", config.port)
            .putString("token", config.token)
            .putString("admin_password", config.adminPassword)
            .apply()
    }

    /** 读取自动刷新间隔，缺省为 [RefreshInterval.DEFAULT]。 */
    fun loadRefreshInterval(): RefreshInterval =
        RefreshInterval.byName(prefs.getString("refresh_interval", null))

    fun saveRefreshInterval(interval: RefreshInterval) {
        prefs.edit().putString("refresh_interval", interval.name).apply()
    }

    /**
     * 是否「曾经成功登录过」。
     *
     * 只有在 [connect] 真正认证通过时才置 true，用户主动断开连接或重新填写配置时置 false。
     * 下次启动读到 true 就直接自动登录，跳过连接表单。
     */
    fun wasConnected(): Boolean = prefs.getBoolean("was_connected", false)

    fun setWasConnected(value: Boolean) {
        prefs.edit().putBoolean("was_connected", value).apply()
    }
}
