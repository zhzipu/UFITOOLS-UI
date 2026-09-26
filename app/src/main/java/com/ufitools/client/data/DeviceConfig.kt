package com.ufitools.client.data

/** 连接配置：设备地址 + UFI 口令 + 官方后台密码 */
data class DeviceConfig(
    val host: String = "192.168.0.1",
    val port: Int = 2333,
    /** UFI-TOOLS 登录口令（默认 admin） */
    val token: String = "admin",
    /** 中兴官方后台登录密码（用于 goform 写操作） */
    val adminPassword: String = "admin"
) {
    val baseUrl: String get() = "http://$host:$port"

    fun isValid(): Boolean = host.isNotBlank()
}
