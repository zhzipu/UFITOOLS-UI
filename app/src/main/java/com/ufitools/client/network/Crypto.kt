package com.ufitools.client.network

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * UFI-TOOLS 请求签名 / 摘要工具。
 *
 * 与服务端 KanoUtils 保持一致：
 * - authorization = SHA256(口令) 小写 hex
 * - kano-sign     = HmacSignature(secret, "minikano" + METHOD + PATH + timestamp)
 */
object Crypto {

    /** 请求签名密钥，与服务端 REQUEST_SECRET_KEY 一致 */
    const val REQUEST_SECRET = "minikano_kOyXz0Ciz4V7wR0IeKmJFYFQ20jd"

    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256Hex(input: String): String = sha256(input.toByteArray(Charsets.UTF_8)).toHex()

    /**
     * 大写 hex 变体，**专供中兴 goform 的登录口令与 AD 签名**。
     *
     * 设备自带 Web 控制台的 `SHA256()` 固定输出大写字母表（`requests.js` 顶部
     * `var l = 8, d = 1` → `d ? "0123456789ABCDEF" : ...`），因此设备端按**大写**比对：
     * - 登录：`password = SHA256(SHA256(后台密码) + LD)`
     * - 写操作：`AD = SHA256(SHA256(wa_inner_version + cr_version) + RD)`
     *
     * 这两处若用小写的 [sha256Hex]，服务端一律返回 `{"result":"1"}`（登录失败、
     * 不签发 kano-cookie），表现就是"设置项点了没反应"。
     *
     * 注意：UFI-TOOLS 自己的 `authorization` 头用的是小写 [sha256Hex]，两者不要互串。
     */
    fun sha256HexUpper(input: String): String = sha256Hex(input).uppercase()

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hmacMd5(key: String, data: String): ByteArray {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacMD5"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    /**
     * UFI-TOOLS 的 kano-sign 签名算法：
     * 1. HMAC-MD5(key=secret, message=data) -> 16 字节
     * 2. 对半拆成两份(各 8 字节)
     * 3. 分别 SHA256 -> 各 32 字节
     * 4. 拼接后再次 SHA256 -> 32 字节，输出小写 hex
     */
    fun kanoSign(secret: String, data: String): String {
        val md5 = hmacMd5(secret, data)
        val mid = md5.size / 2
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(md5, 0, mid)
        val s1 = digest.digest()
        digest.update(md5, mid, md5.size - mid)
        val s2 = digest.digest()
        digest.update(s1)
        digest.update(s2)
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v shr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }
}
