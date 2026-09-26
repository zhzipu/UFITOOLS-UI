package com.ufitools.client.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * 定时任务（API 文档 §9）。
 *
 * 设备侧的 `action` 是一段自由 JSON，不同任务形态差异很大（重启、开关 WiFi…），
 * 这里只抽出界面需要的公共字段，原始 `action` 保留在 [rawAction] 里，
 * 编辑时原样回传，避免把不认识的字段抹掉。
 */
data class ScheduledTask(
    val id: String,
    /** `HH:MM` */
    val time: String,
    val repeatDaily: Boolean,
    /** 人类可读的动作摘要，从 action 里猜出来的 */
    val actionLabel: String,
    /** 原始 action JSON 文本，回传时原样使用 */
    val rawAction: String,
    val lastRunTimestamp: Long? = null,
    val hasTriggered: Boolean = false,
) {
    companion object {
        fun from(el: JsonElement): ScheduledTask? {
            if (!el.isJsonObject) return null
            val o = el.asJsonObject
            val id = o.str("id")
            if (id.isEmpty()) return null
            val actionEl = o.get("action") ?: o.get("actionMap")
            return ScheduledTask(
                id = id,
                time = o.str("time"),
                repeatDaily = o.bool("repeatDaily"),
                actionLabel = summarize(actionEl),
                rawAction = actionEl?.toString() ?: "{}",
                lastRunTimestamp = o.str("lastRunTimestamp").toLongOrNull()?.takeIf { it > 0 },
                hasTriggered = o.bool("hasTriggered"),
            )
        }

        fun collect(root: JsonObject): List<ScheduledTask> {
            val arr = root.get("tasks")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            return arr.mapNotNull { from(it) }
        }

        /** 把 action JSON 压成一句人话，认不出来的就原样截断展示 */
        private fun summarize(action: JsonElement?): String {
            if (action == null || action.isJsonNull) return "未知动作"
            val txt = action.toString()
            val lower = txt.lowercase()
            return when {
                "reboot" in lower || "restart" in lower -> "重启设备"
                "shutdown" in lower || "poweroff" in lower -> "关机"
                "wifi" in lower && ("off" in lower || "\"0\"" in lower) -> "关闭 WiFi"
                "wifi" in lower -> "开启 WiFi"
                "sms" in lower && "send" in lower -> "发送短信"
                else -> txt.take(60)
            }
        }

        private fun JsonObject.str(key: String): String =
            get(key)?.let { if (it.isJsonNull) "" else if (it.isJsonPrimitive) (it as JsonPrimitive).asString else it.toString() } ?: ""

        private fun JsonObject.bool(key: String): Boolean =
            get(key)?.let { if (it.isJsonPrimitive) (it as JsonPrimitive).asBoolean else false } ?: false
    }
}

/** 一天的流量点，用于历史图表 */
data class UsagePoint(val date: String, val bytes: Long)

/**
 * 解析 `/api/cellularUsage?method=date-range` 的返回：
 * `{"result":"success","usage":[{"date":"2026-06-12","bytes":1048576}]}`
 */
fun parseUsagePoints(root: JsonObject): List<UsagePoint> {
    val arr: JsonArray = root.get("usage")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val o = el.asJsonObject
        val date = o.get("date")?.let { if (it.isJsonNull) "" else it.asString } ?: ""
        val bytes = o.get("bytes")?.let {
            when {
                it.isJsonNull -> 0L
                it.isJsonPrimitive -> (it as JsonPrimitive).asString.toLongOrNull() ?: 0L
                else -> 0L
            }
        } ?: 0L
        if (date.isEmpty()) null else UsagePoint(date, bytes)
    }
}

/** 解析 `method=mills-range` 的单值返回：`{"result":"success","usage":"1048576"}` */
fun parseUsageTotal(root: JsonObject): Long =
    root.get("usage")?.let {
        when {
            it.isJsonNull -> 0L
            it.isJsonPrimitive -> (it as JsonPrimitive).asString.toLongOrNull() ?: 0L
            else -> 0L
        }
    } ?: 0L

/** 上传目录里的一个文件（API 文档 §11） */
data class UploadedFile(
    val name: String,
    val size: Long,
    val mtime: Long,
    val url: String,
) {
    val isImage: Boolean
        get() = name.lowercase().let {
            it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") ||
                it.endsWith(".gif") || it.endsWith(".webp") || it.endsWith(".bmp")
        }

    companion object {
        fun collect(root: JsonObject): List<UploadedFile> {
            val arr = root.get("files")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val name = o.get("name")?.let { if (it.isJsonNull) "" else it.asString } ?: ""
                if (name.isEmpty()) return@mapNotNull null
                UploadedFile(
                    name = name,
                    size = o.long("size"),
                    mtime = o.long("mtime"),
                    url = o.get("url")?.let { if (it.isJsonNull) "" else it.asString } ?: "/uploads/$name",
                )
            }
        }

        private fun JsonObject.long(key: String): Long =
            get(key)?.let {
                if (it.isJsonPrimitive) (it as JsonPrimitive).asString.toLongOrNull() ?: 0L else 0L
            } ?: 0L
    }
}

/** 插件商店里的一个插件（API 文档 §14） */
data class StorePlugin(
    val publicName: String,
    val displayName: String,
    val version: String,
    val author: String,
    val description: String,
    val type: String,
    val installed: Boolean,
    /** 已安装插件的卸载名（可能与 publicName 不同） */
    val installName: String = "",
) {
    val isLua: Boolean get() = publicName.endsWith(".lua", ignoreCase = true)

    companion object {
        fun from(el: JsonElement, installedNames: Set<String> = emptySet()): StorePlugin? {
            if (!el.isJsonObject) return null
            val o = el.asJsonObject
            val pub = o.sAny("public_name", "publicName", "file", "name")
            if (pub.isEmpty()) return null
            val installName = o.sAny("install_name", "installed_name", "name")
            val flag = o.s("installed").let { it == "true" || it == "1" }
            return StorePlugin(
                publicName = pub,
                displayName = o.sAny("display_name", "title", "name").ifEmpty { pub },
                version = o.sAny("version", "ver"),
                author = o.sAny("author", "uploader"),
                description = o.sAny("description", "desc", "intro"),
                type = o.sAny("type", "category"),
                installed = flag || pub in installedNames || installName in installedNames,
                installName = installName,
            )
        }

        /** 从 store / list 响应里收集，兼容多个可能的数组键名 */
        fun collect(root: JsonObject, installedNames: Set<String> = emptySet()): List<StorePlugin> {
            val arr = sequenceOf("plugins", "list", "data", "store", "items")
                .mapNotNull { root.get(it)?.takeIf { e -> e.isJsonArray }?.asJsonArray }
                .firstOrNull()
                ?: root.entrySet().firstOrNull { it.value.isJsonArray }?.value?.asJsonArray
                ?: return emptyList()
            return arr.mapNotNull { from(it, installedNames) }
        }

        private fun JsonObject.s(key: String): String =
            get(key)?.let {
                when {
                    it.isJsonNull -> ""
                    it.isJsonPrimitive -> (it as JsonPrimitive).asString
                    else -> it.toString()
                }
            } ?: ""

        private fun JsonObject.sAny(vararg keys: String): String =
            keys.firstNotNullOfOrNull { k -> s(k).takeIf { v -> v.isNotEmpty() } } ?: ""
    }
}
