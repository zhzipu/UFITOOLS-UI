package com.ufitools.client.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 设备 ubus 通道（经 `/api/run_shell` 执行 `ubus call`）。
 *
 * ### 为什么在有 goform 的情况下还要它
 *
 * 1. **写入可校验**。goform 的 `goform_set_cmd_process` 是「投递即成功」——
 *    实测提交**不存在的 goformId**、非法参数、甚至错的 AD 签名，设备一律回
 *    `{"result":"success"}`，因此**响应码无法用来判断设置是否真的生效**。
 *    ubus 写完可以直接用对应的 `list` / `get` 方法读回核对（本项目已在
 *    `direct_power_supply_mode` 上实测：写 `enable` → 读回 `enable` → 复原成功）。
 * 2. **绕开登录**。goform 写操作要先走 `SHA256(SHA256(密码)+LD)` 登录拿会话，
 *    口令大小写、cookie 名称、一次性 LD 任何一环不对都是静默失败；
 *    ubus 走 `run_shell`，与 goform 登录完全无关。
 * 3. **字段更权威**。有些状态 goform / sysfs 根本没有，例如「直供」只有
 *    `zwrt_bsp.charger` 的 `direct_power_supply_mode` 会给。
 *
 * 参考实现：zwrt-datad 项目的 `rust/src/control.rs`（已适配本机型 MU5250）。
 *
 * ### 可用性
 * 依赖固件里存在 `/bin/ubus` 与对应服务对象；`run_shell` 不可用的固件上
 * 所有调用都会返回 [Result.ok] = false，调用方据此回退到 goform / 内核节点。
 */
class UbusClient(private val api: ApiClient) {

    /**
     * 一次 ubus 调用的结果。
     *
     * @param ok  `run_shell` 上报的命令是否成功执行。注意**成功也可能没有输出**：
     *            写设置类方法（如 `charger set`）成功时不打印任何东西，
     *            所以不能拿 `raw` 是否为空来判断成败。
     * @param raw stdout 原文（读方法通常是 JSON，写方法通常为空串）
     */
    data class Result(val ok: Boolean, val raw: String) {

        /** 把输出当 JSON 对象解析；空输出或非 JSON 返回 null */
        fun json(): JsonObject? = try {
            JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        }

        /** 取字符串字段（不存在 / JsonNull → null） */
        fun str(key: String): String? =
            json()?.get(key)?.takeIf { !it.isJsonNull }?.asString
    }

    /**
     * 调用 `ubus call <service> <method> ['{...}']`。
     *
     * 参数一律按**字符串**下发（ubus CLI 的 JSON 参数只认字符串/数字字面量，
     * 而本机型这些接口的入参都是 String 类型，见 `ubus -v list` 输出的签名）。
     *
     * @param args 为空时不带参数体，直接 `ubus call service method`
     */
    suspend fun call(
        service: String,
        method: String,
        args: Map<String, String> = emptyMap()
    ): Result {
        // 服务名/方法名来自代码常量，这里仍做一次白名单校验，避免将来传入外部字符串造成注入
        if (!IDENT.matches(service) || !IDENT.matches(method)) return Result(false, "")

        val cmd = buildCommand(service, method, args)
        return try {
            val (ok, content) = api.runShellResult(cmd)
            Result(ok, content.trim())
        } catch (_: Exception) {
            // 连接失败 / 令牌失效等：交给调用方回退
            Result(false, "")
        }
    }

    private fun buildCommand(
        service: String,
        method: String,
        args: Map<String, String>
    ): String {
        if (args.isEmpty()) return "ubus call $service $method"
        val body = args.entries.joinToString(",") { (k, v) ->
            // shell 单引号串里不能直接出现单引号，用 '\'' 的标准写法断开再拼接
            "\"$k\":\"${v.replace("'", "'\\''")}\""
        }
        return "ubus call $service $method '{$body}'"
    }

    private companion object {
        /** 允许出现在 ubus 服务名/方法名里的字符（如 `zwrt_bsp.charger`、`zte_nwinfo_api`） */
        val IDENT = Regex("^[A-Za-z0-9_.:-]+$")
    }
}
