package com.ufitools.client.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.ufitools.client.data.ClientIconStore
import com.ufitools.client.data.ConfigStore
import com.ufitools.client.data.DeviceConfig
import com.ufitools.client.data.RefreshInterval
import com.ufitools.client.data.ThemeStore
import com.ufitools.client.model.ClientDevice
import com.ufitools.client.model.ClientIcon
import com.ufitools.client.model.IW_STATION_DUMP_CMD
import com.ufitools.client.model.POWER_UBUS_CMD
import com.ufitools.client.model.PowerMode
import com.ufitools.client.model.PowerStatus
import com.ufitools.client.model.SmsMessage
import com.ufitools.client.model.detectClientIcon
import com.ufitools.client.model.kickClientCmd
import com.ufitools.client.model.parseIwStationDump
import com.ufitools.client.model.parsePowerStatusUbus
import com.ufitools.client.network.ApiClient
import com.ufitools.client.network.ApiException
import com.ufitools.client.network.GoformClient
import com.ufitools.client.network.UbusClient
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

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val configStore = ConfigStore(app)
    private val themeStore = ThemeStore(app)
    private val clientIconStore = ClientIconStore(app)

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

    /** 终端自定义图标：MAC(小写) -> ClientIcon.key；无记录表示沿用自动识别 */
    private var clientIconOverrides by mutableStateOf(clientIconStore.loadAll())

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
        return when {
            isSuccess(res) -> "success"
            // 重试后仍是这两个码：会话没拿到，根子还是后台密码
            r == "1" || r == "3" -> LOGIN_FAILED_MSG
            else -> res.get("error")?.asStringSafe() ?: "操作失败($r)"
        }
    }

    // ------------------------------------------------------------------ 设置动作

    suspend fun setNickname(nickname: String): String = api.setNickname(nickname)

    suspend fun changeToken(newToken: String): String = api.setToken(newToken)

    /**
     * 唤醒锁。
     *
     * 设备**没有对应的读接口**（`/api/get_wakelock_status` 实测 404），开关状态无法回读，
     * 界面已移除入口。方法保留，以备后续找到可回读的来源后再接回界面。
     */
    suspend fun setWakelock(enabled: Boolean): String = api.setWakelock(enabled)

    suspend fun setAdbWifi(enabled: Boolean, password: String): String = api.setAdbWifi(enabled, password)

    suspend fun setDataLimit(params: Map<String, Any>): String = api.setDataLimit(params)

    suspend fun setSmsForward(enabled: Boolean): String = api.setSmsForwardEnabled(enabled)

    suspend fun volteStatus(): JsonObject = api.volteStatus()

    suspend fun vonrStatus(): JsonObject = api.vonrStatus()

    suspend fun setVolte(enabled: Boolean): String = api.setVolte(enabled)

    suspend fun setVonr(enabled: Boolean): String = api.setVonr(enabled)

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
    suspend fun setBearerPreference(value: String): String =
        setNetworkModeByUbus(value)
            ?: goformAction("SET_BEARER_PREFERENCE", mapOf("BearerPreference" to value))

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
    suspend fun setPerformanceMode(on: Boolean): String =
        goformAction("PERFORMANCE_MODE_SETTING", mapOf("performance_mode" to if (on) "1" else "0"))

    /** 旧名保留，行为与 [setBearerPreference] 完全一致 */
    suspend fun setNetworkMode(value: String): String = setBearerPreference(value)

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

    suspend fun lockCell(pci: String, earfcn: String, rat: String): String =
        goformAction("CELL_LOCK", mapOf("pci" to pci, "earfcn" to earfcn, "rat" to rat))

    suspend fun unlockCell(): String = goformAction("UNLOCK_ALL_CELL", emptyMap())

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
     * 指示灯。
     *
     * 设备**不支持该设置**：goform 取不到 `indicator_light_switch` 字段，
     * 官方 Web 端也没有这个入口，状态无从回读。界面已移除入口，方法保留以备后用。
     */
    suspend fun setIndicatorLight(on: Boolean): String =
        goformAction("INDICATOR_LIGHT_SETTING", mapOf("indicator_light_switch" to if (on) "1" else "0"))

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

    suspend fun setSimSlot(slot: String): String =
        goformAction("SET_SIM_SLOT", mapOf("sim_slot" to slot))

    suspend fun setUsbNetworkProtocol(value: String): String =
        goformAction("SET_USB_NETWORK_PROTOCAL", mapOf("usb_network_protocal" to value))

    suspend fun reboot(): String = goformAction("REBOOT_DEVICE", emptyMap())

    suspend fun shutdown(): String = goformAction("SHUTDOWN_DEVICE", emptyMap())

    // ------------------------------------------------------------------ 短信

    suspend fun sendSms(number: String, content: String): String {
        val cookie = ensureCookie() ?: return LOGIN_FAILED_MSG
        val res = goform.sendSms(number, content, cookie)
        return if (isSuccess(res)) "success" else (res.get("error")?.asStringSafe() ?: "发送失败")
    }

    suspend fun deleteSms(msgId: String): String {
        val cookie = ensureCookie() ?: return LOGIN_FAILED_MSG
        val res = goform.deleteSms(msgId, cookie)
        return if (isSuccess(res)) "success" else "删除失败"
    }

    suspend fun markSmsRead(msgId: String): String {
        val cookie = ensureCookie() ?: return LOGIN_FAILED_MSG
        val res = goform.markSmsRead(msgId, cookie)
        return if (isSuccess(res)) "success" else "标记失败"
    }

    // ------------------------------------------------------------------ AT 命令

    suspend fun atCommand(command: String): String {
        val o = api.atCommand(command)
        return o.get("result")?.asStringSafe() ?: o.toString()
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
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
