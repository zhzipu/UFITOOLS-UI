package com.ufitools.client.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 为每个请求自动附加 UFI-TOOLS 认证头。
 *
 * 服务端（U60Pro / UFI-TOOLS 0.3.2）实测要求的头：
 * 1. `kano-t`        —— 毫秒时间戳
 * 2. `kano-sign`     —— HMAC 签名，待签串 = "minikano" + METHOD(大写) + path(不含 query) + 时间戳
 * 3. `authorization` —— **SHA256(控制台口令) 小写 hex**（不是口令原文）
 * 4. `X-Device-Token`—— **必填**，值取自免鉴权接口 `/api/need_token` 返回的 `device_token`
 *
 * 缺任一项服务端都会返回 401 并在 body 里给出 reason：
 *   missing kano-t header / missing kano-sign header / bad signature /
 *   missing X-Device-Token header / bad device token / missing Authorization header / bad credential
 *
 * @param tokenProvider    返回控制台口令（明文）
 * @param deviceTokenProvider 返回设备令牌（device_token），需先调用 /api/need_token 获取
 */
class SigningInterceptor(
    private val tokenProvider: () -> String,
    private val deviceTokenProvider: () -> String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val t = System.currentTimeMillis().toString()
        val method = request.method.uppercase()
        val path = request.url.encodedPath
        val sign = Crypto.kanoSign(Crypto.REQUEST_SECRET, "minikano$method$path$t")

        val builder = request.newBuilder()
            .header("kano-t", t)
            .header("kano-sign", sign)
            .header("authorization", Crypto.sha256Hex(tokenProvider()))

        // X-Device-Token 为设备级令牌；获取失败时留空，服务端会提示 missing/bad device token
        val deviceToken = deviceTokenProvider()
        if (deviceToken.isNotBlank()) {
            builder.header("X-Device-Token", deviceToken)
        }

        return chain.proceed(builder.build())
    }
}
