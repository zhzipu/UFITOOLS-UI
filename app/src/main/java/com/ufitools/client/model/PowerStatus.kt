package com.ufitools.client.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 设备电源模式（三态）。
 *
 * 该固件（U60Pro / BD_CNMU5250V1.0.0B31）的 goform 里**没有任何可用的充电标志**：
 *
 * - `battery_charging` 恒为 `"0"` —— 实测插着 PD 充电器（vbus 8902mV）时依然返回 `0`；
 * - `current_now` 的**符号与充放电无关** —— 插着充电器实测 `-4028µA`，拔掉时同样为负值。
 *
 * 所以旧判定（`current_now > 50mA` 才算充电）在任何情况下都会得出「未充电」。
 *
 * ## 判据优先级（2026-09 实测更新）
 *
 * ### 首选：设备自己的 ubus 服务（经 `/api/run_shell` 调用）
 *
 * - `ubus call zwrt_bsp.charger list`
 *   - `direct_power_supply_mode` —— **「直供」的权威开关**，`enable` / `disable`。
 *     这是唯一能明确表达"直供"的字段，goform 与内核节点都没有。
 *   - `charger_connect`  —— 是否插着外部电源（`1` / `0`）
 *   - `charge_status`    —— `1` 表示正在充电
 * - `ubus call zwrt_bsp.battery list` —— `battery_capacity` 电量、`battery_temperature` 温度
 *
 * 这套接口即 zwrt-datad 项目 `power.direct_supply` 的底层（同机型 MU5250），
 * 已在真机验证：写 `direct_power_supply_mode` 后立刻读回可确认变更。
 *
 * ### 回退：内核 power_supply 节点
 *
 * 仅当 ubus 不可用（固件无 `/bin/ubus` / `run_shell` 被禁用）时使用：
 * `battery/status`（`Charging` / `Discharging` / `Not charging` / `Full`）
 * 配合 `charger_zte/present_mbb`（是否插入外部电源）。
 *
 * 两条路径归一到同一套三态：
 *
 * | 判据 | 结论 |
 * |---|---|
 * | `direct_power_supply_mode == enable` | **直供**（用户开启的旁路供电）|
 * | `charger_connect == 0`（或 `Discharging`）| **未充电** |
 * | 插着电源且正在充电 | **充电中** |
 * | 插着电源但电池不充（`Not charging` / `Full`）| **直供**（由适配器直接供电）|
 */
enum class PowerMode(val label: String) {
    /** 插着外部电源，且电池正在充电 */
    CHARGING("充电中"),

    /** 插着外部电源，但电池不充电（旁路直供 / 已充满），系统由适配器供电 */
    DIRECT("直供"),

    /** 未接外部电源，设备由电池供电 */
    UNPLUGGED("未充电"),

    /** ubus 与内核节点都读不到（未连接 / 固件不支持），界面回退到旧的电流判定 */
    UNKNOWN("未知")
}

/**
 * 一次电源状态读取的结果。
 *
 * @param mode           三态结论
 * @param externalPresent 是否检测到外部电源
 * @param capacity       电量百分比（0..100），读不到为 null
 * @param rawStatus      内核上报的原始 status 串，便于排查
 */
data class PowerStatus(
    val mode: PowerMode,
    val externalPresent: Boolean,
    val capacity: Int? = null,
    val rawStatus: String = ""
)

/**
 * 读取电源三态的 shell 命令。
 *
 * 三段用 `@@@` 分隔，避免不同节点输出互相粘连；节点不存在时 `cat` 报错被吞掉，
 * 对应段留空，由 [parsePowerStatus] 判为 [PowerMode.UNKNOWN]。
 */
const val POWER_STATUS_CMD: String =
    "cat /sys/class/power_supply/battery/status 2>/dev/null; echo '@@@'; " +
        "cat /sys/class/power_supply/charger_zte/present_mbb 2>/dev/null; echo '@@@'; " +
        "cat /sys/class/power_supply/battery/capacity 2>/dev/null"

/**
 * 电源三态的**首选**读取命令：ubus 两段 + 内核节点三段，同样用 `@@@` 分隔。
 *
 * - 段 0：`zwrt_bsp.charger list` → `direct_power_supply_mode` / `charger_connect` / `charge_status`
 * - 段 1：`zwrt_bsp.battery list` → `battery_capacity`
 * - 段 2..4：内核节点，与 [POWER_STATUS_CMD] 完全一致，仅作回退
 *
 * 设备没有 `/bin/ubus` 时前两段为空，由 [parsePowerStatusUbus] 自动落到内核判据，
 * 因此这条命令在所有固件上都能用（最差等价于 [POWER_STATUS_CMD]）。
 */
