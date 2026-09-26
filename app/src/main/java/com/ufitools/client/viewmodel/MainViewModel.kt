package com.ufitools.client.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ufitools.client.BuildConfig
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.ufitools.client.data.ClientIconStore
import com.ufitools.client.data.ConfigStore
import com.ufitools.client.data.DeviceConfig
import com.ufitools.client.data.RefreshInterval
import com.ufitools.client.data.ThemeStore
import com.ufitools.client.model.ApnProfile
import com.ufitools.client.model.ClientDevice
import com.ufitools.client.model.ClientIcon
import com.ufitools.client.model.IW_STATION_DUMP_CMD
import com.ufitools.client.model.POWER_UBUS_CMD
import com.ufitools.client.model.PowerMode
import com.ufitools.client.model.PowerStatus
import com.ufitools.client.model.ScheduledTask
import com.ufitools.client.model.SmsMessage
import com.ufitools.client.model.StorePlugin
import com.ufitools.client.model.UploadedFile
import com.ufitools.client.model.UpdateInfo
import com.ufitools.client.model.UsagePoint
import com.ufitools.client.model.detectClientIcon
import com.ufitools.client.model.kickClientCmd
import com.ufitools.client.model.parseIwStationDump
import com.ufitools.client.model.parsePowerStatusUbus
import com.ufitools.client.model.parseUsagePoints
import com.ufitools.client.network.ApiClient
import com.ufitools.client.network.ApiException
import com.ufitools.client.network.GoformClient
import com.ufitools.client.network.UbusClient
import com.ufitools.client.network.UpdateChecker
import com.ufitools.client.ui.theme.ThemeMode
import com.ufitools.client.widget.WidgetBus
import com.ufitools.client.widget.WidgetSnapshot
import com.ufitools.client.widget.WidgetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 连接状态 */
sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting : ConnectionStatus()
    data class Connected(val model: String) : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

/**
 * 短信转发的完整配置（API 文档 §6.3）。
 *
 * 设备把不同转发方式拆成了 4 组接口：方式选择 + 各自的参数。界面需要一次把这些
 * 都读出来，所以这里聚合为一个数据类；保存时按 [method] 只提交对应那一组。
 */
