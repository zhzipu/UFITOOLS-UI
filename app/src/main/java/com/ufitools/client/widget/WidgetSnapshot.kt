package com.ufitools.client.widget

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ufitools.client.model.PowerMode
import com.ufitools.client.model.PowerStatus
import com.ufitools.client.model.bytesToHumanOrZero
// 起个别名：本类里有个同名的 carrierCode 属性，避免与这个函数同名后解析歧义
import com.ufitools.client.model.carrierCode as carrierCodeOf
import com.ufitools.client.model.carrierLabel
import com.ufitools.client.model.firstValidRsrp
import com.ufitools.client.model.formatTemp
import com.ufitools.client.model.networkTypeLabel
import com.ufitools.client.model.signalBarsOf
import java.util.Locale

/**
 * 桌面小组件要展示的一屏数据。
 *
 * 它是 [com.ufitools.client.viewmodel.MainViewModel] 里那些 `JsonObject` 状态的**扁平化投影**，
 * 存在的意义有两点：
 * 1. 能序列化进 SharedPreferences —— 桌面小组件是独立进程/独立生命周期，
 *    拿不到 App 的内存状态，必须先把上一次的数据落盘，`onUpdate` 时立刻渲染出来，
 *    再去拉新数据，避免每次刷新前都是一片空白；
 * 2. 渲染层（[WidgetViews]）只依赖这个类，不再关心字段名与单位换算。
 */
