package com.ufitools.client.viewmodel

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.ufitools.client.data.ClashConfig
import com.ufitools.client.data.ClashConfigStore
import com.ufitools.client.data.ConfigStore
import com.ufitools.client.data.DeviceConfig
import com.ufitools.client.data.RefreshInterval
import com.ufitools.client.data.ThemeStore
import com.ufitools.client.data.WebAutoLogin
import com.ufitools.client.data.WebTheme
import com.ufitools.client.data.WebThemeMapper
import com.ufitools.client.model.AclState
import com.ufitools.client.model.ApnProfile
import com.ufitools.client.model.ClientDevice
import com.ufitools.client.model.ClientIcon
import com.ufitools.client.model.ClashConfigProbe
import com.ufitools.client.model.ClashConnection
import com.ufitools.client.model.ClashLogLevel
import com.ufitools.client.model.ClashProvider
import com.ufitools.client.model.ClashProxy
import com.ufitools.client.model.ClashRule
import com.ufitools.client.model.ClashRuleSet
import com.ufitools.client.model.ClashRuntimeConfig
import com.ufitools.client.model.ClashVersion
import com.ufitools.client.model.applyRates
import com.ufitools.client.model.IW_STATION_DUMP_CMD
import com.ufitools.client.model.InstalledPlugin
import com.ufitools.client.model.IpValidator
import com.ufitools.client.model.LanSetting
import com.ufitools.client.model.POWER_UBUS_CMD
import com.ufitools.client.model.PowerMode
import com.ufitools.client.model.PowerStatus
import com.ufitools.client.model.ScheduledTask
import com.ufitools.client.model.SmsMessage
import com.ufitools.client.model.StorePlugin
import com.ufitools.client.model.UploadedFile
import com.ufitools.client.model.UpdateInfo
import com.ufitools.client.model.UsagePoint
import com.ufitools.client.model.WifiAp
import com.ufitools.client.model.ClashProbe
import com.ufitools.client.model.detectClientIcon
import com.ufitools.client.model.parseClashConfig
import com.ufitools.client.model.parseClashConfigProbe
import com.ufitools.client.model.parseClashConnections
import com.ufitools.client.model.parseClashProviders
import com.ufitools.client.model.parseClashProviderNodes
import com.ufitools.client.model.mergeClashProxies
import com.ufitools.client.model.parseClashProxies
import com.ufitools.client.model.parseClashRules
import com.ufitools.client.model.parseClashVersion
import com.ufitools.client.model.parseIwStationDump
import com.ufitools.client.model.parsePowerStatusUbus
import com.ufitools.client.model.parseUsagePoints
import com.ufitools.client.network.ApiClient
import com.ufitools.client.network.ApiException
import com.ufitools.client.network.ClashClient
import com.ufitools.client.network.ClashException
import com.ufitools.client.network.ClashLogStream
import com.ufitools.client.network.Crypto
import com.ufitools.client.network.GoformClient
import com.ufitools.client.network.NfcState
import com.ufitools.client.network.UbusClient
import com.ufitools.client.network.UpdateChecker
import com.ufitools.client.ui.theme.ThemePalettes
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

/**
 * 猫猫服务不可用时的统一提示。
 *
 * 地址写死、secret 自动读取之后，「连不上」的成因已经收敛成一种：
 * 设备上没有在跑的 mihomo 内核（或没开 external-controller）。
 * 内核原始的 Connection refused / timeout 对用户没有指导意义，统一换成这句。
 */
const val CLASH_NOT_RUNNING_HINT = "未安装/运行猫猫服务"

/** 黑名单读取失败（设备没返回 AclMode，通常是会话过期后重登仍失败） */
const val ACL_READ_FAILED_MSG = "读取黑名单失败：请检查「设置 → 修改口令」中的后台密码是否正确"

/** 黑名单写入前的登录失败提示（与 [LOGIN_FAILED_MSG] 同因，文案指向同一处设置） */
const val ACL_LOGIN_FAILED_MSG =
    "登录设备后台失败：请确认「设置 → 修改口令」里填的是设备 Web 控制台的管理密码"

/**
 * 剥掉文件扩展名（`u60屏幕管理插件.txt` → `u60屏幕管理插件`）。
 *
 * 用于插件名归一化：设备 custom_head 里的块名没有扩展名，
 * 而商店侧的 `installName` / `publicName` 往往带 `.txt` / `.js`，
 * 不剥就永远匹配不上（商店的「已安装」标记不会亮、已安装列表也认不出对应条目）。
 *
 * 保护条件：`i <= 0`（无扩展名或隐藏文件）与 `i < s.length - 8`（后缀过长、像域名而非扩展名）都不剥。
 */
private fun stripExt(s: String): String {
    val i = s.lastIndexOf('.')
    if (i <= 0 || i < s.length - 8) return s
    return s.substring(0, i).ifEmpty { s }
}

/**
 * 插件块匹配正则。
 *
 * 与官方前端 `main.js` 中的 `parsePluginBlocks` 严格等价：
 * 起止标记里的块名必须一致（`\1` 反向引用），否则视为残缺块不予处理。
 * 块内容（第二个捕获组）可能包含任意 HTML/JS，因此必须用非贪婪 + DOTALL 语义。
 */
private val PLUGIN_BLOCK_RE = Regex(
    "<!--\\s*\\[KANO_PLUGIN_START\\]\\s*(.*?)\\s*-->([\\s\\S]*?)<!--\\s*\\[KANO_PLUGIN_END\\]\\s*\\1\\s*-->",
)

/**
 * 按块名删除插件块（含块后紧随的空白）。
 *
 * 对应官方 `stripPluginBlocksByNames`。删除的是**整块**：起止标记 + 块内容，
 * 并顺带吃掉块后连续的换行/空格，避免反复卸载后 custom_head 里堆积空行。
 * 名字集合为空时原样返回（防御空参数导致的"清空全部"事故）。
 */
private fun stripPluginBlocks(text: String, names: Set<String>): String {
    val nameSet = names.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
    if (nameSet.isEmpty()) return text
    val re = Regex(
        "<!--\\s*\\[KANO_PLUGIN_START\\]\\s*(.*?)\\s*-->[\\s\\S]*?<!--\\s*\\[KANO_PLUGIN_END\\]\\s*\\1\\s*-->[ \\t]*\\n?",
    )
    return re.replace(text) { m ->
        val name = m.groupValues[1].trim()
        if (nameSet.contains(name)) "" else m.value
    }
}

/**
 * 在 custom_head 文本里交换两个插件块的位置，用于「上移 / 下移」。
 *
 * custom_head 里的插件块是**顺序执行**的，所以排序不是展示层的排序，
 * 而是真实写回设备、改变执行次序。做法：把每个块换成占位符，
 * 得到骨架 + 块内容列表，交换列表元素后回填，块外内容（非插件 HTML）位置不动。
 *
 * @param from 原下标，@param to 目标下标（越界直接原样返回）
 */
