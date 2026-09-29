package com.ufitools.client.data

/**
 * 网页版 UFI-TOOLS 的自动登录凭据。
 *
 * ## 网页版是怎么判定"已登录"的
 *
 * 网页版（设备上 `/script/requests.js` + `main.js`，版本比开源仓库新）把登录态
 * 放在 **localStorage**，而不是 Cookie 或服务端会话：
 *
 * ```js
 * // 登录成功后写入（main.js）
 * localStorage.setItem('kano_sms_pwd',   password.trim())
 * localStorage.setItem('kano_sms_token', SHA256(token.trim()).toLowerCase())
 *
 * // 之后每次发请求前读取（main.js → initRequestData）
 * const PWD   = localStorage.getItem('kano_sms_pwd')
 * const TOKEN = localStorage.getItem('kano_sms_token')
 * if (!PWD) return false                        // 没密码 → 判定未登录
 * if (isNeedToken && !TOKEN) return false        // 需要 token 却没给 → 未登录
 * common_headers.authorization = TOKEN
 * ```
 *
 * 也就是说**只要这两个键存在且正确，网页版就认为已登录**，
 * 根本不会再弹登录框。所以 App 把已存的凭据写进去，就能免登。
 *
 * ## 两个值分别对应 App 的哪个字段
 *
 * | localStorage 键 | 网页版用途 | App 来源 |
 * |---|---|---|
 * | `kano_sms_pwd` | 登录框「密码」，用于 goform `LOGIN` | [DeviceConfig.adminPassword] |
 * | `kano_sms_token` | 登录框「TOKEN」，作为 `authorization` 头 | `sha256Hex(`[DeviceConfig.token]`)` |
 *
 * ⚠️ `kano_sms_token` 是**口令的 sha256 小写 hex**，与 App 的 `SigningInterceptor`
 * 里 `authorization = Crypto.sha256Hex(config.token)` 是**同一个算法、同一个输入**
 * —— 两边的认证体系本来就是同一套（连 `kano-sign` 的密钥都相同）。
 *
 * ⚠️ 网页版还有第三个键 `kano_device_token`（`X-Device-Token` 头），
 * 但**那个不需要 App 注入**：网页版启动时自己会调 `/api/need_token` 拿并写入
 * （`main.js` 的 `needToken()`）。它按会话生成，App 注入反而可能过期，交给网页版自己取。
 *
 * @param password  明文后台密码，写入 `kano_sms_pwd`
 * @param tokenHash 口令的 sha256 小写 hex，写入 `kano_sms_token`
 */
data class WebAutoLogin(
    val password: String,
    val tokenHash: String
)
