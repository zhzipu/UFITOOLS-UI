package com.ufitools.client.widget

import android.content.Context
import com.google.gson.JsonObject
import com.ufitools.client.data.ConfigStore
import com.ufitools.client.model.POWER_UBUS_CMD
import com.ufitools.client.model.parsePowerStatusUbus
import com.ufitools.client.network.ApiClient
import com.ufitools.client.network.ApiException
import com.ufitools.client.network.GoformClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 小组件的独立数据采集。
 *
 * 与 App 内的 [com.ufitools.client.viewmodel.MainViewModel] 走**同一套鉴权链路**
 * （SigningInterceptor 自动补 `kano-t` / `kano-sign` / `authorization`，
 * `/api/` 还要 `X-Device-Token`），但它是无状态的：每次现造 client、现取令牌，
 * 因为小组件刷新时 App 进程通常并不存在，没有内存里的 client 与 device token 可复用。
 *
 * 只请求小组件真正要显示的字段，比 App 的整轮轮询轻：
 * 1. `/api/baseDeviceInfo` —— 型号 / 电量 / 温度 / CPU / 内存 / 流量
 * 2. goform 批量读        —— 制式 / 运营商 / 信号 / 实时速率
 * 3. `/api/run_shell`     —— 电源三态（读设备 ubus `zwrt_bsp.charger`，内核节点兜底）
 */
object WidgetData {

    /**
     * 小组件用到的 goform 字段（App 的 `POLL_FIELDS` 的子集）。
     *
     * 特意不含频段、邻区、短信等大字段：桌面只需要一眼能看清的几项，
     * 请求越小越快，广播接收器的 10 秒窗口也就越安全。
     * 实时速率（`real_*_speed`）也**没要**——小组件最快 30 分钟才自动刷一次，
     * 瞬时速率没有意义，那是 App 里看的。
     */
    private val WIDGET_FIELDS = listOf(
        "model",
        "network_type", "network_provider", "network_provider_fullname", "network_signalbar",
        "Z5g_rsrp", "lte_rsrp",
        // 流量兜底：baseDeviceInfo 拿不到时按 rx+tx 相加
        "day_rx_bytes", "day_tx_bytes", "month_rx_bytes", "month_tx_bytes"
    )

    /**
     * 拉取一次数据（不落盘、不重绘）。
     *
     * 三类请求彼此独立：任意一类失败都不影响其他类的取值，
     * 只有**全部**失败才判定为离线。
     */
    suspend fun fetch(context: Context): WidgetSnapshot {
        val config = ConfigStore(context).load()
        if (!config.isValid()) {
            return WidgetSnapshot(online = false, message = "请先在 App 中配置设备地址")
        }
        val api = ApiClient { config }
        val goform = GoformClient { config }

        var base: JsonObject? = null
        try {
            base = try {
                api.baseDeviceInfo()
            } catch (e: ApiException) {
                // 设备令牌过期是常态（设备重启就会换），重取一次再试
                if (e.code == 401) {
                    api.refreshDeviceToken()
                    api.baseDeviceInfo()
                } else {
                    throw e
                }
            }
        } catch (_: Exception) {
        }

        var live: Map<String, String> = emptyMap()
        try {
            val obj = goform.get(WIDGET_FIELDS, multiData = true)
            live = obj.entrySet().associate { (k, v) ->
                k to if (v == null || v.isJsonNull) "" else runCatching { v.asString }.getOrDefault("")
            }
        } catch (_: Exception) {
        }

        val power = try {
            val text = api.runShell(POWER_UBUS_CMD)
            if (text.isBlank()) null else parsePowerStatusUbus(text)
        } catch (_: Exception) {
            null
        }

        val online = base != null || live.isNotEmpty()
        return WidgetSnapshot.build(
            base = base,
            live = live,
            power = power,
            online = online,
            message = if (online) "" else "设备连接失败"
        )
    }

    /**
     * 拉取 + 落盘，返回**应当渲染**的快照。
     *
     * 拉取失败时不清掉旧数据：把上一次成功的数据配上「离线」标记返回，
     * 桌面上仍能看到最后的已知状态，只是时间戳旁边标个红字。
     *
     * @param force 手动刷新（点了刷新按钮）时传 true，绕过下面的合并窗口
     */
    suspend fun refresh(context: Context, force: Boolean = false): WidgetSnapshot = fetchMutex.withLock {
        val store = WidgetStore(context)
        val now = System.currentTimeMillis()

        // 系统唤醒三个小组件时会连着触发三次 onUpdate，这里把重复请求合并成一次
        if (!force && now - lastFetchAt < MIN_REFRESH_INTERVAL_MS) {
            store.load()?.let { return@withLock it }
        }
        lastFetchAt = now

        val fresh = fetch(context)
        if (fresh.online) {
            store.save(fresh)
            store.saveFingerprint(fresh.fingerprint())
            fresh
        } else {
            (store.load() ?: fresh).copy(online = false, message = fresh.message)
        }
    }

    private val fetchMutex = Mutex()

    @Volatile
    private var lastFetchAt = 0L

    /** 重复拉取的合并窗口 */
    private const val MIN_REFRESH_INTERVAL_MS = 3_000L
}