private fun reorderPluginBlocks(text: String, from: Int, to: Int): String {
    val matches = PLUGIN_BLOCK_RE.findAll(text).toList()
    if (from !in matches.indices || to !in matches.indices || from == to) return text

    val blocks = matches.map { it.value }.toMutableList()
    val moved = blocks.removeAt(from)
    blocks.add(to, moved)

    // 用唯一的哨兵占位，避免块内容里恰好含有 \\u0000 之类字符造成误替换
    val sb = StringBuilder()
    var cursor = 0
    matches.forEachIndexed { i, m ->
        sb.append(text, cursor, m.range.first)
        sb.append("\u0000KANO_PLUGIN_SLOT_").append(i).append("\u0000")
        cursor = m.range.last + 1
    }
    sb.append(text, cursor, text.length)

    var out = sb.toString()
    blocks.forEachIndexed { i, block ->
        // 槽位 i 现在放 blocks[i]；blocks 已是重排后的结果
        out = out.replace("\u0000KANO_PLUGIN_SLOT_$i\u0000", block)
    }
    return out
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

    /**
     * Clash 面板（mihomo external-controller）配置与客户端。
     *
     * 与设备那套连接配置**无关**：面板跑在独立的 9090 端口上，
     * 有自己的地址与 secret，因此单独存一份（见 [ClashConfigStore]）。
     */
    private val clashStore = ClashConfigStore(app)
    private val clash = ClashClient { clashConfig }

    /**
     * 内核日志流（`WS /logs`）。
     *
     * ⚠️ 这是**长连接**，必须由界面在进入/离开日志页时显式 start/stop，
     * 不能跟着主轮询走。ViewModel 销毁时由 [onCleared] 兜底释放。
     */
    private val clashLog = ClashLogStream(clash)

    // ------------------------------------------------------------------ 主题

    /** 当前配色 ID：0 默认 / 1 科技蓝 / 2 薄荷绿 / 3 梦幻紫 / 4 活力橙 */
    var paletteId by mutableStateOf(themeStore.loadPaletteId())
        private set

    var themeMode by mutableStateOf(ThemeMode.fromKey(themeStore.loadThemeMode()))
        private set

    fun setPalette(id: Int) {
        paletteId = id
        themeStore.savePaletteId(id)
        refreshWidgetTheme()
    }

    /** 应用主题明暗模式并持久化（方法名避开 themeMode 生成的 setter，防止 JVM 签名冲突） */
    fun applyThemeMode(mode: ThemeMode) {
        themeMode = mode
        themeStore.saveThemeMode(mode.key)
        refreshWidgetTheme()
    }

    /**
     * 主题（配色 / 明暗）变了，通知桌面小组件按新配色重绘。
     *
     * 小组件不在 Compose 树里，读的是 `ThemeStore` + `RemoteViews`，
     * **不会**跟着 `LocalPalette` 自动重组；不显式广播的话，用户改完主题
     * 得等到下一次数据指纹变化（最多 10 秒、甚至更久）才看到桌面变色，
     * 而且明暗模式单独改时指纹根本不变 —— 可能一直不变色。
     *
     * 直接发广播、不走 [pushWidgetSnapshot]：后者有指纹去重与最小间隔两道闸，
     * 而这里颜色变了但数据没变，会被指纹闸直接拦掉。
     */
    private fun refreshWidgetTheme() {
        val app = getApplication<Application>()
        // 配色存在 ThemeStore 里，widget 侧渲染时会重新读取，这里只需触发重建
        WidgetBus.notifyAll(app)
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

    /**
     * 设备上**已安装**的插件（带版本与顺序），来自 `custom_head` 里的插件块。
     *
     * 与 [storePlugins] 是两回事：后者是远端商店的全量列表，与设备状态无关。
     */
    var installedPluginList by mutableStateOf<List<InstalledPlugin>>(emptyList())
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
     * ⚠️ **冷启动时必须先等网络就绪**：App 刚起来时 Android 的网络栈 / DNS
     * 往往还没建立完，此时直接请求 `api.github.com` 必然解析失败或超时 ——
     * 表现就是"刚进 App 检查更新总失败，过几分钟又正常"（[awaitNetworkReady]）。
     *
     * @param silent 启动自动检查传 true：失败或已是最新都静默，只在发现新版时由界面弹窗；
     *               用户在「关于」里手动点则传 false，会返回一句可 Toast 的结果。
     * @return 一句面向用户的结果文案（silent 时也可能为空串）
     */
    suspend fun checkUpdate(silent: Boolean): String {
        if (updateChecking) return ""
        updateChecking = true
        return try {
            if (!awaitNetworkReady()) {
                return if (silent) "" else "当前无网络连接，请检查网络后重试"
            }
            // 网络刚就绪的一瞬间 DNS 可能还没生效，失败就补一次再下结论
            var info = UpdateChecker.check(appVersion)
            if (info == null) {
                delay(UPDATE_RETRY_DELAY_MS)
                info = UpdateChecker.check(appVersion)
            }
            updateInfo = info
            when {
                // 失败时把 [UpdateChecker.lastError] 一并带出来（如 DNS 解析失败 / HTTP 403）——
                // 只说"检查失败"没法判断该改网络还是等一会儿再试
                info == null -> if (silent) "" else
                    "检查更新失败：${UpdateChecker.lastError ?: "未知原因"}"
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
     * 等待系统报告「网络可用」，最多等 [NETWORK_WAIT_TIMEOUT_MS]。
     *
     * 存在的意义就是修掉「冷启动检查更新必失败」：进程刚起来那几秒，
     * `ConnectivityManager` 还没给出带 `NET_CAPABILITY_INTERNET` 的网络，
     * 这时发出去的请求会以 `UnknownHostException` / 超时告终。
     * 先等它就绪，可以免掉那次注定失败的尝试。
     *
     * 判据只用 `NET_CAPABILITY_INTERNET` 而**不加 `NET_CAPABILITY_VALIDATED`**：
     * 后者要等系统完成一次真实连通性探测，冷启动时会慢很多；
     * 这里宁可"早放行 + 靠调用方重试兜底"，也不要让用户干等。
     *
     * @return true = 已有可用网络；false = 超时（大概率真的没网）
     */
    private suspend fun awaitNetworkReady(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(ConnectivityManager::class.java)
            // 拿不到服务时不要阻塞功能，直接放行让请求自己去撞
            ?: return true
        val deadline = System.currentTimeMillis() + NETWORK_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                return true
            }
            delay(NETWORK_POLL_INTERVAL_MS)
        }
        return false
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

    // ================================================================== Clash 面板

    /** 面板配置（地址 + secret），改动即持久化 */
    var clashConfig by mutableStateOf(clashStore.load())
        private set

    /** 内核版本；null 表示尚未成功连上面板 */
    var clashVersion by mutableStateOf<ClashVersion?>(null)
        private set

    /** 内核运行配置（模式 / TUN / 端口…） */
    var clashRuntime by mutableStateOf<ClashRuntimeConfig?>(null)
        private set

    /** 全部代理与策略组，策略组排在前面 */
    var clashProxies by mutableStateOf<List<ClashProxy>>(emptyList())
        private set

    /** 代理集（订阅），用于展示剩余流量与到期时间 */
    var clashProviders by mutableStateOf<List<ClashProvider>>(emptyList())
        private set

    /** 活动连接（按流量降序） */
    var clashConnections by mutableStateOf<List<ClashConnection>>(emptyList())
        private set

    /** 连接汇总：累计上下行 */
    var clashDownTotal by mutableStateOf(0L)
        private set
    var clashUpTotal by mutableStateOf(0L)
        private set

    /** 内核内存占用（字节） */
    var clashMemory by mutableStateOf(0L)
        private set

    /** 上一次连接快照的时间戳，用于算实时速率；0 表示还没有基准 */
    private var clashTrafficAt = 0L

    /** 正在测速的节点名（含策略组）；空集表示没有测速进行中 */
    var clashTesting by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * 面板整体状态：null=未探测 / true=已连通 / false=探测失败。
     *
     * 不用 `ConnectionStatus`：那是设备连接的语义，面板是独立通道，
     * 设备连不上时面板仍可能正常（反之亦然）。
     */
    var clashOnline by mutableStateOf<Boolean?>(null)
        private set

    /** 面板最近一次错误文案；null 表示上一轮成功 */
    var clashError by mutableStateOf<String?>(null)
        private set

    /**
     * 「从设备读取」的结果；null 表示还没读过。
     *
     * 这个状态存在的唯一理由：面板地址与 secret 通常只有设备上的
     * mihomo 配置文件知道，用户第一次填时基本是空的。与其让用户去
     * 翻 yaml，不如点一下按钮直接替他读出来。
     */
    var clashProbe by mutableStateOf<ClashConfigProbe?>(null)
        private set

    /** 正在读取设备配置（按钮转圈用） */
    var clashProbeLoading by mutableStateOf(false)
        private set

    /** 读取过程的提示文案，供弹窗内直接展示 */
    var clashProbeMessage by mutableStateOf<String?>(null)
        private set

    /** 策略组列表（界面按组展示，节点跟在组下面） */
    val clashGroups: List<ClashProxy> get() = clashProxies.filter { it.isGroup }

    /** 未被任何策略组引用的自由节点，兜底展示用 */
    val clashLooseProxies: List<ClashProxy>
        get() = clashProxies.filter { !it.isGroup && it.isSelectable }

    /**
     * 从设备上读回 mihomo 的 `secret` 并存入本地。
     *
     * 走 `/api/run_shell`——这是设备上唯一能读到 mihomo 配置的通道。
     * **不抛异常**：读不到不是错误，只是没有这个能力（未装内核、
     * 固件不给 shell、配置路径不在候选列表里）。
     *
     * ⚠️ 必须是「不抛」的：本方法由界面按钮经 `scope.launch` 直接调用，
     * 而 `rememberCoroutineScope()` 里未捕获的异常会直冒线程默认处理器导致闪退
     * （本项目已因此崩过一次，见 MainViewModel 的 `safe { }` 约定）。
     *
     * @return 可直接展示给用户的提示文案
     */
    suspend fun probeClashFromDevice(): String {
        clashProbeLoading = true
        clashProbeMessage = null
        try {
            val out = try {
                api.runShell(ClashProbe.clashConfigProbeCmd())
            } catch (e: ApiException) {
                if (e.code == 401) {
                    // 设备令牌过期：重取一次再跑（与终端列表同一套兜底）
                    api.refreshDeviceToken()
                    api.runShell(ClashProbe.clashConfigProbeCmd())
                } else {
                    clashProbe = null
                    val m = "读取设备配置失败：${e.message ?: "设备未连接"}"
                    clashProbeMessage = m
                    return m
                }
            }

            val probe = parseClashConfigProbe(out)
            clashProbe = probe

            // 读到 secret 就地存下来：下次冷启动直接用它探活，
            // 不必再跑一次 shell（shell 通道比 HTTP 慢得多，且要设备在线）。
            if (probe.found && probe.secret.isNotBlank() && probe.secret != clashConfig.secret) {
                clashStore.saveSecret(probe.secret)
                clashConfig = clashConfig.copy(secret = probe.secret)
            }

            val msg = when {
                !probe.found ->
                    "设备上没找到 mihomo 配置文件。\n" +
                        "可能内核还没装，或配置放在了非标准路径，请手动填写地址与密码。"

                else -> buildString {
                    append("已从 ")
                    append(probe.configPath)
                    append(" 读取：\n")
                    append("• 密码：")
                    append(probe.secret.ifBlank { "（未设置）" })
                    append("\n• 监听：")
                    append(probe.controller.ifBlank { "（未配置）" })
                    if (probe.loopbackOnly) {
                        append("\n\n⚠ 监听地址是回环地址，手机连不上。")
                        append("需要把 external-controller 改成 0.0.0.0:")
                        append(probe.port.ifBlank { "9090" })
                        append(" 后重启内核。")
                    }
                    if (probe.hasPanel) {
                        append("\n\n设备上已装面板：")
                        append(probe.externalUiName.ifBlank { "（未记录名称）" })
                    }
                }
            }
            clashProbeMessage = msg
            return msg
        } catch (e: Exception) {
            // 兜底：设备未连接、令牌取不到、shell 通道异常等都在这里收口
            clashProbe = null
            val m = "读取设备配置失败：${e.message ?: "未知错误"}"
            clashProbeMessage = m
            return m
        } finally {
            clashProbeLoading = false
        }
    }

    /** 正在自动连接（读 secret + 探活）的标记 */
    var clashAutoConnecting by mutableStateOf(false)
        private set

    /** 本次自动初始化是否已经跑过，避免每次进页面都重跑 */
    private var clashAutoInitDone = false

    /**
     * 自动连接猫猫服务：读设备配置拿 secret → 探活。
     *
     * 这是本页唯一的连接入口（界面上已没有地址/密码输入框）。
     * 流程：
     * 1. 若本地没有 secret，先从设备配置里读一次；
     * 2. 用「固定地址 + secret」探活；
     * 3. 仍失败**再读一次**再探——覆盖「内核刚启动、secret 变了」的情形。
     *
     * 只跑一次（[clashAutoInitDone] 去重）；用户点「重试」时走 [retryClash]，
     * 那条路径允许反复触发。
     *
     * ⚠️ 不抛异常：本方法由界面 `LaunchedEffect` 直接调用。
     */
    suspend fun autoConnectClash() {
        if (clashAutoInitDone) return
        clashAutoInitDone = true
        clashAutoConnecting = true
        try {
            // 本地没存过 secret 才去读设备——省一次 shell 往返
            if (clashConfig.secret.isBlank()) {
                probeClashFromDevice()
            }
            refreshClash()
            // 第一次探活失败 → 很可能是 secret 过期/内核刚换过，再读一次重试
            if (clashOnline != true) {
                probeClashFromDevice()
                refreshClash()
            }
        } catch (_: Exception) {
            // refreshClash 内部已收口；这里只兜底极端情况，保证不冒泡
        } finally {
            clashAutoConnecting = false
        }
    }

    /** 手动重试：允许重复触发，会重新读一次 secret */
    suspend fun retryClashAuto() {
        clashAutoConnecting = true
        try {
            probeClashFromDevice()
            retryClash()
        } catch (_: Exception) {
        } finally {
            clashAutoConnecting = false
        }
    }

    /**
     * 一次拉全：版本 + 配置 + 代理 + 订阅。
     *
     * 面板轮询比设备轮询重（4 个请求），因此由界面按需触发，
     * **不挂进主轮询**；只有「连接列表」才值得高频刷新，单独有 [refreshClashConnections]。
     */
    suspend fun refreshClash() {
        page("clash", Unit) {
            // 版本接口是最轻的探活手段：先把它与配置一起拿到
            val v = parseClashVersion(clash.version())
            val cfg = parseClashConfig(clash.configs())
            clashVersion = v
            clashRuntime = cfg
            clashOnline = true
            clashStore.setWasConnected(true)

            // 代理与订阅属于「大响应」，失败不应把整页判死，各自兜底。
            //
            // ⚠️ **节点必须从两个来源合并**：`/proxies` 顶层只给内置项与策略组，
            // **不给代理集提供的节点**（实机：顶层 8 个 vs provider 里 46 个）。
            // 只读顶层会导致策略组里除 DIRECT 外的成员全找不到实体，
            // 界面表现为「43 个成员只出 1 张卡」。
            // `clash.providers()` 本来就要调（订阅页要用），**不额外增加请求**。
            val providerNodes = try {
                parseClashProviderNodes(clash.providers())
            } catch (_: Exception) {
                emptyList()
            }
            clashProxies = try {
                mergeClashProxies(parseClashProxies(clash.proxies()), providerNodes)
            } catch (_: Exception) {
                clashProxies
            }
            clashProviders = try {
                parseClashProviders(clash.providers())
            } catch (_: Exception) {
                clashProviders
            }
            // 连接与流量汇总也一起拉，避免首帧概览显示 0
            try {
                reportClashConnections(parseClashConnections(clash.connections()))
            } catch (_: Exception) {
                // 连接接口失败不影响「已连通」的判定
            }
        }
        clashError = pageError["clash"]
        clashOnline = pageError["clash"] == null
        if (clashOnline == false) {
            // 探活失败：把版本清掉，界面据此回落到「未连接」形态
            clashVersion = null
            // 地址是写死的、secret 是自动读的，所以连不上只剩下一种可能：
            // **设备上压根没有在跑的猫猫服务**（内核没装 / 没启动 / 没开 external-controller）。
            // 内核原始报错（Connection refused / timeout）对用户没有指导意义，统一换成一句人话。
            clashError = CLASH_NOT_RUNNING_HINT
        }
    }

    /**
     * 只刷连接列表（轻量端点，适合在面板页停留时高频调用）。
     *
     * @param silent 为 true 时不写 [pageError]，避免后台自动刷新把错误弹到界面
     */
    suspend fun refreshClashConnections(silent: Boolean = false) {
        if (silent) {
            try {
                reportClashConnections(parseClashConnections(clash.connections()))
            } catch (_: Exception) {
                // 静默刷新失败就维持现状，等下一次
            }
            return
        }
        page("clashConn", Unit) {
            reportClashConnections(parseClashConnections(clash.connections()))
        }
    }

    /**
     * 写入连接快照，并顺带算出实时速率。
     *
     * ## 为什么速率要在 App 侧算
     *
     * 内核的 `/connections` **只给累计流量**，没有「当前速率」字段
     * （zashboard 也是同样处理）。做法是：拿上一次快照，用每条连接的
     * `download`/`upload` 增量除以两次快照的时间差。
     *
     * 三条防线：
     * - **旧快照为空时不抹零**：首次进入页面没有基准，用 0 而不是把累计值当速率；
     * - **负增量当 0**：内核重启会让计数器回绕，直接算会得到巨大负数；
     * - **时间差过小不更新**：两次刷新挤在几十毫秒内，除出来的数字会乱跳。
     */
    private fun reportClashConnections(sum: com.ufitools.client.model.ClashConnectionSummary) {
        val nowAt = System.currentTimeMillis()
        val prevSnapshot = clashConnections
        val prevAt = clashTrafficAt
        clashConnections = applyRates(sum.connections, prevSnapshot, prevAt, nowAt)
        clashTrafficAt = nowAt
        clashDownTotal = sum.downloadTotal
        clashUpTotal = sum.uploadTotal
        clashMemory = sum.memory
    }

    /**
     * 切换内核运行模式（rule / global / direct）。
     *
     * ⚠️ 不叫 `setClashMode`——[clashRuntime] 若是公开 `var` 会生成 setter 撞名，
     * 命名约定见 [reportPageError]。
     */
    suspend fun applyClashMode(mode: String): String = safe {
        clash.setMode(mode)
        // 内核改完不回读；这里乐观更新，界面不必等下一轮刷新
        clashRuntime = clashRuntime?.copy(mode = mode)
        "success"
    }

    /** 切换 TUN 模式 */
    suspend fun applyClashTun(enable: Boolean): String = safe {
        clash.setTun(enable)
        clashRuntime = clashRuntime?.copy(tunEnabled = enable)
        "success"
    }

    /**
     * 策略组切换到指定节点。
     *
     * 成功后顺手更新本地策略组的 `now`，让界面立刻反映选择，不用等整页刷新。
     */
    suspend fun selectClashNode(group: String, node: String): String = safe {
        clash.selectProxy(group, node)
        clashProxies = clashProxies.map { p ->
            if (p.isGroup && p.name == group) p.copy(now = node) else p
        }
        "success"
    }

    /**
     * 单节点测速。
     *
     * 测速是长耗时操作（可达 5 秒），用 [clashTesting] 标记「进行中」，
     * 结果写回对应代理的 [ClashProxy.delay]。
     */
    suspend fun testClashNode(name: String): String = safe {
        clashTesting = clashTesting + name
        try {
            val d = clash.delay(name)
            clashProxies = clashProxies.map { p ->
                if (p.name == name) p.copy(delay = d, alive = d > 0) else p
            }
            if (d > 0) "延迟 $d ms" else "测速失败（节点不可达）"
        } finally {
            clashTesting = clashTesting - name
        }
    }

    /**
     * 整个策略组测速。
     *
     * 内核的组测速接口会并发测完组内全部节点，耗时可能到 10 秒以上，
     * 期间把组名放进 [clashTesting] 让按钮显示进度。
     */
    suspend fun testClashGroup(group: String): String = safe {
        clashTesting = clashTesting + group
        try {
            val result = clash.groupDelay(group)
            // 注意：safe 不是 inline，不能用 return@safe 做非局部返回，这里用表达式收口
            if (result.isEmpty()) {
                "测速未返回结果"
            } else {
                clashProxies = clashProxies.map { p ->
                    val d = result[p.name]
                    if (d != null) p.copy(delay = d, alive = d > 0) else p
                }
                val ok = result.values.count { it > 0 }
                "完成：$ok/${result.size} 个节点可达"
            }
        } finally {
            clashTesting = clashTesting - group
        }
    }

    /**
     * 批量测速指定节点（用于「一键测速」）。
     *
     * 串行执行，避免同时打出几十个请求把内核拖死；每测完一个就写回状态，
     * 界面能看到延迟逐个出现。
     */
    suspend fun testClashNodes(names: List<String>, onProgress: (Int, Int) -> Unit = { _, _ -> }): String {
        if (names.isEmpty()) return "没有可测速的节点"
        var ok = 0
        names.forEachIndexed { i, name ->
            clashTesting = clashTesting + name
            try {
                val d = clash.delay(name)
                clashProxies = clashProxies.map { p ->
                    if (p.name == name) p.copy(delay = d, alive = d > 0) else p
                }
                if (d > 0) ok++
            } catch (_: Exception) {
                // 单点失败不中断整批，继续测下一个
            } finally {
                clashTesting = clashTesting - name
            }
            onProgress(i + 1, names.size)
        }
        return "完成：$ok/${names.size} 个节点可达"
    }

    /** 关闭全部连接 */
    suspend fun closeAllClashConnections(): String = safe {
        clash.closeAllConnections()
        clashConnections = emptyList()
        "success"
    }

    /** 关闭单条连接 */
    suspend fun closeClashConnection(id: String): String = safe {
        clash.closeConnection(id)
        clashConnections = clashConnections.filterNot { it.id == id }
        "success"
    }

    /** 更新订阅（代理集） */
    suspend fun updateClashProvider(name: String): String = safe {
        clash.updateProvider(name)
        "success"
    }

    /** 刷新面板数据后清空错误提示（供界面「重试」按钮用） */
    suspend fun retryClash() {
        reportPageError("clash", null)
        clashOnline = null
        refreshClash()
    }

    // ------------------------------------------------------------------ 规则页

    /** 全部分流规则 */
    var clashRules by mutableStateOf<List<ClashRule>>(emptyList())
        private set

    /** 内核声明的规则总数（可能大于实际下发条数） */
    var clashRuleTotal by mutableStateOf(0)
        private set

    /** 规则集订阅（`/providers/rules`） */
    var clashRuleProviders by mutableStateOf<List<ClashProvider>>(emptyList())
        private set

    /** 规则页的加载中标记；单独一个，不跟主刷新打架 */
    var clashRulesLoading by mutableStateOf(false)
        private set

    /** 拉取全部规则。规则动辄几千条，是「大响应」，失败不把整页判死 */
    suspend fun refreshClashRules() {
        clashRulesLoading = true
        try {
            try {
                val set: ClashRuleSet = parseClashRules(clash.rules())
                clashRules = set.rules
                clashRuleTotal = set.total
            } catch (_: Exception) {
                // 保持原有列表，让用户看到上次结果而不是一片空白
            }
            try {
                clashRuleProviders = parseClashProviders(clash.ruleProviders())
            } catch (_: Exception) {
                // 规则集订阅是可选端点，失败静默
            }
        } finally {
            clashRulesLoading = false
        }
    }

    /** 规则集订阅更新 */
    suspend fun updateClashRuleProvider(name: String): String = safe {
        clash.updateRuleProvider(name)
        "已触发更新，稍后刷新查看"
    }

    // ------------------------------------------------------------------ 日志页

    /**
     * 日志流状态源，供界面 `collectAsState()` 使用。
     *
     * 日志走 StateFlow 而不是 `mutableStateOf`：日志是**高频率**推送
     * （繁忙时每秒数百条），由流自身做环形缓冲与背压，比每来一条就写一次
     * Compose 状态更适合。
     */
    val clashLogStream: ClashLogStream get() = clashLog

    /** 当前订阅的日志等级 */
    var clashLogLevel by mutableStateOf(ClashLogLevel.INFO)
        private set

    /** 日志是否「暂停自动滚动」。暂停时连接不断，只是界面不再吸底 */
    var clashLogPaused by mutableStateOf(false)
        private set

    /** 是否正在接收日志（界面切到日志 Tab 时置 true） */
    var clashLogActive by mutableStateOf(false)
        private set

    /**
     * 进入日志页：建立连接。
     *
     * ⚠️ 必须在页面离开时调 [leaveClashLogs]，否则长连接会一直挂着。
     */
    fun enterClashLogs() {
        clashLogActive = true
        clashLog.start(clashLogLevel)
    }

    /** 离开日志页：断开连接，省电省流量 */
    fun leaveClashLogs() {
        clashLogActive = false
        clashLog.stop()
    }

    /** 切换订阅等级（会触发重连，内核只在建连时读 level） */
    fun applyClashLogLevel(level: ClashLogLevel) {
        clashLogLevel = level
        clashLog.setLevel(level)
    }

    /**
     * 暂停/恢复自动滚动。**不**断连接，日志仍在后台累积。
     *
     * 命名带 `Paused` 以对齐界面调用；**不叫** `setClashLogPaused`，
     * 避免与 `var clashLogPaused` 生成的 setter 撞名（见类头命名约定）。
     */
    fun toggleClashLogPaused() {
        clashLogPaused = !clashLogPaused
    }

    /** 清空已收日志 */
    fun clearClashLogs() {
        clashLog.clear()
        clashLogPaused = false
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

    // ------------------------------------------------------------------ WiFi 设置（SSID / 密码 / 安全模式）

    /**
     * 两个频段的 AP 配置。null = 还没读到；空 = 读不到 AP（WiFi 模块可能整体关闭）。
     *
     * 只在「WiFi 设置」页进入/刷新时拉取 —— `queryAccessPointInfo` 返回整个 AP
     * 配置（比单字段查询重），不进主轮询（频段开关状态另由 [wifiBands] 承担）。
     */
    var wifiApList by mutableStateOf<List<WifiAp>?>(null)
        private set

    suspend fun refreshWifiApConfig() {
        wifiApList = page("wifiSettings", emptyList()) {
            val o = goform.get(listOf("queryAccessPointInfo"), multiData = true)
            WifiAp.collect(o.getAsJsonArray("ResponseList"))
        }
    }

    /**
     * 保存一个频段的 WiFi 配置 —— 与设备网页版完全同款：
     * `goformId=setAccessPointInfo`、`Password` 要 base64、开放网络改提交
     * `EncrypType=NONE` 且不带 Password，成功判据 `result == 'success'`
     * （`goformAction` 已封装重试与判定）。
     *
     * ⚠️ **保存后该频段 WiFi 会重启**：连着它的设备（包括本机）都会断开几秒；
     * SSID / 密码改了的话还要用新凭据重连 —— UI 必须先弹确认再提交。
     */
    suspend fun setWifiAp(cfg: WifiAp): String = safe {
        val params = buildMap {
            put("SSID", cfg.ssid)
            put("AuthMode", cfg.authMode)
            if (cfg.isOpen) {
                put("EncrypType", "NONE")
            } else {
                put("Password", java.util.Base64.getEncoder().encodeToString(cfg.password.toByteArray()))
            }
            put("ApBroadcastDisabled", if (cfg.broadcastDisabled) "1" else "0")
            put("ApIsolate", "0")
            put("ChipIndex", cfg.chipIndex.toString())
            put("AccessPointIndex", cfg.apIndex)
            put("Pmf_switch", cfg.pmf)
        }
        goformAction("setAccessPointInfo", params)
    }


    // ------------------------------------------------------------------ 拉黑终端

    /** 设备黑名单（含 AclMode）。null = 尚未读到（界面显示加载中）。 */
    var aclState by mutableStateOf<AclState?>(null)
        private set

    /** 黑名单列表读取失败的提示，null 表示正常 */
    var aclError by mutableStateOf<String?>(null)
        private set

    /**
     * 刷新黑名单。
     *
     * 走 `cmd=station_list,lan_station_list,queryDeviceAccessControlList,hostNameList`
     * （**必须整体、不能带 multi_data**，详见 [GoformClient.deviceAccessControlList]）
     * 且**需要登录 Cookie** —— 未登录时设备回 `{}`，读不出任何东西。
     *
     * 因此这里先确保 cookie 再读。读失败（含未登录）时**保留上一次的值**
     * （与 [refreshSmsForward] 同策略），避免网络抖动把已拉黑的条目在界面上清空。
     */
    suspend fun refreshBlacklist() {
        val state = try {
            val cookie = ensureCookie() ?: run {
                aclError = ACL_LOGIN_FAILED_MSG
                return
            }
            goform.deviceAccessControlList(cookie)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            aclError = e.message ?: "读取黑名单失败"
            return
        }
        if (state == null) {
            // 设备没返回 AclMode —— 多半是会话过期，清 cookie 让下次重登
            goformCookie = null
            aclError = ACL_READ_FAILED_MSG
            return
        }
        aclState = state
        aclError = null
    }

    /**
     * 拉黑一台终端。
     *
     * 契约对齐设备 Web 端的 `setOrRemoveDeviceFromBlackList`：把该终端 MAC 追加进
     * 黑名单列表后**整体写回**。
     *
     * ⚠️ 名称字段（`BlackNameList`）实测**中文会被设备丢弃**，且与 MAC 列表不同序，
     * 因此只作"尽力而为"的附加信息写入，界面一律以 MAC 为准。
     *
     * @return 成功返回 null；失败返回可读原因（供界面提示）
     */
    suspend fun blockClient(client: ClientDevice): String? = safeWithReason {
        if (aclState?.containsMac(client.mac) == true) return@safeWithReason null
        val cur = ensureAclState() ?: return@safeWithReason aclError ?: ACL_READ_FAILED_MSG
        val next = AclState.withEntry(cur, client.mac, clientNameOf(client))
        writeAndRefreshAcl(next)
    }

    /** 从黑名单移除一条（MAC 大小写不敏感） */
    suspend fun unblockMac(mac: String): String? = safeWithReason {
        val cur = ensureAclState() ?: return@safeWithReason aclError ?: ACL_READ_FAILED_MSG
        val next = AclState.withoutMac(cur, mac)
        writeAndRefreshAcl(next)
    }

    /**
     * 覆盖写入整个黑名单（黑名单编辑页用）。
     *
     * 设备端要求名称列表与 MAC 列表同长度，这里补齐/截断到等长后再提交；
     * 但设备**可能丢弃名称**（见 [blockClient] 的说明），回读后以设备结果为准。
     */
    suspend fun saveBlacklist(macs: List<String>, names: List<String>): String? = safeWithReason {
        val cur = ensureAclState() ?: return@safeWithReason aclError ?: ACL_READ_FAILED_MSG
        val cleanMacs = macs.map { it.trim() }.filter { it.isNotEmpty() }
        val cleanNames = cleanMacs.indices.map { names.getOrNull(it)?.trim().orEmpty() }
        writeAndRefreshAcl(cur.copy(blackMacs = cleanMacs, blackNames = cleanNames))
    }

    /** 拿当前黑名单：优先用已有状态，没有就先读一次。返回 null 表示读不到。 */
    private suspend fun ensureAclState(): AclState? {
        aclState?.let { return it }
        refreshBlacklist()
        return aclState
    }

    /** 写黑名单并**以设备回读结果覆盖本地状态**（设备会重排/丢名，本地不可信） */
    private suspend fun writeAndRefreshAcl(next: AclState): String? {
        val err = writeAcl(next)
        if (err == null) refreshBlacklist()
        return err
    }

    /**
     * 写黑名单到设备。返回 null 表示成功，否则为失败原因。
     *
     * 会话过期时（设备回 `result:"1"`）清掉缓存 cookie 重登一次再写，
     * 与 [goformAction] 的重试策略一致。
     */
    private suspend fun writeAcl(state: AclState): String? {
        return try {
            var cookie = ensureCookie()
            if (cookie.isNullOrBlank()) return ACL_LOGIN_FAILED_MSG
            var result = goform.setDeviceAccessControlList(cookie, state)
            if (result == "1" || result == "3") {
                goformCookie = null
                cookie = ensureCookie()
                if (cookie.isNullOrBlank()) return ACL_LOGIN_FAILED_MSG
                result = goform.setDeviceAccessControlList(cookie, state)
            }
            if (result.equals("success", true) || result == "0") null
            else if (result == "1" || result == "3") ACL_LOGIN_FAILED_MSG
            else "操作失败($result)"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "操作失败：${e.message ?: "网络异常"}"
        }
    }

    /** 该终端是否已被拉黑 */
    fun isBlocked(mac: String): Boolean = aclState?.containsMac(mac) == true

    // ------------------------------------------------------------------ 设备系统设置（设置页「系统设置」组）

    /**
     * 休眠时间（分钟）；`-1` = 从不休眠。
     *
     * `null` 有两种可能，界面都要当作"读不到"处理、不显示该项：
     * ① 还没读过（进页面时才会拉）；② 设备不支持该字段（回空串）。
     */
    var sleepMinutes by mutableStateOf<Int?>(null)
        private set

    /** 内网设置；null = 没读到 */
    var lanSetting by mutableStateOf<LanSetting?>(null)
        private set

    /** 蜂窝数据开关；null = 没读到 */
    var cellularOn by mutableStateOf<Boolean?>(null)
        private set

    /**
     * NFC 状态。`supported == false` 时设置页**不显示 NFC 入口**——
     * 设备按硬件能力决定是否支持，直接照抄这个判据比猜机型可靠。
     */
    var nfcState by mutableStateOf<NfcState>(NfcState(supported = false))
        private set

    /** USB 调试（ADB）开关；null = 没读到 */
    var usbDebugOn by mutableStateOf<Boolean?>(null)
        private set

    /**
     * 拉取「系统设置」组里几个新条目的状态。
     *
     * 逐项独立 try/catch：这四项走的是四条不同的通道（goform 免鉴权 / goform 需 Cookie /
     * `/api/`），任一项失败不该把其余项一起拖没。失败项保持 null，界面自然不显示。
     *
     * 只在进入设置页时调一次，不进主轮询——这些都是**低频改动**的配置，
     * 每小时都不会变一次，没必要占轮询预算。
     */
    suspend fun refreshDeviceSettings() {
        // 休眠：免鉴权 goform，最轻
        try {
            sleepMinutes = goform.sleepMinutes()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        // 内网：免鉴权 goform（读走 lan_ipaddr 那组）
        try {
            lanSetting = goform.lanSetting()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        // 数据开关：免鉴权 goform
        try {
            cellularOn = goform.cellularDataOn()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        // NFC：免鉴权 goform（先问能力再问状态）
        try {
            nfcState = goform.nfcState()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        // USB 调试：只能走 /api/，令牌失效时重取一次
        try {
            usbDebugOn = api.adbUsbDebugOn()
        } catch (e: ApiException) {
            if (e.code == 401) {
                try {
                    api.refreshDeviceToken()
                    usbDebugOn = api.adbUsbDebugOn()
                } catch (_: Exception) {
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * 设置休眠时间；[minutes] 传 -1 表示从不休眠。返回 null = 成功，否则为失败原因。
     *
     * 命名用 `applyXxx` 而不是 `setXxx`：Kotlin 的 `var sleepMinutes` 已经生成了
     * JVM 方法 `setSleepMinutes(int)`，同名手写会撞签名（真实编译错误，首构建踩过）。
     */
    suspend fun applySleepMinutes(minutes: Int): String? = safeWithReason {
        val err = goformWrite("休眠时间") { cookie -> goform.setSleepMinutes(cookie, minutes) }
        if (err == null) {
            sleepMinutes = minutes
            // 回读一次拿设备认定的值（设备可能把不支持的档位归一成别的）
            sleepMinutes = try {
                goform.sleepMinutes()
            } catch (_: Exception) {
                minutes
            }
        }
        err
    }

    /**
     * 保存内网设置。
     *
     * ⚠️⚠️ **设备会重启网络服务**，本机 WiFi 会短暂断开、需要用新网关重连。
     * 界面**必须先做二次确认**再调这里。改动生效后原地址可能不通，
     * 因此这里不自动重连、也**不自动回读**（回读大概率超时），只回报写入结果。
     */
    suspend fun applyLanSetting(next: LanSetting): String? = safeWithReason {
        IpValidator.validate(next)?.let { return@safeWithReason it }
        goformWrite("内网设置") { cookie -> goform.setLanSetting(cookie, next) }
    }

    /**
     * 开关蜂窝数据。
     *
     * 写完之后**轮询回读确认**（与设备 Web 端 `waitForCellularState` 同思路）：
     * 这两个 goformId 回 `success` 只代表收到指令，真正断开/拨号要几秒，
     * 不确认就回报"成功"很容易骗到用户。
     */
    suspend fun setCellularData(on: Boolean): String? = safeWithReason {
        val err = goformWrite("数据开关") { cookie -> goform.setCellularData(cookie, on) }
        if (err != null) return@safeWithReason err

        // 最多等 8 秒，每 600ms 探一次
        var latest: Boolean? = null
        repeat(13) {
            delay(600)
            latest = try {
                goform.cellularDataOn()
            } catch (_: Exception) {
                null
            }
            if (latest == on) {
                cellularOn = latest
                return@safeWithReason null
            }
        }
        cellularOn = latest
        "指令已下发，但设备尚未切换到「${if (on) "开" else "关"}」状态，请稍后刷新查看"
    }

    /** 开关 NFC（并选择配对 WiFi；只切开关时传当前 [NfcState.ap] 即可） */
    suspend fun setNfc(enabled: Boolean, ap: String = nfcState.ap): String? = safeWithReason {
        val err = goformWrite("NFC") { cookie -> goform.setNfc(cookie, enabled, ap) }
        if (err == null) {
            nfcState = try {
                goform.nfcState()
            } catch (_: Exception) {
                nfcState.copy(enabled = enabled, ap = ap)
            }
        }
        err
    }

    /**
     * 开关 USB 调试（ADB）。
     *
     * ⚠️ 关掉 ADB 后本机 `adb connect` 会断开——这是**预期行为**，不是 bug。
     * 该开关走 `/api/adb/mode`，不走 goform。
     */
    suspend fun setUsbDebug(enabled: Boolean): String? {
        val r = safe { api.setAdbUsbDebug(enabled) }
        return if (r == "success") {
            usbDebugOn = enabled
            null
        } else {
            r
        }
    }

    /**
     * goform 写入的公共外壳：确保 cookie、失败时重登重试一次、统一判据。
     *
     * 与 [writeAcl] 同一套策略，抽出来给新增的几个 goform 写操作共用，
     * 免得每加一项就把"清 cookie 重登"的逻辑抄一遍。
     *
     * @return null = 成功；否则为失败原因
     */
    private suspend fun goformWrite(
        label: String,
        action: suspend (cookie: String) -> String
    ): String? {
        return try {
            var cookie = ensureCookie()
            if (cookie.isNullOrBlank()) return "$label 失败：$LOGIN_FAILED_MSG"
            var result = action(cookie)
            // 设备的会话失效码：本服务 "1"、老固件 "3"
            if (result == "1" || result == "3") {
                goformCookie = null
                cookie = ensureCookie()
                if (cookie.isNullOrBlank()) return "$label 失败：$LOGIN_FAILED_MSG"
                result = action(cookie)
            }
            when {
                result.equals("success", true) || result == "0" -> null
                result == "1" || result == "3" -> "$label 失败：$LOGIN_FAILED_MSG"
                else -> "$label 失败($result)"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "$label 失败：${e.message ?: "网络异常"}"
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
            // QoS：QCI 等级 + 合约速率（AMBR，单位 Mbps）。
            //
            // ⚠️ 合约速率的字段名是 **`ambr_dl_max` / `ambr_ul_max`**（带 `_max` 后缀）——
            // 不带后缀的 `ambr` / `ambr_dl` / `ambr_ul` 实测**全部返回空 `{}`**，
            // 这曾经导致误判「U60Pro 拿不到合约速率」。
            //
            // 真实字段名是在设备网页版的脚本里翻到的（它渲染 `ambr_dl_max`），
            // 实测三个字段**未登录也能读、可 multi_data 批量取**，一次轮询全拿到。
            //
            // 历史：中间实现过「grep 设备日志 /data/logfs/key.log 的 session_ambr_*」，
            // 虽然能用但要 shell 权限、要扫 2.8MB 日志（0.74 秒），已被本方案取代。
            // 两者的值同源（网页版注释也写明它自己是从 key.log 取的），故无精度差异。
            "qci", "ambr_dl_max", "ambr_ul_max",
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
         * 检查更新前，等待「网络可用」的最长时间。
         *
         * 冷启动时网络栈/DNS 就绪通常只要 1~3 秒，8 秒足够覆盖；
         * 超过这个时间基本可以判定是真的没网，没必要继续等。
         */
        private const val NETWORK_WAIT_TIMEOUT_MS = 8_000L

        /** 等待网络时的轮询间隔 */
        private const val NETWORK_POLL_INTERVAL_MS = 300L

        /**
         * 检查更新首次失败后的补试间隔。
         *
         * 网络刚就绪的一瞬间 DNS 可能还没生效，隔 1.2 秒再问一次能显著提高成功率，
         * 又不会让手动点击的用户等太久。
         */
        private const val UPDATE_RETRY_DELAY_MS = 1_200L

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

        // 注：QCI 与合约速率（ambr_dl_max / ambr_ul_max）已并入 [POLL_FIELDS]，
        // 随主轮询一起取，这里不再需要单独的刷新步骤。
        // （曾有一版单独 grep 设备日志 /data/logfs/key.log 的实现，已废弃。）

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
    } catch (e: ClashException) {
        // 面板异常自带「地址错 / secret 错 / 超时」这类可读文案，直接透传
        e.message ?: "面板操作失败"
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e // 协程取消要原样抛出，否则会吞掉取消信号
    } catch (e: Exception) {
        "操作失败：${e.message ?: "未知错误"}"
    }

    /**
     * 与 [safe] 同源，但约定 **null = 成功**、非 null 字符串 = 失败原因。
     *
     * 黑名单这类「成功无需文案、失败才提示」的操作用它更顺手：
     * 界面写 `val err = vm.blockClient(c); if (err != null) toast(err)`，
     * 不必再区分 `"success"` 这个魔法字符串。
     *
     * ⚠️ 同样**绝不向上抛**（原因见 [safe]）。
     */
    private suspend fun safeWithReason(block: suspend () -> String?): String? = try {
        block()
    } catch (e: ApiException) {
        e.message ?: "操作失败"
    } catch (e: ClashException) {
        e.message ?: "面板操作失败"
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
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

    /**
     * 刷新**设备上已安装**的插件列表。
     *
     * 数据源与商店列表完全无关：读 `/api/get_custom_head` 的 `text`，
     * 里面每个插件是一段 `[KANO_PLUGIN_START]` … `[KANO_PLUGIN_END]` 块，
     * 块首可能有一行 `[KANO_META]` 台账（版本 / 商店编号 / 原始文件名），
     * 详见 [InstalledPlugin]。
     *
     * 这个接口**不会因设备令牌失效而崩**：走 `getJson`，401 时 `page` 会把错误
     * 记进 `pageError["installedPlugins"]`，列表保持上一次的值。
     */
    suspend fun refreshInstalledPlugins() {
        val list = page("installedPlugins", null as List<InstalledPlugin>?) {
            val root = api.getCustomHead()
            val text = root.get("text")?.takeIf { it.isJsonPrimitive }?.asString
            InstalledPlugin.parseAll(text)
        }
        if (list != null) {
            installedPluginList = list
            // 同步给商店列表用来打「已安装」标记：块名与商店公开名/安装名都算命中
            installedPlugins = buildSet {
                list.forEach {
                    add(it.name)
                    if (it.publicName.isNotBlank()) add(it.publicName)
                    if (it.sid.isNotBlank()) add(it.sid)
                }
            }
        }
    }

    /**
     * 卸载已安装插件。
     *
     * 与商店的 [uninstallPlugin] 是**两条不同的路**：
     * - 商店卸载走 `/api/plugin/uninstall`（按商店的 name 卸）；
     * - 这里走 `/api/set_custom_head` —— 把对应块从 `custom_head` 文本里删掉再整体写回。
     *
     * 之所以要两条路：设备上有些插件是手工贴进 custom_head 的脚本，
     * 商店里根本没有对应条目，只能按块名删。官方的「插件管理」用的也是这条。
     *
     * @return null = 成功；否则为失败原因
     */
    suspend fun removeInstalledPlugin(plugin: InstalledPlugin): String? = safeWithReason {
        val root = api.getCustomHead()
        val text = root.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (text.isBlank()) return@safeWithReason "读取设备插件列表失败"

        val next = stripPluginBlocks(text, setOf(plugin.name))
        if (next == text) return@safeWithReason "设备上已找不到「${plugin.name}」"

        val err = api.setCustomHead(next)
        if (err != "success") return@safeWithReason err
        refreshInstalledPlugins()
        refreshPlugins()
        null
    }

    /**
     * 调整已安装插件的顺序。
     *
     * 插件在 `custom_head` 里是**顺序执行**的，排在前面的先跑 —— 有依赖关系时顺序有意义
     * （例如某个插件要给后面的插件挂 UI 容器）。所以这里不是纯展示排序，
     * 而是真的把文本块按新顺序重排后写回设备。
     */
    suspend fun reorderInstalledPlugin(from: Int, to: Int): String? = safeWithReason {
        val list = installedPluginList
        if (from !in list.indices || to !in list.indices || from == to) return@safeWithReason null

        val root = api.getCustomHead()
        val text = root.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (text.isBlank()) return@safeWithReason "读取设备插件列表失败"

        // 按块切分（保留块外内容），再只重排插件块之间的相对位置
        val next = reorderPluginBlocks(text, from, to)
        val err = api.setCustomHead(next)
        if (err != "success") return@safeWithReason err

        // 本地也按新序排一次，避免等回读
        val reordered = list.toMutableList().apply { add(to, removeAt(from)) }
        installedPluginList = reordered
        null
    }

    suspend fun refreshPlugins() {
        data class PluginBundle(val installed: Set<String>, val store: List<StorePlugin>)

        val b = page("plugins", null as PluginBundle?) {
            // 已安装集合从 custom_head 取（唯一权威来源）。
            // 以前这里读的是 `/api/plugin/list` 的 `plugins` 数组，但实测该接口返回的是
            // **商店全量**、根本没有 `plugins` 键，于是这个集合恒为空、商店条目的
            // 「已安装」标记从来没生效过。改读 custom_head 的插件块名 / 台账名。
            val installedSet = mutableSetOf<String>()
            try {
                val root = api.getCustomHead()
                val text = root.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                InstalledPlugin.parseAll(text).forEach { p ->
                    // 同时登记「原名」与「剥掉扩展名的原名」：
                    // 设备块名是 `u60屏幕管理插件`，商店的 installName 可能是 `u60屏幕管理插件.txt`，
                    // 不归一化就匹配不上（商店条目的「已安装」标记会不亮）。
                    listOf(p.name, p.publicName, p.sid)
                        .filter { it.isNotBlank() }
                        .forEach { installedSet += it; installedSet += stripExt(it) }
                }
            } catch (_: Exception) {
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

    /**
     * 设备上「网页版 UFI-TOOLS」的地址。
     *
     * 网页版与 App 用的是**同一个服务、同一个端口** ——
     * `ufi-tools-u60pro` 进程同时监听 `:2333`（实测 `netstat` 确认），
     * 既提供 App 调的 `/api/` 下的接口，也直接吐网页界面（`GET /` 返回 177KB 的
     * `lang="zh-cn"` 页面）。所以地址就是 [DeviceConfig.baseUrl]，
     * 不需要另拼端口。
     *
     * ⚠️ 别跟设备原生 Web 后台搞混：那是 uhttpd 在 **80 端口**上的
     * 中兴自带界面（`/usr/zte_web/web`，25KB），与本项目的网页版完全不是一回事。
     *
     * ⚠️ 写 KDoc 时注意：`/api/` 后面直接跟星号会被当成**注释起始**，
     * 导致后面的内容全被吞掉（编译报 "Unclosed comment"）。
     * 本注释上方原本写的就是这个形式，已改掉。
     */
    fun webUiUrl(): String = config.baseUrl

    /**
     * 网页版自动登录是否已经尝试过。
     *
     * ⚠️ 这个标志是**故意跨页面实例保持**的（放在 ViewModel 而不是 WebScreen 的
     * `remember`）：网页版的「登出」按钮会清掉 localStorage 里的凭据，
     * 如果每次进页面都重新注入，就等于"用户登出无效、一进来又被登回去"。
     *
     * 现在的语义：App 启动后**第一次**进入网页版自动登录；用户若主动登出，
     * 再进出本页也不会被打扰，直到下次冷启动 App。
     */
    var webAutoLoginDone = false

    /**
     * 组装网页版自动登录凭据；返回 null 表示凭据不全、不做自动登录。
     *
     * 取值与理由见 [WebAutoLogin] 的详细说明（网页版把登录态放在 localStorage
     * 的 `kano_sms_pwd` / `kano_sms_token` 两个键里）。
     */
    fun webAutoLogin(): WebAutoLogin? {
        val pwd = config.adminPassword.trim()
        val token = config.token.trim()
        // 两个都必填：网页版 initRequestData 里 `!PWD` 与 `isNeedToken && !TOKEN`
        // 任一成立就判定未登录，缺一个注入也没用。
        if (pwd.isEmpty() || token.isEmpty()) return null
        return WebAutoLogin(password = pwd, tokenHash = Crypto.sha256Hex(token))
    }

    /**
     * 网页版应使用的配色参数。
     *
     * 三个色值都按当前明暗模式从配色表取：
     * - `pageBg` —— **App 顶栏那片的颜色**（页面背景），用作网页背景
     * - `textPrimary` —— 主文字色，保证正文与背景的对比度
     * - `accent` —— 强调色，换算成 HSV 后让网页的按钮/标题跟随主题色调
     *
     * ⚠️ 背景取的是 `pageBg`（页面背景 / 顶栏），**不是 `cardBg`（底栏）** ——
     * 梦幻紫下实测两者不同：顶栏 `rgb(26,22,48)`、底栏 `rgb(39,32,68)`。
     *
     * 「默认」主题的强调色是纯灰（饱和度 0），此时色调字段为 null、只同步背景与文字，
     * 详见 [WebThemeMapper]。
     */
    fun webTheme(): WebTheme {
        val palette = ThemePalettes.ALL.firstOrNull { it.id == paletteId }
            ?: ThemePalettes.ALL.first()
        val isDark = themeStore.resolveIsDark()
        return WebThemeMapper.from(
            accentArgb = if (isDark) palette.accentDark else palette.accentLight,
            pageBgArgb = if (isDark) palette.pageBgDark else palette.pageBgLight,
            textPrimaryArgb = if (isDark) palette.textPrimaryDark else palette.textPrimaryLight
        )
    }

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
        // 日志是长连接，ViewModel 没了必须显式释放，否则连接会挂到进程结束
        clashLog.release()
        super.onCleared()
    }
}
