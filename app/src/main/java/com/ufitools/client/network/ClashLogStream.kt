package com.ufitools.client.network

import com.ufitools.client.model.ClashLogEntry
import com.ufitools.client.model.ClashLogLevel
import com.ufitools.client.model.parseClashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * 内核日志流（`WS /logs`）。
 *
 * ## 为什么不用轮询
 *
 * 内核的日志**没有 REST 快照接口**，只有 WebSocket 单向推送。要「像
 * zashboard 那样实时滚动」，就必须维持一条长连接。
 *
 * ## 设计要点
 *
 * 1. **独立 OkHttpClient 的超时**：WebSocket 是长连接，`readTimeout`
 *    必须设成 0（无限），否则空闲一会儿就被判定超时断开。为此这里
 *    从 [ClashClient] 复制一份 client 并关掉读超时——共用同一个 client
 *    的话，普通请求的 10 秒读超时会把日志流一起掐掉。
 * 2. **环形缓冲**：日志可能每秒几百条，全存会把内存吃光。固定容量
 *    [capacity]，超出丢弃最旧的。
 * 3. **单一出口**：所有日志先推到 [Channel]，由唯一一个协程消费并写进
 *    [entries]，避免 OkHttp 回调线程与 Compose 线程各自写状态。
 * 4. **断线自动重连**：退避重试，间隔从 1 秒翻倍到上限 15 秒；用户主动
 *    停止时不再重连。
 *
 * ⚠️ **生命周期归 MainViewModel**：日志流持有长连接，界面切走时必须
 * 调 [stop]，否则连接会一直挂着。
 */
class ClashLogStream(private val client: ClashClient) {

    /** 环形缓冲容量：约 3000 条足够回看，再多对手机没有意义 */
    private val capacity = 3000

    /** 日志推送通道。容量给足，避免内核推得快时把回调线程阻塞 */
    private val inbox = Channel<ClashLogEntry>(Channel.UNLIMITED)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _entries = MutableStateFlow<List<ClashLogEntry>>(emptyList())

    /** 日志列表（尾部最新），供 Compose 直接 collect 成 state */
    val entries: StateFlow<List<ClashLogEntry>> = _entries.asStateFlow()

    private val _connected = MutableStateFlow(false)

    /** 是否已与内核建立日志连接 */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** 最近一次连接失败原因；成功连接后清空 */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 累计收到的日志条数（含被环形缓冲丢弃的），用于界面显示「共 N 条」 */
    private val _received = MutableStateFlow(0L)
    val received: StateFlow<Long> = _received.asStateFlow()

    private val seqGen = AtomicLong(0L)

    /** 当前订阅的最低等级。改变它会重连以让内核按新等级过滤 */
    @Volatile
    private var level: ClashLogLevel = ClashLogLevel.INFO

    /** 用户是否要求「暂停滚动」。暂停只是 UI 不再自动吸底，**连接不断**、日志照收 */
    @Volatile
    private var running = false

    private var connectJob: Job? = null
    private var drainJob: Job? = null
    private var socket: WebSocket? = null

    /** 悬着的重连等待，用于 stop 时立刻打断 */
    @Volatile
    private var attempt = 0

    init {
        // 消费协程常驻：即使没连接，也只是空转挂起，开销可以忽略
        drainJob = scope.launch {
            for (e in inbox) {
                val next = _entries.value + e
                _entries.value = if (next.size <= capacity) next else next.takeLast(capacity)
            }
        }
    }

    /**
     * 开始接收日志。
     *
     * 重复调用是安全的（已在跑就只更新等级、必要时重连）。
     */
    fun start(level: ClashLogLevel = this.level) {
        val levelChanged = level != this.level
        this.level = level
        if (running && !levelChanged) return
        running = true
        attempt = 0
        // 换等级需要重连，先拆掉旧连接（不置 error，避免闪一下红字）
        if (levelChanged) teardownSocket()
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { connectLoop() }
    }

