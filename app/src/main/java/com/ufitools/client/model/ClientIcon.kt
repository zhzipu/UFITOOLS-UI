package com.ufitools.client.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Watch
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 终端设备图标。
 *
 * 每个图标有稳定的 [key]（用于持久化自定义选择），**不要随意改动已有 key**，
 * 否则用户已保存的自定义图标会失效。
 */
enum class ClientIcon(val key: String, val label: String) {
    PHONE("phone", "手机"),
    TABLET("tablet", "平板"),
    LAPTOP("laptop", "笔记本"),
    DESKTOP("desktop", "台式机"),
    TV("tv", "电视"),
    WATCH("watch", "手表"),
    ROUTER("router", "路由"),
    PRINTER("printer", "打印机"),
    SPEAKER("speaker", "音箱"),
    GAME("game", "游戏机"),
    CAMERA("camera", "摄像头"),
    NAS("nas", "存储"),
    UNKNOWN("unknown", "其他");

    val icon: ImageVector
        get() = when (this) {
            PHONE -> Icons.Filled.Smartphone
            TABLET -> Icons.Filled.Tablet
            LAPTOP -> Icons.Filled.Laptop
            DESKTOP -> Icons.Filled.DesktopWindows
            TV -> Icons.Filled.Tv
            WATCH -> Icons.Filled.Watch
            ROUTER -> Icons.Filled.Router
            PRINTER -> Icons.Filled.Print
            SPEAKER -> Icons.Filled.Speaker
            GAME -> Icons.Filled.SportsEsports
            CAMERA -> Icons.Filled.CameraAlt
            NAS -> Icons.Filled.Storage
            UNKNOWN -> Icons.Filled.DevicesOther
        }

    companion object {
        /** 按持久化的 key 还原；未知/空返回 null，由调用方回退到自动识别 */
        fun byKey(key: String?): ClientIcon? =
            entries.firstOrNull { it.key == key }
    }
}

/**
 * 依据主机名 / 厂商名 / MAC 的 OUI 自动猜测设备图标。
 *
 * 判定顺序即优先级：**具体的形态词（平板/笔记本/台式机）先于泛化的品牌词**，
 * 否则 "OPPO Padmini" 会因为命中品牌词 "oppo" 被误判成手机。
 * 顺序还保证 "xbox" 之类不会被更宽的规则先截走。
 */
fun detectClientIcon(hostname: String, vendor: String, mac: String): ClientIcon {
    val hay = "$hostname $vendor ${ouiVendor(mac)}".lowercase()
    return when {
        hay.hasAny(
            "ipad", "padmini", "tablet", "matepad", "galaxy tab", "mi pad", "redmi pad",
            "tab-", "-tab", "pad-", "-pad", " pad", "平板"
        ) -> ClientIcon.TABLET

        hay.hasAny(
            "macbook", "laptop", "notebook", "thinkpad", "ideapad", "yoga", "vaio", "surface",
            "xps", "gram", "gpd", "win mini", "steam deck", "rog ally", "笔记本", "电脑"
        ) -> ClientIcon.LAPTOP

        hay.hasAny(
            "imac", "macmini", "mac mini", "desktop", "optiplex", "workstation",
            "win-", "-pc", "pc-", "台式"
        ) -> ClientIcon.DESKTOP

        hay.hasAny(
            "ps4", "ps5", "playstation", "xbox", "nintendo", "switch"
        ) -> ClientIcon.GAME

        hay.hasAny(
            "iphone", "phone", "redmi", "poco", "honor", "huawei", "oneplus", "realme",
            "iqoo", "nubia", "meizu", "galaxy", "pixel", "moto", "oppo", "vivo",
            "xiaomi", "find x", "reno", "手机"
        ) -> ClientIcon.PHONE

        hay.hasAny("watch", "手表") -> ClientIcon.WATCH
        hay.hasAny("tv", "bravia", "mitv", "chromecast", "shield", "电视") -> ClientIcon.TV
        hay.hasAny("router", "gateway", "repeater", "openwrt", "路由") -> ClientIcon.ROUTER
        hay.hasAny("print", "epson", "canon", "brother", "打印") -> ClientIcon.PRINTER
        hay.hasAny("speaker", "echo", "homepod", "sonos", "soundbar", "音箱") -> ClientIcon.SPEAKER
        hay.hasAny("camera", "ipc", "doorbell", "nest", "摄像头") -> ClientIcon.CAMERA
        hay.hasAny("nas", "synology", "qnap", "truenas", "存储") -> ClientIcon.NAS
        else -> ClientIcon.UNKNOWN
    }
}

private fun String.hasAny(vararg keys: String): Boolean = keys.any { contains(it) }

/**
 * MAC OUI（前三段）→ 厂商名。
 *
 * 只收录**实测过**的前缀，不做无把握的猜测：猜错比不猜更糟（会把用户的设备标成错的形态）。
 * 新设备接入后可按同样格式补充。注意：
 * - 带隐私随机化的 MAC（首字节第二十六进制位为 2/6/A/E，如 `e2:`）**无法**用 OUI 归属厂商；
 * - 因此本表只是辅助，主力是主机名关键词。
 */
private val OUI_VENDOR = mapOf(
    "c0:2f:cd" to "OPPO",
    "10:3c:59" to "nubia",
    "c8:15:4e" to "GPD"
)

/** 取 MAC 的 OUI 厂商名；无法判定时返回空串 */
fun ouiVendor(mac: String): String {
    val m = mac.trim().lowercase()
    if (m.length < 8) return ""
    return OUI_VENDOR[m.substring(0, 8)] ?: ""
}
