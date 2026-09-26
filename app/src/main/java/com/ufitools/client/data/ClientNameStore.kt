package com.ufitools.client.data

import android.content.Context

/**
 * 每台终端的自定义**名称**（本地别名）持久化。
 *
 * 为什么存在本地：设备侧 `station_list` 只**上报** hostname，
 * 固件没有任何「重命名已连接终端」的接口（API 文档里也没有），
 * 所以别名只能由 App 自己存，按 MAC 关联。
 *
 * 约定与 [ClientIconStore] 一致：以 **MAC（小写）为键**；
 * 没有记录 = 用设备上报的 hostname；删除记录即恢复原名。
 */
class ClientNameStore(context: Context) {

    private val prefs = context.getSharedPreferences("ufi_client_names", Context.MODE_PRIVATE)

    /** 读取全部自定义名称：MAC(小写) -> 别名 */
    fun loadAll(): Map<String, String> =
        prefs.all.entries
            .mapNotNull { (k, v) ->
                (v as? String)?.takeIf { it.isNotBlank() }?.let { k.lowercase() to it }
            }
            .toMap()

    /** 保存别名；传空串等价于 [clear]（恢复设备原名） */
    fun save(mac: String, name: String) {
        val key = mac.trim().lowercase()
        val v = name.trim()
        if (v.isEmpty()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, v).apply()
    }

    fun clear(mac: String) {
        prefs.edit().remove(mac.trim().lowercase()).apply()
    }
}