data class SmsForwardConfig(
    /** `SMTP` / `CURL` / `DINGTALK` */
    val method: String = "SMTP",
    // --- SMTP ---
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val smtpTo: String = "",
    val smtpUsername: String = "",
    val smtpPassword: String = "",
    val mailForwardDevInfo: Boolean = false,
    // --- CURL ---
    val curlText: String = "",
    val curlForwardDevInfo: Boolean = false,
    // --- 钉钉 ---
    val dingtalkWebhook: String = "",
    val dingtalkSecret: String = "",
    val dingtalkForwardDevInfo: Boolean = false,
    // --- 黑名单 ---
    val blacklistPhones: String = "",
    val blacklistKeywords: String = "",
) {
    companion object {
        val METHODS = listOf(
            "SMTP" to "邮件",
            "CURL" to "CURL 请求",
            "DINGTALK" to "钉钉机器人",
        )

        fun methodLabel(v: String): String = METHODS.firstOrNull { it.first == v }?.second ?: v

        private fun JsonObject.s(key: String): String =
            get(key)?.let { if (it.isJsonNull) "" else it.asString } ?: ""

        private fun JsonObject.boolish(key: String): Boolean =
            s(key).let { it == "1" || it.equals("true", true) || it.equals("on", true) }

        /** 从四组接口的响应里拼出完整配置 */
        fun merge(
            methodObj: JsonObject?,
            mail: JsonObject?,
            curl: JsonObject?,
            dingtalk: JsonObject?,
            blacklist: JsonObject?,
        ): SmsForwardConfig {
            val methodRaw = methodObj?.s("method").orEmpty()
                .ifEmpty { methodObj?.s("type").orEmpty() }
            return SmsForwardConfig(
                method = methodRaw.ifEmpty { "SMTP" }.uppercase(),
                smtpHost = mail?.s("smtp_host").orEmpty(),
                smtpPort = mail?.s("smtp_port").orEmpty().ifEmpty { "465" },
                smtpTo = mail?.s("smtp_to").orEmpty(),
                smtpUsername = mail?.s("smtp_username").orEmpty(),
                smtpPassword = mail?.s("smtp_password").orEmpty(),
                mailForwardDevInfo = mail?.boolish("forward_dev_info") ?: false,
                curlText = curl?.s("curl_text").orEmpty(),
                curlForwardDevInfo = curl?.boolish("forward_dev_info") ?: false,
                dingtalkWebhook = dingtalk?.s("webhook_url").orEmpty(),
                dingtalkSecret = dingtalk?.s("secret").orEmpty(),
                dingtalkForwardDevInfo = dingtalk?.boolish("forward_dev_info") ?: false,
                blacklistPhones = blacklist?.s("phone").orEmpty(),
                blacklistKeywords = blacklist?.s("keywords").orEmpty(),
            )
        }
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val configStore = ConfigStore(app)
    private val themeStore = ThemeStore(app)
    private val clientIconStore = ClientIconStore(app)

    /** 终端本地别名存储（设备侧无重命名接口，只能存在 App） */
    private val clientNameStore = com.ufitools.client.data.ClientNameStore(app)

    private val api = ApiClient { config }
    private val goform = GoformClient { config }

    /**
     * 设备 ubus 通道（经 `/api/run_shell`）。
     *
     * 用于那些 goform 做不好或做不到的事：写入后**可回读校验**（goform 的
     * `goform_set_cmd_process` 是投递即成功，响应码没有信息量），
     * 以及只有 ubus 才暴露的字段（如「直供」= `zwrt_bsp.charger` 的
     * `direct_power_supply_mode`）。详见 [UbusClient]。
     */
    private val ubus = UbusClient(api)

    // ------------------------------------------------------------------ 主题

    /** 当前配色 ID：0 默认 / 1 科技蓝 / 2 薄荷绿 / 3 梦幻紫 / 4 活力橙 */
    var paletteId by mutableStateOf(themeStore.loadPaletteId())
        private set

    var themeMode by mutableStateOf(ThemeMode.fromKey(themeStore.loadThemeMode()))
        private set

    fun setPalette(id: Int) {
        paletteId = id
        themeStore.savePaletteId(id)
    }

    /** 应用主题明暗模式并持久化（方法名避开 themeMode 生成的 setter，防止 JVM 签名冲突） */
    fun applyThemeMode(mode: ThemeMode) {
        themeMode = mode
        themeStore.saveThemeMode(mode.key)
    }

    // ------------------------------------------------------------------ 状态

    var config by mutableStateOf(configStore.load())
        private set

    var status by mutableStateOf<ConnectionStatus>(ConnectionStatus.Disconnected)
        private set

    var baseInfo by mutableStateOf<JsonObject?>(null)
        private set
    var live by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var version by mutableStateOf<JsonObject?>(null)
        private set
    var connInfo by mutableStateOf<JsonObject?>(null)
        private set
    var usbStatus by mutableStateOf<JsonObject?>(null)
        private set
    var selinux by mutableStateOf<String?>(null)
        private set

    /** baseDeviceInfo 最新一份（设备信息页扩展字段：CPU/内存/存储等） */
    var baseExtra by mutableStateOf<JsonObject?>(null)
        private set

    var sms by mutableStateOf<List<SmsMessage>>(emptyList())
        private set
    var smsUnread by mutableStateOf(0)
        private set

    /** 已连接终端列表（含每台的已用流量与连接时长） */
    var clients by mutableStateOf<List<ClientDevice>>(emptyList())
        private set

    /**
     * 设备电源三态：充电 / 直供 / 未充电。
     *
     * 该固件的 goform **没有任何可用的充电标志**——`battery_charging` 恒为 `"0"`、
     * `current_now` 的符号与充放电无关（实测插着充电器也是负值）。唯一可靠来源是
     * 经 `/api/run_shell` 读内核节点 `battery/status`，见 [PowerStatus]。
     *
     * null 表示尚未读到（未连接，或固件无该节点），界面此时回退到旧的电流判定。
     */
    var power by mutableStateOf<PowerStatus?>(null)
        private set

    /**
     * 短信转发开关。
     *
     * goform 侧没有对应字段，只能读 UFI-TOOLS 自研接口 `/api/sms_forward_enabled`，
     * 实测返回 `{"enabled":"1"}`——注意**值是字符串** `"0"`/`"1"`，不是布尔。
     */
    var smsForward by mutableStateOf(false)
        private set

    // ------------------------------------------------------------------ 新增功能的可观察状态

    /** 短信转发完整配置：method / mail / curl / dingtalk / blacklist */
    var smsForwardCfg by mutableStateOf<SmsForwardConfig?>(null)
        private set

    /** 电量变化转发开关 */
    var powerForward by mutableStateOf(false)
        private set

    /** 流量历史（按天） */
    var usagePoints by mutableStateOf<List<UsagePoint>>(emptyList())
        private set

    /** APN：运营商自动下发档位（只读） */
    var apnAuto by mutableStateOf<List<ApnProfile>>(emptyList())
        private set

    /** APN：用户自建档位 */
    var apnManual by mutableStateOf<List<ApnProfile>>(emptyList())
        private set

    /** APN 模式：0=自动，1=手动 */
    var apnMode by mutableStateOf(0)
        private set

    /** 定时任务列表 */
    var tasks by mutableStateOf<List<ScheduledTask>>(emptyList())
        private set

    /** 上传目录文件列表 */
    var uploads by mutableStateOf<List<UploadedFile>>(emptyList())
        private set

    /** 插件商店列表 */
    var storePlugins by mutableStateOf<List<StorePlugin>>(emptyList())
        private set

    /** 已安装插件名集合（用于在商店里标记"已安装"） */
    var installedPlugins by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 插件未读通知文本列表 */
    var pluginNotifications by mutableStateOf<List<String>>(emptyList())
        private set

    /** ttyd 运行状态（true=在跑） */
    var ttydRunning by mutableStateOf(false)
        private set

    /** ttyd 监听地址（形如 `192.168.0.1:1146`） */
    var ttydAddress by mutableStateOf("")
        private set

    /** ADB 存活信息（含 mode） */
    var adbInfo by mutableStateOf<JsonObject?>(null)
        private set

    /** 资源服务器地址 */
    var resServer by mutableStateOf("")
        private set

    /** 自定义头部文本 */
    var customHead by mutableStateOf("")
        private set

    /** 弱口令检测结果：null=未知，true=弱口令 */
    var weakToken by mutableStateOf<Boolean?>(null)
        private set

    /** 各页面的加载/错误状态（key = 页面标识） */
    var pageLoading by mutableStateOf<Set<String>>(emptySet())
        private set
    var pageError by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun markLoading(page: String, loading: Boolean) {
        pageLoading = if (loading) pageLoading + page else pageLoading - page
    }

    // ------------------------------------------------------------------ 应用自身更新（GitHub）

    /** 当前安装的版本名，取自 BuildConfig（与 Release tag 同源） */
    val appVersion: String = BuildConfig.VERSION_NAME

    /** 更新检查结果：null = 尚未检查 / 检查失败 */
    var updateInfo by mutableStateOf<UpdateInfo?>(null)
        private set

    /** 是否正在检查更新 */
    var updateChecking by mutableStateOf(false)
        private set

    /** 启动时的自动检查只提示「有新版」，不打扰「已是最新/失败」 */
    var updateDialogDismissed by mutableStateOf(false)

    /**
     * 检查 GitHub 上的新版本。
     *
     * @param silent 启动自动检查传 true：失败或已是最新都静默，只在发现新版时由界面弹窗；
     *               用户在「关于」里手动点则传 false，会返回一句可 Toast 的结果。
     * @return 一句面向用户的结果文案（silent 时也可能为空串）
     */
    suspend fun checkUpdate(silent: Boolean): String {
        if (updateChecking) return ""
        updateChecking = true
        return try {
            val info = UpdateChecker.check(appVersion)
            updateInfo = info
            when {
                info == null ->
                    if (silent) "" else "检查更新失败，请检查网络后重试"
                info.hasUpdate -> {
                    updateDialogDismissed = false
                    "发现新版本 ${info.version}"
                }
                else -> if (silent) "" else "已是最新版本（$appVersion）"
            }
        } catch (e: Exception) {
            if (silent) "" else "检查更新失败：${e.message ?: "未知错误"}"
        } finally {
            updateChecking = false
        }
    }

    /**
     * 记录/清除页面级错误。
     *
     * ⚠️ **不能叫 `setPageError`**：`pageError` 是公开 `var`，Kotlin 会自动生成
     * `setPageError(Map)`，与手写方法在 JVM 上只按名字+参数类型区分 →
     * 重载解析歧义/签名冲突（曾因此导致编译失败）。故用 `reportPageError`。
     */
    fun reportPageError(page: String, msg: String?) {
        pageError = if (msg == null) pageError - page else pageError + (page to msg)
    }

    /** 终端自定义图标：MAC(小写) -> ClientIcon.key；无记录表示沿用自动识别 */
    private var clientIconOverrides by mutableStateOf(clientIconStore.loadAll())

    /** 终端本地别名：MAC(小写) -> 别名；无记录表示沿用设备上报的 hostname */
    private var clientNameOverrides by mutableStateOf(clientNameStore.loadAll())

    /** 最近一次成功刷新的时间（毫秒）；null 表示尚未成功刷新过 */
    var lastUpdated by mutableStateOf<Long?>(null)
        private set

    /** 最近一次刷新的失败原因；null 表示最近一轮是成功的 */
    var lastError by mutableStateOf<String?>(null)
        private set

    /**
     * 是否正在进行「启动自动登录」。
     *
     * 上次会话成功登录过（[ConfigStore.wasConnected]）时，启动即自动连接；
     * 期间界面显示过渡页而不是连接表单，避免表单"闪一下"再被主界面顶掉。
     *
     * 初值直接取自持久化记录（而不是在 init 里再赋值），这样第一帧就已经是"自动登录中"，
     * 不会先闪一帧连接表单；同时也避免了在 composition 期间写状态。
     */
    var autoLogin by mutableStateOf(configStore.wasConnected())
        private set

    private var goformCookie: String? = null
    private var pollJob: Job? = null

    /** 上一次刷新终端列表的时间，用于在 1 秒间隔下给设备留余量 */
    private var lastClientsAt = 0L

    /** 上一次刷新电源状态的时间（同样走 shell，单独限流） */
    private var lastPowerAt = 0L

    /** 上一次刷新短信转发开关的时间（走 /api，单独限流） */
    private var lastSmsForwardAt = 0L

    /** 上一次刷新 WiFi 双频段开关的时间（走 goform 查询命令，单独限流） */
    private var lastWifiBandAt = 0L

    /** 上一次向桌面小组件推送数据的时间（推送很轻，但仍不希望每轮刷新都惊动桌面） */
    private var lastWidgetPushAt = 0L

    // ------------------------------------------------------------------ 终端列表

    /**
     * 刷新已连接终端列表。
     *
     * 数据分两步取，且**第二步允许失败**：
     * 1. goform `station_list`（无需鉴权）→ 名称 / IP / MAC / 频段；
     * 2. `/api/run_shell` 跑 `iw ... station dump` → 每台设备的流量与连接时长。
     *
     * 第 2 步在部分固件上不可用（无 `iw`、无 `run_shell`、权限不足），
     * 此时列表照常展示，只是流量与连接时长显示 `-`，不影响主状态刷新。
     */
    suspend fun refreshClients() {
        val stations = try {
            goform.stationList()
        } catch (_: Exception) {
            return // 连列表都拿不到：保留上一次结果，避免界面被清空
        }

        val stats = try {
            parseIwStationDump(shellWithTokenRetry(IW_STATION_DUMP_CMD))
        } catch (_: Exception) {
            emptyMap()
        }

        clients = stations.mapNotNull { st ->
            val mac = st.str("mac_addr")?.lowercase() ?: return@mapNotNull null
            val s = stats[mac]
            ClientDevice(
                mac = mac,
                ip = st.str("ip_addr") ?: "",
                hostname = st.str("hostname") ?: "",
                connType = st.str("conn_type") ?: "",
                vendor = st.str("vendor") ?: "",
                rxBytes = s?.rxBytes,
                txBytes = s?.txBytes,
                connectedSeconds = s?.connectedSeconds,
                signalDbm = s?.signalDbm,
                iface = s?.iface
            )
        }
    }

    /** 设备令牌过期时重取一次再执行，避免终端列表静默变成空 */
    private suspend fun shellWithTokenRetry(cmd: String): String = try {
        api.runShell(cmd)
    } catch (e: ApiException) {
        if (e.code == 401) {
            api.refreshDeviceToken()
            api.runShell(cmd)
        } else {
            ""
        }
    }

    // ------------------------------------------------------------------ 电源状态

    /**
     * 刷新电源三态（充电 / 直供 / 未充电）。
     *
     * 优先读设备自己的 ubus：`zwrt_bsp.charger list` 给出
     * `direct_power_supply_mode`（「直供」的权威判据）以及 `charger_connect` / `charge_status`，
     * `zwrt_bsp.battery list` 给出电量。ubus 不可用时命令里已附带内核节点，自动回退。
     * 为什么不能靠 goform 字段见 [PowerStatus] 的说明。
     */
    suspend fun refreshPower() {
        val text = try {
            shellWithTokenRetry(POWER_UBUS_CMD)
        } catch (_: Exception) {
            return
        }
        if (text.isBlank()) return
        val parsed = parsePowerStatusUbus(text)
        // 偶发一次读取失败（UNKNOWN）时保留上一次结果，避免状态来回闪烁
        if (parsed.mode != PowerMode.UNKNOWN) power = parsed
    }

    /**
     * 刷新「短信转发」开关。
     *
     * 该状态由 UFI-TOOLS 自研接口提供（goform 没有这个字段），
     * 实测响应 `{"enabled":"1"}`。
     *
     * 读失败时**保留上一次的值**：否则任何一次网络抖动都会把开关画回「关闭」，
     * 看起来就像设备自己把短信转发关掉了。
     */
    suspend fun refreshSmsForward() {
        val o = try {
            api.smsForwardEnabled()
        } catch (_: Exception) {
            return
        }
        val v = o.get("enabled")?.asStringSafe()
        if (!v.isNullOrBlank()) smsForward = v == "1" || v.equals("true", ignoreCase = true)
    }

    /** 两个 WiFi 频段的开关状态（2.4G / 5G），随主轮询更新；null=未知 */
    var wifiBands by mutableStateOf<List<Boolean?>>(listOf(null, null))
        private set

    suspend fun refreshWifiBands() {
        wifiBands = wifiBandStates()
    }

    // ------------------------------------------------------------------ 踢出终端

    /**
     * 踢出一个已连接终端。
     *
     * 设备没有"断开指定客户端"的官方接口，这里用 root shell 执行
     * `iw dev <iface> station del <MAC> subtype 0xC` 发 deauth，见 [kickClientCmd]。
     *
     * @return null 表示指令已成功下发；否则返回失败原因（供界面提示）
     */
    suspend fun kickClient(client: ClientDevice): String? {
        val cmd = kickClientCmd(client.mac, client.iface)
            ?: return "MAC 地址非法，无法踢出"
        return try {
            shellWithTokenRetry(cmd)
            // 指令已下发即返回成功：`iw station del` 成功时无输出、失败才写 stderr，
            // run_shell 只回 stdout，无法进一步区分，故以"随后列表里是否还有它"为准。
            null
        } catch (e: Exception) {
            e.message ?: "踢出失败"
        }
    }

    /** 该终端最终使用的图标：自定义优先，否则按主机名/厂商/OUI 自动识别 */
    fun clientIconOf(c: ClientDevice): ClientIcon =
        ClientIcon.byKey(clientIconOverrides[c.mac])
            ?: detectClientIcon(c.hostname, c.vendor, c.mac)

    /** 设置自定义图标；传 null 表示恢复自动识别 */
    fun setClientIcon(mac: String, icon: ClientIcon?) {
        if (icon == null) {
            clientIconStore.clear(mac)
        } else {
            clientIconStore.save(mac, icon.key)
        }
        clientIconOverrides = clientIconStore.loadAll()
    }

    /**
     * 该终端最终显示的名称：本地别名优先，否则用设备上报的 hostname。
     *
     * 设备**没有**重命名已连接终端的接口，别名只能存在 App 本地（按 MAC 关联），
     * 见 [ClientNameStore]。
     */
    fun clientNameOf(c: ClientDevice): String =
        clientNameOverrides[c.mac.trim().lowercase()]?.takeIf { it.isNotBlank() }
            ?: c.displayName

    /** 该终端已设的本地别名；**未设过返回空串**（区别于 [clientNameOf] 的回落邏辑） */
    fun clientAliasOf(mac: String): String =
        clientNameOverrides[mac.trim().lowercase()].orEmpty()

    /** 设置本地别名；传空串表示恢复设备原名 */
    fun setClientName(mac: String, name: String) {
        clientNameStore.save(mac, name)
        clientNameOverrides = clientNameStore.loadAll()
    }

    // ------------------------------------------------------------------ 字段清单

    companion object {
        /**
         * goform 登录失败时的统一提示。
         *
         * 指向真正可操作的动作：连接页里的「后台密码」是**设备 Web 控制台的管理密码**，
         * 与「控制台口令」是两把不同的钥匙——填成同一个（例如都填控制台口令）时，
         * 登录会被设备回 `result:"1"` 且不下发 cookie，所有走 goform 的设置项都会失败。
         */
        private const val LOGIN_FAILED_MSG =
            "登录设备后台失败：请到「连接」页把「后台密码」填成设备 Web 控制台的管理密码" +
                "（它与「控制台口令」不是同一个）"

        /**
         * goform 轮询字段：信号 + 设备属性 + 状态。
         *
         * ⚠️ 实时吞吐字段名实测为 `real_rx_speed` / `real_tx_speed`（单位 B/s），
         * **不存在** `realtime_rx_thrpt` / `realtime_tx_thrpt`（后者在 U60Pro 固件上请求返回空，
         * 会导致「实时上下行」永远显示 "-"）。同一固件上 `battery` 也不存在，
         * 电量要用 `battery_value` / `battery_vol_percent`。
         * 部分字段（如 `cpu_temp`/`cpu_usage`/`mem_usage`）goform 不提供，只能取 `/api/baseDeviceInfo`，
         * 这里一并请求只是兼容其他固件，取不到不影响。
         */
        val POLL_FIELDS = listOf(
            // 5G
            "Z5g_rsrp", "Nr_snr", "nr_rsrq", "Nr_bands", "Nr_fcn", "Nr_bands_widths",
            "Nr_pci", "nr_rssi", "Nr_cell_id",
            // 4G
            "lte_rsrp", "Lte_snr", "lte_rsrq", "Lte_bands", "Lte_fcn", "Lte_bands_widths",
            "Lte_pci", "lte_rssi", "Lte_cell_id", "lte_ca_status", "nr_ca_status",
            // 网络状态 + 实时速率
            "network_type", "network_provider", "network_provider_fullname", "rssi",
            "network_signalbar", "ppp_status",
            "real_rx_speed", "real_tx_speed",
            "day_rx_bytes", "day_tx_bytes", "month_rx_bytes", "month_tx_bytes",
            "total_rx_bytes", "total_tx_bytes",
            "data_volume_limit_switch", "data_volume_limit_size", "data_volume_alert_percent",
            "wifi_access_sta_num", "ipv6_wan_ipaddr", "ipv4_wan_ipaddr",
            "net_select", "lte_band_lock", "nr_band_lock",
            // 设置页开关的真实状态。注意 `queryWiFiModuleSwitch` 是查询命令，
            // 设备回的是 `WiFiModuleSwitch` / `BandSteeringSwitch` 两个字段（"1"/"0"）；
            // 漫游取值为 "on"/"off"，读回以 `dial_roam_setting_option` 为准（官方 Web 端同此）。
            "queryWiFiModuleSwitch", "roam_setting_option", "dial_roam_setting_option",
            "neighbor_cell_info", "locked_cell_info", "sim_slot", "dual_sim_support",
            "sms_unread_num", "battery", "battery_value", "battery_vol_percent",
            "battery_charging", "battery_temperature", "uptime",
            // 设备标识
            "imei", "iccid", "imsi", "msisdn", "mac_address", "lan_ipaddr",
            "cr_version", "wa_inner_version",
            // 温度 / 占用（部分固件会通过 goform 返回，取不到则回退 baseDeviceInfo）
            "cpu_temp", "cpu_usage", "mem_usage"
        )

        /**
         * 终端列表（`iw station dump` 走 shell，比普通轮询重）最小刷新间隔。
         *
         * 自动刷新选到「1 秒」时，主状态仍每秒更新，但终端列表最快 3 秒刷一次，
         * 避免每秒都往设备上打一次 shell；手动刷新不受此限制。
         */
        const val CLIENTS_MIN_INTERVAL_MS = 3_000L

        /**
         * 电源三态的最小刷新间隔。
         *
         * 它走 `run_shell` 调 ubus（`zwrt_bsp.charger` / `zwrt_bsp.battery`），比 goform 轮询重，
         * 但充放电与直供切换需要及时反映，所以比终端列表的 3 秒更短；手动刷新不受限。
         */
        const val POWER_MIN_INTERVAL_MS = 2_000L

        /** 设备网络信息的 ubus 服务名（`nwinfo_get_netinfo` / `nwinfo_set_netselect`） */
        private const val NWINFO_SERVICE = "zte_nwinfo_api"

        /**
         * 切换网络模式后回读确认的次数与间隔。
         * 切换要重新搜网、不会立刻生效，所以最多等 5 × 800ms ≈ 4 秒；
         * 超时不报失败，只如实提示"可能正在重新搜网"。
         */
        private const val NET_MODE_VERIFY_ATTEMPTS = 5
        private const val NET_MODE_VERIFY_DELAY_MS = 800L

        /**
         * 向桌面小组件推送数据的最小间隔。
         *
         * 推送本身很轻（只写 SharedPreferences + 三条进程内广播，不产生网络请求），
         * 但流量等数值会持续缓慢变化，太频繁会让桌面上不断重绘。
         */
        const val WIDGET_PUSH_MIN_INTERVAL_MS = 10_000L

        /**
         * 短信转发开关的最小刷新间隔。
         *
         * 它走 `/api/sms_forward_enabled`（goform 没有这个字段），请求很轻，
         * 但状态几乎不变，没必要跟着「1 秒」档一起跑。
         */
        const val SMS_FORWARD_MIN_INTERVAL_MS = 5_000L

        /**
         * WiFi 双频段开关状态的最小刷新间隔。
         *
         * 走 goform `queryAccessPointInfo`（返回整个 AP 配置，比单字段查询重），
         * 而频段开关几乎不会变，没必要跟着秒级轮询跑。
         */
        const val WIFI_BAND_MIN_INTERVAL_MS = 10_000L

        /** 安全取值：null / JsonNull → ""，原始类型取其字符串，对象/数组取 toString。 */
        private fun JsonElement?.asStringSafe(): String = when {
            this == null || isJsonNull -> ""
            isJsonPrimitive -> (this as JsonPrimitive).asString
            else -> toString()
        }
    }

    // ------------------------------------------------------------------ 连接

    fun updateConfig(newConfig: DeviceConfig) {
        config = newConfig
        configStore.save(newConfig)
        // 换了配置（地址/口令），旧的"已登录"记录不再可信，等这次真正认证通过再写回
        configStore.setWasConnected(false)
        goformCookie = null
        lastClientsAt = 0L
        lastPowerAt = 0L
        stopPolling()
        status = ConnectionStatus.Disconnected
        baseInfo = null
        live = emptyMap()
        version = null
        sms = emptyList()
        clients = emptyList()
        power = null
        lastUpdated = null
        lastError = null
    }

    suspend fun connect(): ConnectionStatus {
        status = ConnectionStatus.Connecting
        return try {
            // 先取设备令牌（/api/need_token 免鉴权），后续请求的 X-Device-Token 依赖它
            api.refreshDeviceToken()

            val v = api.versionInfo()
            val model = v.get("model")?.takeIf { !it.isJsonNull }?.asString ?: config.host
            version = v
            // 认证自检：baseDeviceInfo 需要鉴权（口令 + 设备令牌 + 签名）
            try {
                api.baseDeviceInfo()
            } catch (e: ApiException) {
                if (e.code == 401) {
                    status = ConnectionStatus.Error("认证失败：${e.message ?: "UFI 口令错误"}")
                    return status
                }
            }
            status = ConnectionStatus.Connected(model)
            // 记下"已成功登录过"，下次冷启动直接自动进入主界面
            configStore.setWasConnected(true)
            refreshAll()
            startPolling()
            status
        } catch (e: Exception) {
            status = ConnectionStatus.Error(e.message ?: "连接失败")
            status
        }
    }

    fun disconnect() {
        stopPolling()
        // 用户主动断开：下次启动不再自动登录，回到连接表单
        configStore.setWasConnected(false)
        status = ConnectionStatus.Disconnected
    }

    /** 自动刷新间隔（含"关闭"选项），持久化到本地。 */
    var refreshInterval by mutableStateOf(configStore.loadRefreshInterval())
        private set

    /** 开关自动刷新（方法名避开 refreshInterval 生成的 setter，防止 JVM 签名冲突） */
    fun applyRefreshInterval(interval: RefreshInterval) {
        refreshInterval = interval
        configStore.saveRefreshInterval(interval)
        stopPolling()
        if (interval.enabled && status is ConnectionStatus.Connected) startPolling()
    }

    private fun startPolling() {
        // 用 isActive 判断：任务已结束（异常退出）时允许重新拉起
        if (pollJob?.isActive == true) return
        val interval = refreshInterval
        if (!interval.enabled) return
        pollJob = viewModelScope.launch {
            while (isActive && status is ConnectionStatus.Connected) {
                // 每轮重新读取，保证运行中改间隔立即生效
                val current = refreshInterval
                if (!current.enabled) break
                // 单轮异常不能让轮询整体退出，否则界面会"不再实时更新"
                try {
                    refreshAll()
                } catch (_: Exception) {
                }
                delay(current.millis)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ------------------------------------------------------------------ 数据刷新

    suspend fun refreshAll(forceClients: Boolean = false) {
        // /api/ 走设备令牌鉴权，令牌失效时自动重取一次再试，避免轮询从此静默失效
        val baseOk = try {
            baseInfo = fetchBaseInfo()
            true
        } catch (_: Exception) {
            false
        }
        val liveOk = try {
            val obj = goform.get(POLL_FIELDS, multiData = true)
            live = obj.entrySet().associate { (k, v) -> k to v.asStringSafe() }
            smsUnread = live["sms_unread_num"]?.toIntOrNull() ?: 0
            true
        } catch (_: Exception) {
            false
        }

        if (baseOk || liveOk) {
            lastUpdated = System.currentTimeMillis()
            lastError = null
        } else {
            lastError = "刷新失败：检查与设备的连接或口令"
        }

        // 终端列表独立刷新：它依赖 shell 取流量，失败也不该把整轮标记为失败。
        // 1 秒间隔下按 CLIENTS_MIN_INTERVAL_MS 限流；手动刷新（forceClients）不受限。
        val now = System.currentTimeMillis()
        if (forceClients || now - lastClientsAt >= CLIENTS_MIN_INTERVAL_MS) {
            lastClientsAt = now
            refreshClients()
        }

        // 电源三态（充电/直供/未充电）同样依赖 shell，单独按更短的间隔限流。
        // 充放电切换要及时反映，所以 2 秒而不是跟终端列表一样 3 秒。
        if (forceClients || now - lastPowerAt >= POWER_MIN_INTERVAL_MS) {
            lastPowerAt = now
            refreshPower()
        }

        // 短信转发开关：goform 不提供，走 /api 单独取，按更长的间隔限流。
        // WiFi / 漫游两个开关的状态已随 POLL_FIELDS 一起拿到，无需额外请求。
        if (forceClients || now - lastSmsForwardAt >= SMS_FORWARD_MIN_INTERVAL_MS) {
            lastSmsForwardAt = now
            refreshSmsForward()
        }

        // WiFi 双频段开关：goform 的 queryAccessPointInfo 不在 POLL_FIELDS 里
        // （它是查询命令、响应较重），单独按更长间隔取，避免影响主轮询节奏。
        if (forceClients || now - lastWifiBandAt >= WIFI_BAND_MIN_INTERVAL_MS) {
            lastWifiBandAt = now
            try {
                refreshWifiBands()
            } catch (_: Exception) {
            }
        }

        // 数据有了就同步给桌面小组件：直接用内存里刚拿到的值，不再多发一次请求
        if (baseOk || liveOk) pushWidgetSnapshot()
    }

    /**
     * 把本轮数据投影成小组件快照推给桌面。
     *
     * 两道闸：**指纹去重**（数据没变就不打扰桌面，否则 1 秒档刷新会让小组件一直重绘）+
     * **最小间隔**（流量数值会缓慢变化，10 秒推一次足够）。
     */
    private fun pushWidgetSnapshot() {
        val now = System.currentTimeMillis()
        if (now - lastWidgetPushAt < WIDGET_PUSH_MIN_INTERVAL_MS) return

        val app = getApplication<Application>()
        val snapshot = WidgetSnapshot.build(baseInfo, live, power, online = true)
        val store = WidgetStore(app)
        if (snapshot.fingerprint() == store.loadFingerprint()) return

        lastWidgetPushAt = now
        store.save(snapshot)
        store.saveFingerprint(snapshot.fingerprint())
        WidgetBus.notifyAll(app)
    }

    /** 取 baseDeviceInfo；遇 401（设备令牌过期/失效）重取令牌后重试一次 */
    private suspend fun fetchBaseInfo(): JsonObject = try {
        api.baseDeviceInfo()
    } catch (e: ApiException) {
        if (e.code == 401) {
            api.refreshDeviceToken()
            api.baseDeviceInfo()
        } else {
            throw e
        }
    }

    /** 手动刷新（界面按钮调用）：终端列表也一起强刷 */
    fun refreshAllNow() {
        viewModelScope.launch { refreshAll(forceClients = true) }
    }

    suspend fun refreshDeviceExtra() {
        try {
            connInfo = api.connInfo()
        } catch (_: Exception) {
        }
        try {
            usbStatus = api.usbStatus()
        } catch (_: Exception) {
        }
        try {
            val s = api.selinux()
            selinux = s.get("SELinux")?.asStringSafe().takeIf { !it.isNullOrBlank() }
                ?: s.get("status")?.asStringSafe()
        } catch (_: Exception) {
        }
        try {
            baseExtra = api.baseDeviceInfo()
        } catch (_: Exception) {
        }
    }

    suspend fun refreshSms() {
        try {
            val obj = goform.smsList()
            val arrEl = obj.get("messages") ?: obj.get("Messages")
            val list = mutableListOf<SmsMessage>()
            if (arrEl != null && arrEl.isJsonArray) {
                for (e in arrEl.asJsonArray) {
                    if (!e.isJsonObject) continue
                    val o = e.asJsonObject
                    val id = o.str("id")
                    val number = o.str("number") ?: o.str("address") ?: ""
                    val content = o.str("content") ?: ""
                    val tag = o.str("tag") ?: "2"
                    val date = o.str("date") ?: o.str("time") ?: ""
                    if (id != null && content.isNotEmpty()) {
                        list.add(SmsMessage(id, number, content, tag, date))
                    }
                }
            }
            sms = list
        } catch (_: Exception) {
        }
    }

    private fun JsonObject.str(key: String): String? = get(key)?.asStringSafe()?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------ goform 写操作

    private suspend fun ensureCookie(): String? {
        if (!goformCookie.isNullOrBlank()) return goformCookie
        goformCookie = goform.login(config.adminPassword)
        return goformCookie
    }

    private fun isSuccess(res: JsonObject): Boolean {
        if (res.has("error")) return false
        val r = res.get("result")?.asStringSafe()
        return r.isNullOrBlank() || r == "0" || r.equals("success", true) || r.equals("ok", true)
    }

    suspend fun goformAction(goformId: String, params: Map<String, String>): String {
        // goform 是所有写操作的公共入口，这里兜住网络异常，
        // 避免任一界面按钮因超时/断连把 App 带崩（见 safe 的说明）。
        return try {
            // 登录失败的两张脸：本服务(U60Pro) 返回 "1"、老 ZTE 固件返回 "3"。
            // 之前只认 "3"，于是 "1" 会被当成成功、继续带着空 cookie 往下走，
            // 最后报一个"操作失败(1)"之类的含糊结果——现在统一按登录失败处理。
            var cookie = ensureCookie() ?: return LOGIN_FAILED_MSG
            var res = goform.post(goformId, params, cookie)
            var r = res.get("result")?.asStringSafe() ?: ""
            if (r == "1" || r == "3" || r.equals("failure", true)) {
                goformCookie = null
                cookie = ensureCookie() ?: return LOGIN_FAILED_MSG
                res = goform.post(goformId, params, cookie)
                r = res.get("result")?.asStringSafe() ?: ""
            }
            when {
                isSuccess(res) -> "success"
                // 重试后仍是这两个码：会话没拿到，根子还是后台密码
                r == "1" || r == "3" -> LOGIN_FAILED_MSG
                else -> res.get("error")?.asStringSafe() ?: "操作失败($r)"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "操作失败：${e.message ?: "网络异常"}"
        }
    }

    // ------------------------------------------------------------------ 设置动作

    suspend fun setNickname(nickname: String): String = safe {
    api.setNickname(nickname)
    }

    suspend fun changeToken(newToken: String): String = safe {
    api.setToken(newToken)
    }

    /**
     * 唤醒锁。
     *
     * 设备**没有对应的读接口**（`/api/get_wakelock_status` 实测 404），开关状态无法回读，
     * 界面已移除入口。方法保留，以备后续找到可回读的来源后再接回界面。
     */
    suspend fun setWakelock(enabled: Boolean): String = safe {
    api.setWakelock(enabled)
    }

    suspend fun setAdbWifi(enabled: Boolean, password: String): String = safe {
    api.setAdbWifi(enabled, password)
    }

    suspend fun setDataLimit(params: Map<String, Any>): String = safe {
    api.setDataLimit(params)
    }

    /**
     * 开关短信转发。⚠️ 不叫 `setSmsForward`——`smsForward` 是公开 `var`，
     * 其自动生成的 setter 会与手写方法在 JVM 上撞名。命名约定见 [reportPageError]。
     */
    suspend fun applySmsForward(enabled: Boolean): String = safe {
    api.setSmsForwardEnabled(enabled)
    }

    suspend fun volteStatus(): JsonObject = api.volteStatus()

    suspend fun vonrStatus(): JsonObject = api.vonrStatus()

    suspend fun setVolte(enabled: Boolean): String = safe {
    api.setVolte(enabled)
    }

    suspend fun setVonr(enabled: Boolean): String = safe {
    api.setVonr(enabled)
    }

    /**
     * 设置蜂窝网络模式优先级，取值见 [com.ufitools.client.model.NetworkMode]。
     *
     * 两条路，**ubus 优先**：
     * 1. `zte_nwinfo_api nwinfo_set_netselect`（经 run_shell）—— 写完可回读 `net_select`
     *    校验，且完全不依赖 goform 登录；见 [setNetworkModeByUbus]。
     * 2. 回退 goform `SET_BEARER_PREFERENCE`（需登录 Cookie + AD 签名，由 [goformAction] 负责）。
     *
     * 返回 `"success"` 表示已切到目标档位；其它为提示文案。
     */
    suspend fun setBearerPreference(value: String): String = safe {
            setNetworkModeByUbus(value)
                ?: goformAction("SET_BEARER_PREFERENCE", mapOf("BearerPreference" to value))
    }

    /**
     * 经 ubus 切换网络模式。
     *
     * @return `"success"` 或提示文案；**null 表示这条路走不通**（设备无 `ubus`、
     *         `run_shell` 被禁用、命令本身失败），由调用方回退 goform ——
     *         这样在没有 ubus 的固件上行为与旧版一致，不会更差。
     *
     * 为什么必须回读：`nwinfo_set_netselect` 对**非法取值是静默忽略**的
     * （实测传 `XXX_FAKE` 既不报错、状态也不变），只看命令是否执行成功会误判成功。
     * 另外切换要重新搜网、不会瞬间生效，所以只做有限次轮询，超时也不谎报成功。
     */
    private suspend fun setNetworkModeByUbus(value: String): String? {
        val write = ubus.call(NWINFO_SERVICE, "nwinfo_set_netselect", mapOf("net_select" to value))
        if (!write.ok) return null

        var current: String? = null
        repeat(NET_MODE_VERIFY_ATTEMPTS) { i ->
            if (i > 0) delay(NET_MODE_VERIFY_DELAY_MS)
            current = ubus.call(NWINFO_SERVICE, "nwinfo_get_netinfo").str("net_select")
            if (current == value) return "success"
        }
        return "指令已下发，设备尚未切到该模式（当前 ${current ?: "未知"}），可能正在重新搜网"
    }

    /**
     * 性能模式。
     *
     * 设备**不支持该设置**：goform 取不到 `performance_mode` 字段（多次探测均为空），
     * 官方 Web 端也没有这个入口，状态无从回读、写入也无从验证。界面已移除入口，
     * 方法保留以备后续在设备侧找到对应档位后再接回。
     */
    suspend fun setPerformanceMode(on: Boolean): String = safe {
            goformAction("PERFORMANCE_MODE_SETTING", mapOf("performance_mode" to if (on) "1" else "0"))
    }

    /** 旧名保留，行为与 [setBearerPreference] 完全一致 */
    suspend fun setNetworkMode(value: String): String = safe {
    setBearerPreference(value)
    }

    suspend fun setBandLock(lte: String, nr: String): String {
        var msg = "success"
        if (lte.isNotBlank()) {
            msg = goformAction("LTE_BAND_LOCK", mapOf("lte_band_lock" to lte))
        }
        if (nr.isNotBlank() && msg == "success") {
            msg = goformAction("NR_BAND_LOCK", mapOf("nr_band_lock" to nr))
        }
        return msg
    }

    suspend fun lockCell(pci: String, earfcn: String, rat: String): String = safe {
            goformAction("CELL_LOCK", mapOf("pci" to pci, "earfcn" to earfcn, "rat" to rat))
    }

    suspend fun unlockCell(): String = safe {
    goformAction("UNLOCK_ALL_CELL", emptyMap())
    }

    /**
     * WiFi 开关。
     *
     * 与官方 Web 端一致，**两个方向走的是不同命令**：
     * - 关闭：`switchWiFiModule` + `SwitchOption=0`
     * - 开启：`switchWiFiChip` + `ChipEnum=chip1|chip2` + `GuestEnable=0`
     *   （`SwitchOption=1` 不在官方实现里，用它开不回来）
     *
     * chip1 = 2.4G（ChipIndex 0）、chip2 = 5G（ChipIndex 1）。本界面只有一个开关、
     * 无法像官方那样选频段，因此开启时优先沿用**当前处于开启状态**的那个芯片，
     * 两个都已关闭时默认开 5G。
     */
    suspend fun setWifi(on: Boolean): String {
        if (!on) return goformAction("switchWiFiModule", mapOf("SwitchOption" to "0"))

        val chip = try {
            val o = goform.get(listOf("queryAccessPointInfo"), multiData = true)
            val index = o.getAsJsonArray("ResponseList")
                ?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
                ?.firstOrNull { it.get("AccessPointSwitchStatus")?.asStringSafe() == "1" }
                ?.get("ChipIndex")?.asStringSafe()
            if (index == "0") "chip1" else "chip2"
        } catch (_: Exception) {
            "chip2"
        }
        return goformAction("switchWiFiChip", mapOf("ChipEnum" to chip, "GuestEnable" to "0"))
    }

    /**
     * 按频段开关 WiFi（API 文档 §5.2）。
     *
     * @param chip 0 = 2.4G（`chip1`），1 = 5G（`chip2`）
     *
     * 与 [setWifi] 的差别：那个是「总开关、不分频段」，这个精确指定芯片。
     * 关闭某频段用 `switchWiFiChip` + `SwitchOption=0`（官方 Web 端的频段级开关写法），
     * 开启用 `switchWiFiChip` + `ChipEnum` + `GuestEnable=0`。
     */
    suspend fun setWifiBand(band: Int, on: Boolean): String {
        val chip = if (band == 0) "chip1" else "chip2"
        return if (on) {
            goformAction("switchWiFiChip", mapOf("ChipEnum" to chip, "GuestEnable" to "0"))
        } else {
            goformAction("switchWiFiChip", mapOf("ChipEnum" to chip, "SwitchOption" to "0"))
        }
    }

    /**
     * 读取两个频段的开关状态。
     *
     * 返回 `[2.4G, 5G]`，元素为 null 表示该频段未知。
     * 数据源：goform `queryAccessPointInfo` → `ResponseList[].AccessPointSwitchStatus`
     * + `ChipIndex`；回退字段 `WiFiModuleSwitch`（整体开关，无法区分频段）。
     */
    suspend fun wifiBandStates(): List<Boolean?> = try {
        val o = goform.get(listOf("queryAccessPointInfo"), multiData = true)
        val list = o.getAsJsonArray("ResponseList")
            ?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
            ?: emptyList()
        if (list.isEmpty()) {
            val all = o.get("WiFiModuleSwitch")?.asStringSafe()
            listOf(all?.let { it == "1" }, all?.let { it == "1" })
        } else {
            listOf(0, 1).map { idx ->
                list.firstOrNull { it.get("ChipIndex")?.asStringSafe() == idx.toString() }
                    ?.get("AccessPointSwitchStatus")?.asStringSafe()
                    ?.let { it == "1" }
            }
        }
    } catch (_: Exception) {
        listOf(null, null)
    }

    /**
     * 指示灯。
     *
     * 设备**不支持该设置**：goform 取不到 `indicator_light_switch` 字段，
     * 官方 Web 端也没有这个入口，状态无从回读。界面已移除入口，方法保留以备后用。
     */
    suspend fun setIndicatorLight(on: Boolean): String = safe {
            goformAction("INDICATOR_LIGHT_SETTING", mapOf("indicator_light_switch" to if (on) "1" else "0"))
    }

    /**
     * 数据漫游开关。
     *
     * 取值是 `on` / `off`（**不是** `1` / `0`，设备读回也是这两个词），
     * 且官方 Web 端两个字段一起传：`roam_setting_option` + `dial_roam_setting_option`
     * （读回时以 `dial_roam_setting_option` 为准）。少传或传错值都会静默无效。
     */
    suspend fun setRoaming(on: Boolean): String {
        val v = if (on) "on" else "off"
        return goformAction(
            "SET_CONNECTION_MODE",
            mapOf(
                "ConnectionMode" to "auto_dial",
                "roam_setting_option" to v,
                "dial_roam_setting_option" to v
            )
        )
    }

    suspend fun setSimSlot(slot: String): String = safe {
            goformAction("SET_SIM_SLOT", mapOf("sim_slot" to slot))
    }

    suspend fun setUsbNetworkProtocol(value: String): String = safe {
            goformAction("SET_USB_NETWORK_PROTOCAL", mapOf("usb_network_protocal" to value))
    }

    suspend fun reboot(): String = safe {
    goformAction("REBOOT_DEVICE", emptyMap())
    }

    suspend fun shutdown(): String = safe {
    goformAction("SHUTDOWN_DEVICE", emptyMap())
    }

    // ------------------------------------------------------------------ 短信

    suspend fun sendSms(number: String, content: String): String {
        val cookie = try {
            ensureCookie()
        } catch (e: Exception) {
            return "登录失败：${e.message ?: "网络异常"}"
        } ?: return LOGIN_FAILED_MSG
        return try {
            val res = goform.sendSms(number, content, cookie)
            if (isSuccess(res)) "success" else (res.get("error")?.asStringSafe() ?: "发送失败")
        } catch (e: Exception) {
            "发送失败：${e.message ?: "网络异常"}"
        }
    }

    suspend fun deleteSms(msgId: String): String {
        val cookie = try {
            ensureCookie()
        } catch (e: Exception) {
            return "登录失败：${e.message ?: "网络异常"}"
        } ?: return LOGIN_FAILED_MSG
        return try {
            val res = goform.deleteSms(msgId, cookie)
            if (isSuccess(res)) "success" else "删除失败"
        } catch (e: Exception) {
            "删除失败：${e.message ?: "网络异常"}"
        }
    }

    suspend fun markSmsRead(msgId: String): String {
        val cookie = try {
            ensureCookie()
        } catch (e: Exception) {
            return "登录失败：${e.message ?: "网络异常"}"
        } ?: return LOGIN_FAILED_MSG
        return try {
            val res = goform.markSmsRead(msgId, cookie)
            if (isSuccess(res)) "success" else "标记失败"
        } catch (e: Exception) {
            "标记失败：${e.message ?: "网络异常"}"
        }
    }

    // ------------------------------------------------------------------ AT 命令

    suspend fun atCommand(command: String): String = safe {
        val o = api.atCommand(command)
        o.get("result")?.asStringSafe() ?: o.toString()
    }

    // ================================================================== 以下为 API 文档对照新增
    // 说明：这些方法全部走 UFI-TOOLS 自研 `/api/` 通道（除标注 goform 的以外），
    // 每个都自带 try/catch，失败时把原因写进 pageError，不影响主轮询。

    /**
     * 页面级加载包装：自动维护 [pageLoading] / [pageError]，异常时返回 [fallback]。
     *
     * 为什么不 inline：`block` 里要调用别的 suspend 函数（`api.xxx()`），
     * suspend inline 函数里的非 crossinline lambda 不允许这样挂起，用普通
     * suspend 高阶函数即可，代价只是一次函数调用。
     */
    private suspend fun <T> page(page: String, fallback: T, block: suspend () -> T): T {
        markLoading(page, true)
        return try {
            val v = block()
            reportPageError(page, null)
            v
        } catch (e: Exception) {
            reportPageError(page, e.message ?: "加载失败")
            fallback
        } finally {
            markLoading(page, false)
        }
    }

    /**
     * 写操作包装：把网络异常转成可读提示，**绝不向上抛**。
     *
     * ⚠️ **为什么必须有这一层**：页面按钮普遍写成
     * `scope.launch { toast(vm.xxx()) }`，而 `rememberCoroutineScope()` 的
     * 异常会直接冒到线程默认处理器 —— 任何接口报错（404 / 401 / 超时）都会**闪退**。
     * 实测踩过：选「转发方式」时接口返回 404，App 当场崩。
     *
     * 所以所有「会被界面按钮直接调用」的写方法都必须走这里，
     * 返回 `"success"` 或一句人能看懂的错误。
     */
    private suspend fun safe(block: suspend () -> String): String = try {
        block()
    } catch (e: ApiException) {
        e.message ?: "操作失败"
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e // 协程取消要原样抛出，否则会吞掉取消信号
    } catch (e: Exception) {
        "操作失败：${e.message ?: "未知错误"}"
    }

    // ------------------------------------------------------------------ 短信转发完整配置（§6.3）

    suspend fun refreshSmsForwardConfig() {
        val cfg = page("smsForward", null as SmsForwardConfig?) {
            val method = try { api.smsForwardMethod() } catch (_: Exception) { null }
            val mail = try { api.smsForwardMail() } catch (_: Exception) { null }
            val curl = try { api.smsForwardCurl() } catch (_: Exception) { null }
            val ding = try { api.smsForwardDingtalk() } catch (_: Exception) { null }
            val bl = try { api.smsForwardBlacklist() } catch (_: Exception) { null }
            SmsForwardConfig.merge(method, mail, curl, ding, bl)
        }
        if (cfg != null) smsForwardCfg = cfg
        powerForward = try {
            api.powerForwardEnabled().get("enabled")?.asStringSafe().let { it == "1" || it == "true" }
        } catch (_: Exception) {
            powerForward
        }
    }

    suspend fun saveSmsForwardMethod(method: String): String = safe {
    api.setSmsForwardMethod(method)
    }

    suspend fun saveSmsForwardMail(cfg: SmsForwardConfig): String = safe {
        api.setSmsForwardMail(
            mapOf(
                "smtp_host" to cfg.smtpHost,
                "smtp_port" to cfg.smtpPort,
                "smtp_to" to cfg.smtpTo,
                "smtp_username" to cfg.smtpUsername,
                "smtp_password" to cfg.smtpPassword,
                "forward_dev_info" to if (cfg.mailForwardDevInfo) "1" else "0",
            )
        )
    }

    suspend fun saveSmsForwardCurl(cfg: SmsForwardConfig): String {
        // 设备强校验 {{sms-body}} 占位符，缺了会静默不生效，这里提前拦下
        if (!cfg.curlText.contains("{{sms-body}}")) return "CURL 模板必须包含 {{sms-body}} 占位符"
        return safe {
            api.setSmsForwardCurl(
                mapOf(
                    "curl_text" to cfg.curlText,
                    "forward_dev_info" to if (cfg.curlForwardDevInfo) "1" else "0",
                )
            )
        }
    }

    suspend fun saveSmsForwardDingtalk(cfg: SmsForwardConfig): String = safe {
        api.setSmsForwardDingtalk(
            mapOf(
                "webhook_url" to cfg.dingtalkWebhook,
                "secret" to cfg.dingtalkSecret,
                "forward_dev_info" to if (cfg.dingtalkForwardDevInfo) "1" else "0",
            )
        )
    }

    suspend fun saveSmsForwardBlacklist(cfg: SmsForwardConfig): String = safe {
        api.setSmsForwardBlacklist(
            mapOf(
                "phone" to cfg.blacklistPhones,
                "keywords" to cfg.blacklistKeywords,
            )
        )
    }

    /**
     * 开关电量转发。⚠️ 不叫 `setPowerForward`——`powerForward` 是公开 `var`，
     * 其自动生成的 setter 会与手写方法在 JVM 上撞名。命名约定见 [reportPageError]。
     */
    suspend fun applyPowerForward(enabled: Boolean): String = safe {
            api.setPowerForwardEnabled(enabled).also { if (it == "success") powerForward = enabled }
    }

    // ------------------------------------------------------------------ 蜂窝流量历史（§8）

    /** 拉取最近 [days] 天的按天流量 */
    suspend fun refreshUsageHistory(days: Int = 30) {
        val now = System.currentTimeMillis()
        val start = now - days * 24L * 3600L * 1000L
        val pts = page("usage", emptyList<UsagePoint>()) {
            parseUsagePoints(api.cellularUsage(start, now, "date-range"))
        }
        usagePoints = pts
    }

    // ------------------------------------------------------------------ APN（§7）

    suspend fun refreshApn() {
        data class ApnBundle(val mode: Int, val auto: List<ApnProfile>, val manual: List<ApnProfile>)

        val b = page("apn", null as ApnBundle?) {
            val modeObj = try { api.apnMode() } catch (_: Exception) { JsonObject() }
            val mode = modeObj.get("apn_mode")?.asStringSafe()?.toIntOrNull() ?: 0
            val enabledId = try {
                api.apnManualEnabled().let { o ->
                    o.get("profileId")?.asStringSafe()?.takeIf { it.isNotEmpty() }
                        ?: o.get("profileid")?.asStringSafe()?.takeIf { it.isNotEmpty() }
                }
            } catch (_: Exception) { null }

            val autoRoot = try { api.apnAuto() } catch (_: Exception) { JsonObject() }
            val manualRoot = try { api.apnManual() } catch (_: Exception) { JsonObject() }

            ApnBundle(
                mode = mode,
                auto = ApnProfile.collect(autoRoot, fromAuto = true),
                manual = ApnProfile.collect(manualRoot, fromAuto = false, enabledId = enabledId),
            )
        }
        if (b != null) {
            apnMode = b.mode
            apnAuto = b.auto
            apnManual = b.manual
        }
    }

    /**
     * 切换 APN 模式（0=自动 / 1=手动）。
     * ⚠️ 不叫 `setApnMode`——`apnMode` 是公开 `var`，其自动生成的 setter
     * 会与手写方法在 JVM 上撞名。命名约定见 [reportPageError]。
     */
    suspend fun applyApnMode(mode: Int): String = safe {
            api.setApnMode(mode).also { if (it == "success") apnMode = mode }
    }

    suspend fun addApn(p: ApnProfile): String = safe {
    api.addApnManual(p.toBody())
    }

    suspend fun updateApn(p: ApnProfile): String = safe {
    api.updateApnManual(p.toBody())
    }

    suspend fun deleteApn(profileId: String): String = safe {
    api.deleteApnManual(profileId)
    }

    suspend fun enableApn(profileId: String): String = safe {
    api.enableApnManual(profileId)
    }

    // ------------------------------------------------------------------ 定时任务（§9）

    suspend fun refreshTasks() {
        tasks = page("tasks", emptyList<ScheduledTask>()) {
            ScheduledTask.collect(api.listTasks())
        }
    }

    /**
     * 新增定时任务。
     *
     * 设备要求 `id` 唯一且非空，这里用时间戳生成；`action` 为自由 JSON，
     * 由调用方传入（界面只提供"重启"这类常用动作）。
     */
    suspend fun addTask(time: String, repeatDaily: Boolean, actionJson: String): String = safe {
        val body = JsonObject().apply {
            addProperty("id", System.currentTimeMillis().toString())
            addProperty("time", time)
            addProperty("repeatDaily", repeatDaily)
            add("action", com.google.gson.JsonParser.parseString(actionJson))
        }
        api.addTask(body.toString()).also { if (it == "success") refreshTasks() }
    }

    suspend fun removeTask(id: String): String = safe {
            api.removeTask(id).also { if (it == "success") refreshTasks() }
    }

    suspend fun clearTasks(): String = safe {
            api.clearTasks().also { if (it == "success") refreshTasks() }
    }

    /** goform 计划重启（§5.2 `RESTART_SCHEDULE_SETTING`） */
    suspend fun setRestartSchedule(enabled: Boolean, time: String): String = safe {
            goformAction(
                "RESTART_SCHEDULE_SETTING",
                mapOf(
                    "restart_schedule_switch" to if (enabled) "1" else "0",
                    "restart_time" to time,
                )
            )
    }

    // ------------------------------------------------------------------ WiFi 二维码（§10）

    /** 拉取指定频段的 WiFi 分享二维码 PNG；[chip] 0=2.4G，1=5G */
    suspend fun wifiQrcode(chip: Int): ByteArray? = page("qrcode$chip", null as ByteArray?) {
        api.wifiQrcode(chip)
    }

    // ------------------------------------------------------------------ 上传管理（§11）

    suspend fun refreshUploads() {
        uploads = page("uploads", emptyList<UploadedFile>()) {
            UploadedFile.collect(api.listUploads())
        }
    }

    suspend fun uploadFile(file: java.io.File, imageOnly: Boolean): String {
        return try {
            val o = if (imageOnly) api.uploadImage(file) else api.uploadFile(file)
            if (o.has("error")) {
                o.get("error")?.asStringSafe() ?: "上传失败"
            } else {
                refreshUploads()
                "success"
            }
        } catch (e: Exception) {
            e.message ?: "上传失败"
        }
    }

    suspend fun deleteUpload(name: String): String = safe {
            api.deleteImage(name).also { if (it == "success") refreshUploads() }
    }

    suspend fun deleteAllUploads(): String = safe {
            api.deleteAllUploads().also { if (it == "success") refreshUploads() }
    }

    /** 上传文件的访问地址（免鉴权，可直接给系统浏览器/播放器） */
    fun uploadUrl(name: String): String = "${config.baseUrl}/api/uploads/$name"

    /** 拉取已上传文件的原始字节（缩略图预览用）；[url] 形如 `/uploads/xxx.png` */
    suspend fun fetchUploadBytes(url: String): ByteArray? = try {
        val path = if (url.startsWith("/uploads/")) "/api$url" else url
        api.getBytes(path)
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ 插件商店（§14）

    suspend fun refreshPlugins() {
        data class PluginBundle(val installed: Set<String>, val store: List<StorePlugin>)

        val b = page("plugins", null as PluginBundle?) {
            val installedRoot = try { api.pluginList() } catch (_: Exception) { JsonObject() }
            val installedSet = mutableSetOf<String>()
            installedRoot.get("plugins")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
                if (el.isJsonObject) {
                    val o = el.asJsonObject
                    o.get("name")?.takeIf { !it.isJsonNull }?.asString?.let { installedSet += it }
                    o.get("public_name")?.takeIf { !it.isJsonNull }?.asString?.let { installedSet += it }
                }
            }
            val storeRoot = try { api.pluginsStore() } catch (_: Exception) { JsonObject() }
            PluginBundle(installedSet, StorePlugin.collect(storeRoot, installedSet))
        }
        if (b != null) {
            installedPlugins = b.installed
            storePlugins = b.store
        }
        pluginNotifications = page("pluginNotif", emptyList<String>()) {
            val root = api.pluginNotifications()
            val arr = root.get("notifications")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: root.get("list")?.takeIf { it.isJsonArray }?.asJsonArray
            arr?.mapNotNull { el ->
                when {
                    el.isJsonPrimitive -> el.asString
                    el.isJsonObject -> el.asJsonObject.get("text")?.takeIf { !it.isJsonNull }?.asString
                        ?: el.asJsonObject.get("title")?.takeIf { !it.isJsonNull }?.asString
                    else -> null
                }
            } ?: emptyList()
        }
    }

    suspend fun installPlugin(publicName: String): String = safe {
            api.pluginInstall(publicName).also { if (it == "success") refreshPlugins() }
    }

    suspend fun uninstallPlugin(name: String): String = safe {
            api.pluginUninstall(name).also { if (it == "success") refreshPlugins() }
    }

    suspend fun pluginUpdateCheck(): JsonObject = page("pluginUpdate", JsonObject()) {
        api.pluginUpdateCheck()
    }

    suspend fun pluginChangelog(): JsonObject = page("pluginChangelog", JsonObject()) {
        api.pluginChangelog()
    }

    suspend fun readPluginNotifications(): String = safe {
            api.readPluginNotification().also { if (it == "success") pluginNotifications = emptyList() }
    }

    // ------------------------------------------------------------------ ttyd / ADB（§15）

    suspend fun refreshTtyd() {
        val o = page("ttyd", null as JsonObject?) { api.ttydStatus() }
        if (o != null) {
            ttydRunning = o.get("running")?.asStringSafe().let { it == "1" || it == "true" }
                || o.get("status")?.asStringSafe().equals("running", true)
                || o.get("code")?.asStringSafe() == "200"
            ttydAddress = o.get("ip")?.asStringSafe().orEmpty()
                .ifEmpty { o.get("address")?.asStringSafe().orEmpty() }
                .ifEmpty { "${config.host}:1146" }
        }
    }

    suspend fun startTtyd(port: Int = 1146): String = safe {
            api.ttydStart(port).also { if (it == "success") refreshTtyd() }
    }

    suspend fun stopTtyd(): String = safe {
            api.ttydStop().also { if (it == "success") refreshTtyd() }
    }

    /** ttyd 服务地址，供外部浏览器打开 */
    fun ttydUrl(port: Int = 1146): String = "http://${config.host}:$port"

    suspend fun refreshAdbInfo() {
        adbInfo = page("adb", null as JsonObject?) {
            val alive = try { api.adbAlive() } catch (_: Exception) { JsonObject() }
            val status = try { api.adbStatus() } catch (_: Exception) { JsonObject() }
            // 注意：JsonObject.add(key, null) 会抛 NPE，缺字段时必须显式放 JsonNull
            JsonObject().apply {
                add("alive", alive.get("alive") ?: JsonNull.INSTANCE)
                add("mode", alive.get("mode") ?: status.get("mode") ?: JsonNull.INSTANCE)
                add("status", status.get("status") ?: status.get("state") ?: JsonNull.INSTANCE)
                add("port", status.get("port") ?: alive.get("port") ?: JsonNull.INSTANCE)
            }
        }
    }

    suspend fun setAdbMode(mode: String): String = safe {
            api.setAdbMode(mode).also { if (it == "success") refreshAdbInfo() }
    }

    // ------------------------------------------------------------------ 后台管理（§3）

    suspend fun updateAdminPwd(password: String): String = safe {
        val r = api.updateAdminPwd(password)
        // 改完密码，当前后台密码配置需要同步，否则后续 goform 写操作会全部失败
        if (r == "success") {
            config = config.copy(adminPassword = password)
            configStore.save(config)
            goformCookie = null
        }
        r
    }

    suspend fun refreshResServer() {
        resServer = page("resServer", resServer) {
            api.getResServer().let { o ->
                o.get("res_server")?.asStringSafe().orEmpty()
                    .ifEmpty { o.get("url")?.asStringSafe().orEmpty() }
            }
        }
    }

    suspend fun saveResServer(url: String): String = safe {
            api.setResServer(url).also { if (it == "success") resServer = url }
    }

    suspend fun refreshCustomHead() {
        customHead = page("customHead", customHead) {
            api.getCustomHead().get("text")?.asStringSafe().orEmpty()
        }
    }

    suspend fun saveCustomHead(text: String): String = safe {
            api.setCustomHead(text).also { if (it == "success") customHead = text }
    }

    /** 弱口令检测（§3 `/api/is_weak_token`） */
    suspend fun refreshWeakToken() {
        weakToken = page("weakToken", weakToken) {
            val o = api.isWeakToken()
            // 字段名在不同固件版本间不一致，取第一个非空的。
            // asStringSafe() 返回 String?，故整条用 ?. 串起来，最后用 ?: "" 收口。
            val raw = o.get("weak")?.asStringSafe()?.takeIf { it.isNotEmpty() }
                ?: o.get("is_weak")?.asStringSafe()?.takeIf { it.isNotEmpty() }
                ?: o.get("result")?.asStringSafe()?.takeIf { it.isNotEmpty() }
                ?: ""
            when {
                raw == "1" || raw.equals("true", true) -> true
                raw == "0" || raw.equals("false", true) -> false
                else -> null
            }
        }
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 上次成功登录过 → 冷启动直接自动登录，跳过连接表单。
     *
     * 放在类体末尾：Kotlin 按声明顺序初始化属性，此处所有状态字段都已就绪。
     * 显式用 `Dispatchers.Main`（非 immediate）而不是 viewModelScope 默认的 Main.immediate：
     * VM 是在 composition 里被 `viewModel()` 创建出来的，immediate 会就地同步执行到这里，
     * 变成"在 composition 期间写 Compose 状态"；post 到主线程队列就跳出了这一帧。
     */
    init {
        if (autoLogin) {
            viewModelScope.launch(Dispatchers.Main) {
                try {
                    connect()
                } finally {
                    // 失败时 status 已是 Error，界面会回落到连接表单并显示原因
                    autoLogin = false
                }
            }
        }
        // 启动即静默检查更新（不依赖设备连接是否成功）——检查失败不打扰用户
        viewModelScope.launch(Dispatchers.Main) { checkUpdate(silent = true) }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
