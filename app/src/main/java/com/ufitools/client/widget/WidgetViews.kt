package com.ufitools.client.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.ufitools.client.MainActivity
import com.ufitools.client.R
import com.ufitools.client.data.ThemeStore
import com.ufitools.client.ui.components.carrierLogoResOf
import com.ufitools.client.ui.theme.ThemePalettes

/**
 * 把 [WidgetSnapshot] 铺进 RemoteViews。
 *
 * 三条硬约束（踩过就知道疼）：
 * 1. RemoteViews 只能 inflate **白名单控件**（LinearLayout / TextView / ImageView …），
 *    所以布局里不要出现 `<View>`、ConstraintLayout、自定义 View；
 * 2. `setInt(id, "setBackgroundColor", c)` 这类反射调用只允许白名单方法名，
 *    `setColorFilter` 不在其中，所以信号柱是靠换背景色而不是染色；
 * 3. 某个 id 在当前布局里不存在时对应 action 是空操作，但为了不依赖这个行为，
 *    这里按布局分别渲染，只设该布局真正拥有的 id。
 */
object WidgetViews {

    /** 刷新按钮的广播 action（发给自己这个 provider，不需要导出） */
    const val ACTION_REFRESH = "com.ufitools.client.widget.ACTION_REFRESH"

    /** App 侧刷完数据后通知桌面按缓存重绘的 action */
    const val ACTION_UPDATE = "com.ufitools.client.widget.ACTION_UPDATE"

    private val BAR_IDS = intArrayOf(
        R.id.widget_bar1, R.id.widget_bar2, R.id.widget_bar3, R.id.widget_bar4, R.id.widget_bar5
    )

    /** 4×2 的「低于这个高度就用单行布局」阈值（dp） */
    const val COMPACT_BELOW_HEIGHT_DP = 90

    // ------------------------------------------------------------------ 渲染

    /**
     * 生成某个小组件实例的 RemoteViews。
     *
     * @param providerClass 刷新按钮要把广播发回哪个 provider（必须传具体的子类，不能用基类，
     *                      否则 PendingIntent 指向不到任何已注册的组件）
     */
    fun render(
        context: Context,
        snapshot: WidgetSnapshot,
        layoutId: Int,
        providerClass: Class<*>
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutId)
        val accent = highlightColor(context)

        applyBars(
            views = views,
            bars = snapshot.signalBars,
            accent = accent,
            off = color(context, R.color.widget_bar_off),
            good = color(context, R.color.widget_good),
            warn = color(context, R.color.widget_warn)
        )
        applyRefreshAction(views, context, providerClass)
        // 点卡片空白处 = 打开 App
        views.setOnClickPendingIntent(R.id.widget_root, launchApp(context))

