package com.ufitools.client.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 用系统浏览器打开链接。
 *
 * 设备/模拟器可能没有浏览器，必须兜底，否则 [Intent] 抛
 * `ActivityNotFoundException` 会直接闪退。
 */
fun openUrl(context: Context, url: String, fallbackTip: String = "无法打开链接") {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(context, "$fallbackTip：${e.message}", Toast.LENGTH_LONG).show()
    }
}
