package com.ufitools.client.model

/**
 * 一次 GitHub Release 检查的结果。
 *
 * @param tagName      原始 tag，形如 `v1.1.0`
 * @param version      去掉 `v` 前缀的版本号，形如 `1.1.0`
 * @param releaseUrl   Release 页面（人工挑选 ABI 用）
 * @param apkUrl       与当前设备 ABI 匹配的 APK 直链；匹配不到时回落到 universal 包
 * @param apkName      匹配到的 APK 文件名，供界面提示
 * @param notes        更新说明（Release body）
 * @param publishedAt  发布时间（原文，不解析）
 * @param hasUpdate    [version] 是否比当前安装的版本新
 */
data class UpdateInfo(
    val tagName: String,
    val version: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val apkName: String?,
    val notes: String,
    val publishedAt: String,
    val hasUpdate: Boolean
) {

    companion object {

        /** 从 `v1.2.3` / `V1.2.3` / `1.2.3-beta` 里取出可比较的版本号 */
        fun normalize(tag: String): String =
            tag.trim().removePrefix("v").removePrefix("V").substringBefore('-')

        /**
         * 比较版本号，[candidate] 是否比 [current] 新。
         *
         * 逐段按数字比；段数不同时缺位按 0 算（`1.2` == `1.2.0`）。
         * 任一段解析不出数字就退化成字符串不等判断，避免直接抛异常。
         */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = normalize(candidate).split('.')
            val b = normalize(current).split('.')
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val x = a.getOrNull(i)?.toIntOrNull()
                val y = b.getOrNull(i)?.toIntOrNull()
                if (x == null || y == null) return normalize(candidate) != normalize(current)
                if (x != y) return x > y
            }
            return false
        }

        /**
         * 从 Release 的 assets 里挑出最适合本机的 APK。
         *
         * 命中优先级：本机 ABI > universal > 任意 APK。
         * `abi` 传 `Build.SUPPORTED_ABIS.firstOrNull()`。
         */
        fun pickAsset(
            assets: List<Triple<String, String, String>>,
            abi: String?
        ): Pair<String, String>? {
            val apks = assets.filter { it.first.endsWith(".apk", ignoreCase = true) }
            if (apks.isEmpty()) return null
            if (abi != null) {
                apks.firstOrNull { it.first.contains(abi, ignoreCase = true) }
                    ?.let { return it.first to it.second }
            }
            apks.firstOrNull { it.first.contains("universal", ignoreCase = true) }
                ?.let { return it.first to it.second }
            return apks.first().let { it.first to it.second }
        }
    }
}