        // AGP 8 起 R 里的 id 不再是编译期常量，故用 if / else 而不是 when 分支
        val layout4x2 = R.layout.widget_ufi_4x2
        val layout4x1 = R.layout.widget_ufi_4x1
        if (layoutId == layout4x2) {
            render4x2(context, views, snapshot, accent)
        } else if (layoutId == layout4x1) {
            render4x1(context, views, snapshot, accent)
        } else {
            render2x1(context, views, snapshot, accent)
        }
        return views
    }

    /** 4×2：型号 / 制式 / 电量 / 今日 + 本月流量 / 温度 · CPU · 内存 · 信号 / 更新时间 */
    private fun render4x2(
        context: Context,
        views: RemoteViews,
        s: WidgetSnapshot,
        accent: Int
    ) {
        val hasData = s.updatedAt > 0L

        views.setTextViewText(
            R.id.widget_model,
            when {
                !hasData -> "设备连接失败"
                s.model.isNotBlank() -> s.model
                else -> context.getString(R.string.app_name)
            }
        )
        views.setTextViewText(R.id.widget_nettype, orDash(s.networkType))
        views.setTextColor(R.id.widget_nettype, accent)
        views.setTextViewText(R.id.widget_battery, batteryText(s))

        views.setTextViewText(R.id.widget_day, orDash(s.dayTraffic))
        views.setTextColor(R.id.widget_day, accent)
        views.setTextViewText(R.id.widget_month, orDash(s.monthTraffic))

        views.setTextViewText(R.id.widget_meta, metaText(s))
        views.setTextColor(R.id.widget_meta, color(context, R.color.widget_text_secondary))
        applyCarrierLogo(views, s)

        // 更新时间兼作离线指示：正常显示 HH:mm，拉取失败则标红
        if (s.online) {
            views.setTextViewText(R.id.widget_updated, s.updatedClock())
            views.setTextColor(R.id.widget_updated, color(context, R.color.widget_text_muted))
        } else {
            views.setTextViewText(R.id.widget_updated, if (hasData) "离线" else "--")
            views.setTextColor(R.id.widget_updated, color(context, R.color.widget_bad))
        }
    }

    /** 4×1：信号柱 / 制式 / 今日流量 / 温度·电量 / 刷新 */
    private fun render4x1(
        context: Context,
        views: RemoteViews,
        s: WidgetSnapshot,
        accent: Int
    ) {
        val hasData = s.updatedAt > 0L
        views.setTextViewText(R.id.widget_nettype, orDash(s.networkType))
        views.setTextColor(R.id.widget_nettype, accent)

        if (s.online) {
            views.setTextViewText(R.id.widget_day, "今日 ${orDash(s.dayTraffic)}")
            views.setTextColor(R.id.widget_day, color(context, R.color.widget_text_primary))
        } else {
            // 单行布局没有独立的位置放错误信息，就直接顶掉流量区
            views.setTextViewText(R.id.widget_day, s.message.ifBlank { "设备连接失败" })
            views.setTextColor(R.id.widget_day, color(context, R.color.widget_bad))
        }

        val meta = joinMeta(listOf(s.temp, batteryText(s), s.powerLabel))
        views.setTextViewText(R.id.widget_meta, meta.ifBlank { if (hasData) s.updatedClock() else "" })
        views.setTextColor(R.id.widget_meta, color(context, R.color.widget_text_secondary))
        applyCarrierLogo(views, s)
    }

    /** 2×1：信号柱 / 电量 / 今日流量 / 刷新（最紧凑，去掉一切标签） */
    private fun render2x1(
        context: Context,
        views: RemoteViews,
        s: WidgetSnapshot,
        accent: Int
    ) {
        views.setTextViewText(R.id.widget_battery, batteryText(s))
        if (s.online) {
            views.setTextViewText(R.id.widget_day, "今日 ${orDash(s.dayTraffic)}")
            views.setTextColor(R.id.widget_day, accent)
        } else {
            views.setTextViewText(R.id.widget_day, s.message.ifBlank { "设备连接失败" })
            views.setTextColor(R.id.widget_day, color(context, R.color.widget_bad))
        }
    }

    // ------------------------------------------------------------------ 部件

    /** 信号柱：点亮 [bars] 根，其余留半透明灰，5 根始终都在（视觉上是个固定刻度尺） */
    private fun applyBars(
        views: RemoteViews,
        bars: Int,
        accent: Int,
        off: Int,
        good: Int,
        warn: Int
    ) {
        val onColor = when {
            bars >= 4 -> good
            bars >= 3 -> accent
            bars >= 1 -> warn
            else -> 0
        }
        BAR_IDS.forEachIndexed { i, id ->
            val on = i < bars && onColor != 0
            // setBackgroundColor 在 RemoteViews 的反射白名单里，可安全用 setInt 调用
            views.setInt(id, "setBackgroundColor", if (on) onColor else off)
        }
    }

    private fun applyRefreshAction(views: RemoteViews, context: Context, providerClass: Class<*>) {
        val intent = Intent(context, providerClass).setAction(ACTION_REFRESH)
        val pi = PendingIntent.getBroadcast(
            context,
            REQ_REFRESH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh, pi)
    }

    private fun launchApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_LAUNCH,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ------------------------------------------------------------------ 文案

    /**
     * 运营商品牌标识。
     *
     * 4×2 / 4×1 的布局里各有一个 `widget_carrier`，由 [com.ufitools.client.ui.components.carrierLogoResOf]
     * 把代号映射成官方 logo 资源 —— 与 App 内共用同一张映射表，不会两边跑偏。
     *
     * 完全拿不到运营商信息时（离线首刷、或该固件没上报）直接隐藏，
     * 免得桌面上挂一个和"未知运营商"混淆的兜底灰标。
     */
    private fun applyCarrierLogo(views: RemoteViews, s: WidgetSnapshot) {
        val known = s.carrier.isNotBlank() && s.carrier != "-"
        views.setViewVisibility(R.id.widget_carrier, if (known) View.VISIBLE else View.GONE)
        if (known) {
            views.setImageViewResource(R.id.widget_carrier, carrierLogoResOf(s.carrierCode))
        }
    }

    /** 电量：充电时补一个闪电；读不到给 `--` */
    private fun batteryText(s: WidgetSnapshot): String = when {
        s.battery < 0 -> "--"
        s.charging -> "${s.battery}%⚡"
        else -> "${s.battery}%"
    }

    /**
     * 4×2 的元信息行：电源态 · 温度 · CPU · 内存 · 信号。
     *
     * **不含运营商**了：首行已有品牌 logo，再写一遍中文名是重复信息。
     */
    private fun metaText(s: WidgetSnapshot): String = joinMeta(
        listOf(
            s.powerLabel,
            s.temp,
            s.cpu.takeIf { it.isNotBlank() }?.let { "CPU $it" },
            s.ram.takeIf { it.isNotBlank() }?.let { "内存 $it" },
            s.rsrp
        )
    ).ifBlank { s.updatedClock() }

    /** 用 ` · ` 串起非空项（跳过取值失败的 `-`） */
    private fun joinMeta(parts: List<String?>): String =
        parts.filter { !it.isNullOrBlank() && it != "-" }.joinToString(" · ")

    /** 取值失败的字段统一显示 `--`（`networkTypeLabel` 之类会返回 `-`，这里再兜一层） */
    private fun orDash(value: String): String =
        if (value.isBlank() || value == "-") "--" else value

    // ------------------------------------------------------------------ 工具

    /**
     * 强调色取 App 主题的 **dataHighlight**（深色档）。
     *
     * 不用 `accentDark`：那是给「交互控件」用的，默认配色下是 `#555555`、科技蓝下是 `#0E5ACD`，
     * 压在小组件的深色卡片上几乎看不见。`dataHighlightDark` 是参考项目专为「设备名 / 流量数值」
     * 这类核心数据挑的亮色（默认 `#FFFFFF`、科技蓝 `#69B1FF`…），任何配色下都清晰。
     */
    private fun highlightColor(context: Context): Int {
        val id = ThemeStore(context).loadPaletteId()
        return ThemePalettes.byId(id).dataHighlightDark.toInt()
    }

    private fun color(context: Context, resId: Int): Int = context.getColor(resId)

    private const val REQ_LAUNCH = 100
    private const val REQ_REFRESH = 101
}
