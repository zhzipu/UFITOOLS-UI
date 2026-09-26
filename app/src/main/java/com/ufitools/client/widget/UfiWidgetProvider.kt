package com.ufitools.client.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.ufitools.client.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 三种尺寸小组件的公共实现。
 *
 * 生命周期要点：
 * - `onUpdate`：系统按 `updatePeriodMillis`（本工程 30 分钟）唤醒，或小组件刚被添加时触发。
 *   先用**本地缓存**立刻渲染，再去后台拉数据——网络往返要 1~2 秒，不让用户盯着空白卡片。
 * - `onReceive` 里的自定义 action：刷新按钮 [WidgetViews.ACTION_REFRESH]，
 *   以及 App 侧刷完数据后的 [WidgetViews.ACTION_UPDATE]。后者直接吃缓存，不再重复请求设备。
 * - 两个异步分支都用 `goAsync()` 挂住广播：`onReceive` 一返回，进程随时可能被杀。
 *
 * 刻意**没有**引入 WorkManager：系统的 30 分钟周期 + 手动刷新按钮 + App 前台轮询时的主动推送
 * 已经覆盖实际使用场景，少一个依赖少一份构建风险。
 */
abstract class UfiWidgetProvider : AppWidgetProvider() {

    /** 名义布局：该尺寸默认的长相 */
    protected abstract val nominalLayout: Int

    /**
     * 高度被压到 [WidgetViews.COMPACT_BELOW_HEIGHT_DP] 以下时降级用的布局。
     * 4×2 可以被纵向拖到 1 格高，那时四行内容塞不下，改用单行布局。
     */
    protected open val compactLayout: Int? = null

    // ------------------------------------------------------------------ 系统回调

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val cached = WidgetStore(context).load()
        if (cached != null) {
            appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, viewsFor(context, id, cached)) }
        }
        refreshAsync(context, force = false)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        // 用户拖动缩放改了尺寸：按新尺寸重选布局，用缓存重绘即可，没必要再发请求
        val cached = WidgetStore(context).load() ?: return
        appWidgetManager.updateAppWidget(appWidgetId, viewsFor(context, appWidgetId, cached))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            WidgetViews.ACTION_REFRESH -> refreshAsync(context, force = true)

            WidgetViews.ACTION_UPDATE -> {
                // App 已经拉过一轮并写入缓存，直接按缓存重绘，不再重复打设备
                val cached = WidgetStore(context).load() ?: return
                val manager = AppWidgetManager.getInstance(context)
                manager.getAppWidgetIds(ComponentName(context, javaClass)).forEach { id ->
                    manager.updateAppWidget(id, viewsFor(context, id, cached))
                }
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 本实例自己的 RemoteViews；布局按当前实际高度选（会被用户拖拽改变） */
    private fun viewsFor(context: Context, widgetId: Int, snapshot: WidgetSnapshot): RemoteViews =
        WidgetViews.render(context, snapshot, layoutFor(context, widgetId), javaClass)

    private fun refreshAsync(context: Context, force: Boolean) {
        val pending = goAsync()
        val appContext = context.applicationContext
        // 必须先取出 provider 的类：协程里 `this` 是 CoroutineScope，
        // 直接写 javaClass 拿到的是 CoroutineScope 的类，不是这个 provider
        val providerClass = javaClass
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val snapshot = WidgetData.refresh(appContext, force)
                val manager = AppWidgetManager.getInstance(appContext)
                manager.getAppWidgetIds(ComponentName(appContext, providerClass)).forEach { id ->
                    manager.updateAppWidget(
                        id,
                        WidgetViews.render(appContext, snapshot, layoutFor(appContext, id), providerClass)
                    )
                }
            } catch (_: Exception) {
                // 拉取失败时保留桌面上已有的内容，不要把它擦成空白
            } finally {
                pending?.finish()
            }
        }
    }

    /** 按当前实际高度在「名义布局 / 降级布局」之间选一个 */
    private fun layoutFor(context: Context, widgetId: Int): Int {
        val compact = compactLayout ?: return nominalLayout
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return if (minHeight in 1 until WidgetViews.COMPACT_BELOW_HEIGHT_DP) compact else nominalLayout
    }
}

/** 4×2：完整信息。被压扁时退化成单行布局。 */
class UfiWidget4x2 : UfiWidgetProvider() {
    override val nominalLayout: Int = R.layout.widget_ufi_4x2
    override val compactLayout: Int = R.layout.widget_ufi_4x1
}

/** 4×1：单行状态条。 */
class UfiWidget4x1 : UfiWidgetProvider() {
    override val nominalLayout: Int = R.layout.widget_ufi_4x1
}

/** 2×1：最紧凑的一格。 */
class UfiWidget2x1 : UfiWidgetProvider() {
    override val nominalLayout: Int = R.layout.widget_ufi_2x1
}

/**
 * App 侧的数据推送入口。
 *
 * 用显式广播（指定 component）通知三个 provider 按缓存重绘。
 * 不走 `AppWidgetManager.updateAppWidget(ComponentName, RemoteViews)` 是因为
 * 那需要在这里重建 RemoteViews，就得知道每个 provider 的布局与刷新按钮该指向哪个类；
 * 让 provider 自己处理自己的重绘，职责更干净。
 */
object WidgetBus {

    private val PROVIDERS = listOf(
        UfiWidget4x2::class.java,
        UfiWidget4x1::class.java,
        UfiWidget2x1::class.java
    )

    /**
     * 通知所有**已放置在桌面上**的小组件按缓存重绘。
     *
     * 先查有没有实例再发广播：没往桌面放过这个尺寸时，广播会把我们自己的接收器白唤醒一次。
     */
    fun notifyAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        PROVIDERS.forEach { cls ->
            if (manager.getAppWidgetIds(ComponentName(context, cls)).isEmpty()) return@forEach
            context.sendBroadcast(Intent(context, cls).setAction(WidgetViews.ACTION_UPDATE))
        }
    }
}
