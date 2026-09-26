package com.ufitools.client.data

import android.content.Context

/**
 * 每台终端自定义图标的本地持久化。
 *
 * 以 **MAC（小写）为键**、[com.ufitools.client.model.ClientIcon.key] 为值。
 * 没有记录时表示"使用自动识别"，因此删除记录即可恢复自动。
 */
class ClientIconStore(context: Context) {

    private val prefs = context.getSharedPreferences("ufi_client_icons", Context.MODE_PRIVATE)

    /** 读取全部自定义图标：MAC(小写) -> iconKey */
    fun loadAll(): Map<String, String> =
        prefs.all.entries
            .mapNotNull { (k, v) -> (v as? String)?.let { k.lowercase() to it } }
            .toMap()

    fun save(mac: String, iconKey: String) {
        prefs.edit().putString(mac.trim().lowercase(), iconKey).apply()
    }

    fun clear(mac: String) {
        prefs.edit().remove(mac.trim().lowercase()).apply()
    }
}
