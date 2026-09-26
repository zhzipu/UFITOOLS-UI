package com.ufitools.client.widget

import android.content.Context

/**
 * 小组件数据的本地缓存。
 *
 * 桌面小组件与 App 是各自独立的生命周期：`onUpdate` 触发时 App 进程很可能并不存在，
 * 而网络拉取需要 1~2 秒。所以每次拿到数据都先落盘，`onUpdate` 时**先用缓存立即渲染**，
 * 再异步拉新数据后重绘——用户点开桌面就能看到内容，而不是一片空白。
 */
class WidgetStore(context: Context) {

    private val prefs = context.getSharedPreferences("ufi_widget", Context.MODE_PRIVATE)

    fun load(): WidgetSnapshot? = WidgetSnapshot.fromJson(prefs.getString(KEY_SNAPSHOT, null))

    fun save(snapshot: WidgetSnapshot) {
        prefs.edit().putString(KEY_SNAPSHOT, snapshot.toJson()).apply()
    }

    /** 指纹变化检测用：上一次推送出去的数据指纹 */
    fun loadFingerprint(): String = prefs.getString(KEY_FINGERPRINT, "") ?: ""

    fun saveFingerprint(fingerprint: String) {
        prefs.edit().putString(KEY_FINGERPRINT, fingerprint).apply()
    }

    private companion object {
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_FINGERPRINT = "fingerprint"
    }
}