data class WidgetSnapshot(
    /** 设备型号（如 U60Pro） */
    val model: String = "",
    /** 制式，已归一（如 `5G SA`） */
    val networkType: String = "",
    /** 运营商中文名（如 `中国联通`） */
    val carrier: String = "",
    /** 运营商代号（`CMCC`/`CUCC`/`CTCC`/`CBN`，未收录为空）——桌面按它挑 logo */
    val carrierCode: String = "",
    /** 信号格数 0..5 */
    val signalBars: Int = 0,
    /** 信号强度文本，如 `-79.0 dBm` */
    val rsrp: String = "",
    /** 电量百分比，-1 表示读不到 */
    val battery: Int = -1,
    /** 电源三态文案：充电中 / 直供 / 未充电（读不到为空） */
    val powerLabel: String = "",
    /** 是否正在充电（决定要不要画闪电） */
    val charging: Boolean = false,
    /** 温度，如 `52.4℃` */
    val temp: String = "",
    /** CPU 占用，如 `49%` */
    val cpu: String = "",
    /** 运行内存占用，如 `55%` */
    val ram: String = "",
    /** 今日已用流量，如 `12.34 GB` */
    val dayTraffic: String = "",
    /** 本月累计流量 */
    val monthTraffic: String = "",
    /** 是否连着设备（false 时界面走「离线」态） */
    val online: Boolean = false,
    /** 采集失败原因，为空表示正常 */
    val message: String = "",
    /** 本次数据的时间戳（毫秒） */
    val updatedAt: Long = 0L
) {

    /**
     * 数据指纹：**不含** [updatedAt]。
     *
     * App 侧推送前用它判断「数据是否真的变了」，没变就不打扰桌面，
     * 否则每秒一次的轮询会让小组件一直在重绘。
     */
    fun fingerprint(): String = listOf(
        model, networkType, carrier, carrierCode, signalBars.toString(), rsrp, battery.toString(),
        powerLabel, temp, cpu, ram, dayTraffic, monthTraffic,
        online.toString(), message
    ).joinToString("|")

    /** 更新时间 `HH:mm`；没有数据时返回 `--:--` */
    fun updatedClock(): String {
        if (updatedAt <= 0L) return "--:--"
        return java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(updatedAt))
    }

    fun toJson(): String = GSON.toJson(this)

    companion object {

        private val GSON = Gson()

        /** 反序列化；内容损坏时返回 null，由调用方当作「无缓存」处理 */
        fun fromJson(json: String?): WidgetSnapshot? = try {
            if (json.isNullOrBlank()) null else GSON.fromJson(json, WidgetSnapshot::class.java)
        } catch (_: Exception) {
            null
        }

        /**
         * 从 App 已有的原始数据构建快照。
         *
         * 取值规则与仪表盘保持一致（字段名踩过的坑见各 `MainViewModel` 的注释）：
         * - 电量：`baseDeviceInfo.battery` 最可靠，再退 `battery_value` / `battery_vol_percent` / 内核 capacity
         * - 温度：`cpu_temp` 是**毫摄氏度**，交给 [formatTemp] 换算
         * - 流量：`daily_data` / `monthly_data`（字节）
         * - 格数：按 RSRP 推算（设备的 `network_signalbar` 在本固件恒为 5，只能兜底）
         *
         * @param power 电源三态；为 null 时界面不显示充电状态
         */
        fun build(
            base: JsonObject?,
            live: Map<String, String>,
            power: PowerStatus?,
            online: Boolean,
            message: String = ""
        ): WidgetSnapshot {
            val rsrpRaw = firstValidRsrp(live["Z5g_rsrp"], live["lte_rsrp"])

            val battery = listOf(
                base.str("battery"),
                live["battery"],
                live["battery_value"],
                live["battery_vol_percent"],
                power?.capacity?.toString()
            ).firstOrNull { !it.isNullOrBlank() }?.toIntOrNull() ?: -1

            // 运行内存：memInfo 给的是 kB 明细，算占比；缺失则退回 mem_usage（本身就是百分比）
            val memInfo = base?.get("memInfo")?.takeIf { it.isJsonObject }?.asJsonObject
            val memUsedKb = memInfo.num("mem_used_kb")?.takeIf { it > 0 }
            val memTotalKb = memInfo.num("mem_total_kb")?.takeIf { it > 0 }
            val ram = if (memUsedKb != null && memTotalKb != null) {
                "${(memUsedKb / memTotalKb * 100).toInt()}%"
            } else {
                base.num("mem_usage")?.takeIf { it > 0 }?.let { "${it.toInt()}%" } ?: ""
            }

            val cpu = base.num("cpu_usage")?.takeIf { it > 0 }?.let { "${it.toInt()}%" }
                ?: live["cpu_usage"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "${it.toInt()}%" }
                ?: ""

            return WidgetSnapshot(
                model = base.str("model").ifBlank { live["model"] ?: "" },
                networkType = networkTypeLabel(live["network_type"]),
                carrier = carrierLabel(live["network_provider"], live["network_provider_fullname"]),
                carrierCode = carrierCodeOf(
                    live["network_provider"], live["network_provider_fullname"]
                ) ?: "",
                signalBars = signalBarsOf(rsrpRaw, live["network_signalbar"]),
                rsrp = rsrpRaw?.let { String.format(Locale.US, "%.0f dBm", it) } ?: "",
                battery = battery,
                powerLabel = power?.mode?.takeIf { it != PowerMode.UNKNOWN }?.label ?: "",
                charging = power?.mode == PowerMode.CHARGING,
                temp = formatTemp(base.num("cpu_temp")),
                cpu = cpu,
                ram = ram,
                dayTraffic = traffic(base, live, "daily_data", "day_rx_bytes", "day_tx_bytes"),
                monthTraffic = traffic(base, live, "monthly_data", "month_rx_bytes", "month_tx_bytes"),
                online = online,
                message = message,
                updatedAt = System.currentTimeMillis()
            )
        }

        /**
         * 流量：优先用 `baseDeviceInfo` 的 `daily_data` / `monthly_data`（单个字节总数）；
         * 该接口拿不到时，退回 goform 的 `*_rx_bytes` + `*_tx_bytes` 两个方向相加。
         */
        private fun traffic(
            base: JsonObject?,
            live: Map<String, String>,
            baseKey: String,
            rxKey: String,
            txKey: String
        ): String {
            base.num(baseKey)?.let { return bytesToHumanOrZero(it) }
            val rx = live[rxKey]?.trim()?.toDoubleOrNull()
            val tx = live[txKey]?.trim()?.toDoubleOrNull()
            if (rx == null && tx == null) return "-"
            return bytesToHumanOrZero((rx ?: 0.0) + (tx ?: 0.0))
        }

        private fun JsonObject?.str(key: String): String =
            this?.get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrDefault("") }
                ?: ""

        private fun JsonObject?.num(key: String): Double? =
            this?.get(key)?.takeIf { !it.isJsonNull }
                ?.let { runCatching { it.asDouble }.getOrNull() }
    }
}