const val POWER_UBUS_CMD: String =
    "ubus call zwrt_bsp.charger list 2>/dev/null; echo '@@@'; " +
        "ubus call zwrt_bsp.battery list 2>/dev/null; echo '@@@'; " +
        "cat /sys/class/power_supply/battery/status 2>/dev/null; echo '@@@'; " +
        "cat /sys/class/power_supply/charger_zte/present_mbb 2>/dev/null; echo '@@@'; " +
        "cat /sys/class/power_supply/battery/capacity 2>/dev/null"

private const val SEP = "@@@"

/**
 * 解析 [POWER_STATUS_CMD] 的输出。
 *
 * 注意 `Discharging` **包含** `charging` 子串，所以必须用前缀精确匹配、且先判放电。
 */
fun parsePowerStatus(text: String): PowerStatus {
    if (text.isBlank()) return PowerStatus(PowerMode.UNKNOWN, false)
    val parts = text.split(SEP)
    val rawStatus = parts.getOrNull(0)?.trim().orEmpty()
    val present = parts.getOrNull(1)?.trim() == "1"
    val capacity = parts.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }

    // status 读不到 ⇒ 未知，交由界面回退旧判定，不要在这里瞎猜
    if (rawStatus.isBlank()) return PowerStatus(PowerMode.UNKNOWN, present, capacity)

    val s = rawStatus.lowercase()
    val mode = when {
        s.startsWith("not charging") -> if (present) PowerMode.DIRECT else PowerMode.UNPLUGGED
        s.startsWith("full") -> if (present) PowerMode.DIRECT else PowerMode.UNPLUGGED
        s.startsWith("discharging") -> PowerMode.UNPLUGGED
        s.startsWith("charging") -> PowerMode.CHARGING
        // 少数固件把 status 报成数字：1 Charging / 2 Discharging / 3 Not charging / 4 Full
        rawStatus == "1" -> PowerMode.CHARGING
        rawStatus == "2" -> PowerMode.UNPLUGGED
        rawStatus == "3" || rawStatus == "4" ->
            if (present) PowerMode.DIRECT else PowerMode.UNPLUGGED
        // 未知取值：以"是否插着外部电源"兜底
        else -> if (present) PowerMode.DIRECT else PowerMode.UNPLUGGED
    }
    return PowerStatus(mode, present, capacity, rawStatus)
}

/**
 * 解析 [POWER_UBUS_CMD] 的输出：**ubus 优先，内核节点回退**。
 *
 * 之所以以 ubus 为准：只有它能给出 `direct_power_supply_mode`，
 * 从而把「插着电源但电池不充」这一态从"充电"里区分出来，明确显示为**直供**。
 * 内核对 `Not charging` / `Full` 只能推断，遇到 `charge_status` 的未知取值也没有更多信息。
 */
fun parsePowerStatusUbus(text: String): PowerStatus {
    if (text.isBlank()) return PowerStatus(PowerMode.UNKNOWN, false)
    val parts = text.split(SEP)
    val charger = parseJsonObject(parts.getOrNull(0))
    val battery = parseJsonObject(parts.getOrNull(1))

    if (charger != null) {
        val direct = charger.field("direct_power_supply_mode") == "enable"
        val connect = charger.field("charger_connect") == "1"
        val chargeState = charger.field("charge_status")
        val mode = when {
            // 直供是用户显式开启的旁路供电模式，优先级最高：此时电池本就不该被充
            direct -> PowerMode.DIRECT
            // 没插外部电源，只能由电池供电
            !connect -> PowerMode.UNPLUGGED
            chargeState == "1" -> PowerMode.CHARGING
            // 插着电源却没在充电 ⇒ 旁路直供（含已充满）
            else -> PowerMode.DIRECT
        }
        val capacity = battery?.field("battery_capacity")
            ?.toIntOrNull()?.takeIf { it in 0..100 }
        return PowerStatus(
            mode, connect, capacity,
            "charger{direct=$direct, connect=$connect, charge_status=$chargeState}"
        )
    }

    // ubus 不可用：把内核三段拼回 [POWER_STATUS_CMD] 的格式，复用同一套判据
    val sysfs = (2..4).joinToString(SEP) { parts.getOrNull(it)?.trim().orEmpty() }
    return parsePowerStatus(sysfs)
}

/** 把 ubus 的一段文本输出解析成 JSON 对象；空 / 非 JSON 返回 null */
private fun parseJsonObject(raw: String?): JsonObject? {
    if (raw.isNullOrBlank()) return null
    return try {
        JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) {
        null
    }
}

/** 取字段字符串值；字段缺失或为 null 时返回 null（数字也会转成字符串，如 `1` → `"1"`） */
private fun JsonObject.field(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.asString
