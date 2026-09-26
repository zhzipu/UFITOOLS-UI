package com.ufitools.client.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * APN 配置档。
 *
 * 对应 API 文档 §7。设备上有两套 APN：`/api/apn/auto`（运营商自动下发，只读）
 * 与 `/api/apn/manual`（用户自建，可增删改并切换启用）。两套返回的字段名一致，
 * 用 [fromAuto] / [fromManual] 解析以便区分来源。
 *
 * ⚠️ 与设备交互时字段名是 `profilename`（全小写、无下划线），
 * 但设备**回读**时可能给 `profileName`——[wire] 统一带原样键值提交，
 * 见 [toBody]。
 */
data class ApnProfile(
    /** 档位标识，切换启用与增删改都用它 */
    val profileId: String,
    val name: String,
    val apn: String,
    val username: String = "",
    val password: String = "",
    /** IP / IPv4v6 / IPv6 */
    val pdpType: String = "",
    /** 鉴权方式：0=无，1=PAP，2=CHAP */
    val pppAuthMode: String = "",
    val roamingPdpType: String = "",
    /** 是否当前启用（仅 manual 有意义） */
    val enabled: Boolean = false,
    /** 来源：true=运营商自动下发（只读） */
    val fromAuto: Boolean = false,
) {
    /** 提交给设备时的 JSON 体（字段名与 API 文档 §7 一致） */
    fun toBody(): Map<String, Any> = buildMap {
        put("profileId", profileId)
        put("profilename", name)
        put("wanapn", apn)
        put("username", username)
        put("password", password)
        put("pdpType", pdpType)
        put("pppAuthMode", pppAuthMode)
        put("roamingPdpType", roamingPdpType)
    }

    companion object {
        private const val T_AUTH_NONE = "0"
        private const val T_AUTH_PAP = "1"
        private const val T_AUTH_CHAP = "2"

        fun authLabel(mode: String): String = when (mode) {
            T_AUTH_NONE -> "无"
            T_AUTH_PAP -> "PAP"
            T_AUTH_CHAP -> "CHAP"
            "" -> "-"
            else -> mode
        }

        /** 可选鉴权方式（供下拉框使用，值为提交给设备的原始值） */
        val AUTH_OPTIONS: List<Pair<String, String>> = listOf(
            T_AUTH_NONE to "无",
            T_AUTH_PAP to "PAP",
            T_AUTH_CHAP to "CHAP",
        )

        val PDP_OPTIONS: List<String> = listOf("IP", "IPv4v6", "IPv6")

        private fun JsonObject.s(key: String): String =
            get(key)?.let { el: JsonElement ->
                when {
                    el.isJsonNull -> ""
                    el.isJsonPrimitive -> (el as JsonPrimitive).asString
                    else -> el.toString()
                }
            } ?: ""

        /** 兼容 `profilename` / `profileName` 两种键名 */
        private fun JsonObject.sAny(vararg keys: String): String =
            keys.firstNotNullOfOrNull { k -> s(k).takeIf { it.isNotEmpty() } } ?: ""

        fun from(el: JsonElement, fromAuto: Boolean, enabledId: String? = null): ApnProfile? {
            if (!el.isJsonObject) return null
            val o = el.asJsonObject
            val id = o.sAny("profileId", "profileid", "ProfileId", "id")
            if (id.isEmpty()) return null
            return ApnProfile(
                profileId = id,
                name = o.sAny("profilename", "profileName", "name"),
                apn = o.sAny("wanapn", "apn", "WanApn"),
                username = o.sAny("username", "user"),
                password = o.sAny("password", "passwd"),
                pdpType = o.sAny("pdpType", "pdp_type"),
                pppAuthMode = o.sAny("pppAuthMode", "auth_mode"),
                roamingPdpType = o.sAny("roamingPdpType", "roaming_pdp_type"),
                enabled = enabledId != null && enabledId == id,
                fromAuto = fromAuto,
            )
        }

        /**
         * 从 `/api/apn/all` 这类整体响应里收集所有档位。
         * 兼容返回顶层数组、`{"apn_list":[...]}`、`{"data":[...]}` 三种形态。
         */
        fun collect(root: JsonObject, fromAuto: Boolean, enabledId: String? = null): List<ApnProfile> {
            val arr = sequenceOf("apn_list", "list", "data", "apns", "profiles")
                .mapNotNull { root.get(it)?.takeIf { e -> e.isJsonArray }?.asJsonArray }
                .firstOrNull()
                ?: return emptyList()
            return arr.mapNotNull { from(it, fromAuto, enabledId) }
        }
    }
}