    /** 停止接收并断开连接。切走页面时必须调用 */
    fun stop() {
        running = false
        teardownSocket()
        connectJob?.cancel()
        connectJob = null
        _connected.value = false
    }

    /** 清空已收日志与计数（不断连接） */
    fun clear() {
        _entries.value = emptyList()
        _received.value = 0L
        // 清空后 seq 继续递增，不复位——复位会让还在列表里飞的旧条目 key 撞车
    }

    /**
     * 切换订阅等级。内核只在**建连时**读 level 参数，所以这里必须重连。
     */
    fun setLevel(newLevel: ClashLogLevel) {
        if (newLevel == level) return
        level = newLevel
        if (!running) return
        scope.launch {
            teardownSocket()
            connectJob?.cancel()
            attempt = 0
            connectJob = scope.launch { connectLoop() }
        }
    }

    private fun teardownSocket() {
        socket?.let { runCatching { it.cancel() } }
        socket = null
        _connected.value = false
    }

    /**
     * 连接循环：连上就一直挂着等推送，断开就退避重连。
     *
     * 退避序列 1s / 2s / 4s / 8s / 15s（封顶）。用户点了停止就退出循环。
     */
    private suspend fun connectLoop() {
        while (running) {
            try {
                awaitSocket()
            } catch (e: Exception) {
                if (!running) return
                _connected.value = false
                _error.value = friendly(e)
            }
            if (!running) return
            // 正常结束（内核重启/网络抖动）也要退避，避免疯狂重连把设备拖死
            attempt = min(attempt + 1, 5)
            val wait = when (attempt) {
                1 -> 1_000L
                2 -> 2_000L
                3 -> 4_000L
                4 -> 8_000L
                else -> 15_000L
            }
            delay(wait)
        }
    }

    /**
     * 建立一条 WebSocket 并挂起到断开为止。
     *
     * 用 [suspendCancellableCoroutine] 把回调式 API 包成挂起函数，这样
     * 整个重连逻辑可以写成普通顺序代码，不用维护一堆状态标志。
     */
    private suspend fun awaitSocket() = suspendCancellableCoroutine<Unit> { cont ->
        val url = client.logStreamUrl(level.wire)
        val request = client.applyAuth(
            Request.Builder().url(url)
        ).build()

        // 长连接必须关掉读超时，否则空闲即被判超时
        val wsClient = client.rawClient().newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        val listener = object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                attempt = 0
                _connected.value = true
                _error.value = null
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val now = System.currentTimeMillis()
                // 一行报文可能含多条（内核偶尔用 \n 拼接），逐行解析
                text.split('\n').forEach { line ->
                    val e = parseClashLog(line, now, seqGen.incrementAndGet()) ?: return@forEach
                    _received.value += 1
                    // trySend 对 UNLIMITED 通道不会失败，但保留兜底语义
                    inbox.trySend(e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                _connected.value = false
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                _connected.value = false
                if (cont.isActive) {
                    // 握手阶段失败能拿到状态码，直接翻成人话（401 最常见）
                    val code = response?.code ?: 0
                    if (code == 401) {
                        cont.resumeWithException(ClashException("认证失败：secret 不正确", 401))
                    } else {
                        cont.resumeWithException(t)
                    }
                }
            }
        }

        val ws = wsClient.newWebSocket(request, listener)
        socket = ws
        cont.invokeOnCancellation { ws.cancel() }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is ClashException -> e.message ?: "日志连接失败"
        is java.net.ConnectException -> "无法连接内核日志接口，请确认面板地址"
        is java.net.SocketTimeoutException -> "日志连接超时"
        else -> "日志连接中断：${e.message ?: e.javaClass.simpleName}"
    }

    /** 释放全部资源（ViewModel 的 onCleared 调用） */
    fun release() {
        stop()
        drainJob?.cancel()
        drainJob = null
        inbox.close()
        _entries.value = emptyList()
    }
}
