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
 *
 * 配色从 [ThemeStore] 读的**配色 + 明暗模式**实时算出，不再依赖 `R.color.widget_*`
 * 那套写死的深色值（那套只在 values/ 里有一份，没有 values-night/，
 * 所以以前无论 App 怎么设，桌面卡片永远是深色蓝）。
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

    /**
     * 一次渲染用到的全部颜色，全部由 [Palette] + 明暗模式决定。
     *
     * 之所以先算成一个快照再往下传：`RemoteViews` 的每个 setter 都要单独取色，
     * 若各处自己去读 prefs / 算明暗，一次渲染里就可能跨到两个不同的主题状态
     * （用户在渲染中途改了主题），出现半深半浅的诡异画面。
     */
    private class WidgetColors(
        /** 卡片底 drawable（圆角 + 半透明）资源 id，按 配色×明暗 查表得到 */
        val cardBgRes: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int,
        val divider: Int,
        val barOff: Int,
        val good: Int,
        val warn: Int,
        val bad: Int,
        val accent: Int,
    ) {
        companion object {
            /** 正常 / 告警 / 危险三态的固定色：语义色不该跟着主题跑，否则"电量红"可能变成绿色 */
            private const val GOOD = 0xFF34C759.toInt()
            private const val WARN = 0xFFFF9F0A.toInt()
            private const val BAD = 0xFFFF453A.toInt()

            /**
             * 卡片底 drawable 查表：`[配色][0=浅 / 1=深]`。
             *
             * 必须走资源而不是 `setBackgroundColor`：后者会把 shape 里的 20dp 圆角一起抹掉，
             * 卡片直接变成直角。这些 drawable 由 `.workbuddy/tmp/gen_widget_bg.py`
             * 从 [ThemePalettes] 的 `cardBg` 生成，改配色后要重跑该脚本。
             */
            private val CARD_BG_RES = arrayOf(
                intArrayOf(R.drawable.widget_bg_0_light, R.drawable.widget_bg_0_dark),
                intArrayOf(R.drawable.widget_bg_1_light, R.drawable.widget_bg_1_dark),
                intArrayOf(R.drawable.widget_bg_2_light, R.drawable.widget_bg_2_dark),
                intArrayOf(R.drawable.widget_bg_3_light, R.drawable.widget_bg_3_dark),
                intArrayOf(R.drawable.widget_bg_4_light, R.drawable.widget_bg_4_dark),
            )

            fun resolve(context: Context): WidgetColors {
                val store = ThemeStore(context)
                val pid = store.loadPaletteId()
                val p = ThemePalettes.byId(pid)
                val dark = store.resolveIsDark()
                return WidgetColors(
                    cardBgRes = CARD_BG_RES[pid.coerceIn(0, CARD_BG_RES.size - 1)]
                        [if (dark) 1 else 0],
                    textPrimary = (if (dark) p.textPrimaryDark else p.textPrimaryLight).toInt(),
                    textSecondary = (if (dark) p.textSecondaryDark else p.textSecondaryLight).toInt(),
                    // muted 没有独立角色：按 App 内 AppTheme.textMuted 的算法（主文字 45% 透明度）
                    // 现算，保证两端一致。远端 RemoteViews 支持 #AARRGGBB 的 alpha 通道。
                    textMuted = (((if (dark) p.textPrimaryDark else p.textPrimaryLight) and 0x00FFFFFF) or
                        (0x73L shl 24)).toInt(),
                    divider = (if (dark) p.dividerDark else p.dividerLight).toInt(),
                    // 未点亮的信号柱：主文字的 20% 透明度，深浅两种底色下都是"隐约可见"
                    barOff = (if (dark) p.textPrimaryDark else p.textPrimaryLight).toInt() and 0x00FFFFFF or
                        (0x33 shl 24),
                    good = GOOD,
                    warn = WARN,
                    bad = BAD,
                    // 强调色取 dataHighlight：与 App 内「设备名 / 流量数值」用的是同一个角色
                    accent = (if (dark) p.dataHighlightDark else p.dataHighlightLight).toInt(),
                )
            }
        }
    }

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
        val c = WidgetColors.resolve(context)
        val accent = c.accent

        // 卡片背景跟着主题走。用 setBackgroundResource 而不是 setBackgroundColor：
        // 后者是纯色、会把 drawable 的 20dp 圆角一起抹掉，卡片会变直角。
        // 代价是不能直接传色值，所以按「配色 × 明暗」预生成了 10 个 shape 资源。
        views.setInt(R.id.widget_root, "setBackgroundResource", c.cardBgRes)

        applyBars(
            views = views,
            bars = snapshot.signalBars,
            accent = accent,
            off = c.barOff,
            good = c.good,
            warn = c.warn
        )
        applyRefreshAction(views, context, providerClass)
        // 点卡片空白处 = 打开 App
        views.setOnClickPendingIntent(R.id.widget_root, launchApp(context))

        // AGP 8 起 R 里的 id 不再是编译期常量，故用 if / else 而不是 when 分支
        val layout4x2 = R.layout.widget_ufi_4x2
        val layout4x1 = R.layout.widget_ufi_4x1
        val is4x2 = layoutId == layout4x2
        val is4x1 = layoutId == layout4x1

        // 标题（型号）与刷新图标也用主文字色，否则浅色主题下白字白底看不见。
        // 刷新图标三个布局都有，型号只在 4×2 里存在，所以要分开判断。
        views.setInt(R.id.widget_refresh, "setColorFilter", c.textSecondary)
        if (is4x2) {
            views.setTextColor(R.id.widget_model, c.textPrimary)
            // 4×2 中间那根竖分割线：布局里写死了 @color/widget_divider，
            // 之前没有 id、没法覆盖，浅色主题下会变成一根几乎看不见的白线。
            views.setInt(R.id.widget_divider_line, "setBackgroundColor", c.divider)
        }

        if (is4x2) {
            render4x2(context, views, snapshot, c)
        } else if (is4x1) {
            render4x1(context, views, snapshot, c)
        } else {
            render2x1(context, views, snapshot, c)
        }
        return views
    }

    /** 4×2：型号 / 制式 / 电量 / 今日 + 本月流量 / 温度 · CPU · 内存 · 信号 / 更新时间 */
    private fun render4x2(
        context: Context,
        views: RemoteViews,
        s: WidgetSnapshot,
        c: WidgetColors
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
        views.setTextColor(R.id.widget_model, c.textPrimary)
        views.setTextViewText(R.id.widget_nettype, orDash(s.networkType))
        views.setTextColor(R.id.widget_nettype, c.accent)
        views.setTextViewText(R.id.widget_battery, batteryText(s))
        views.setTextColor(R.id.widget_battery, c.textPrimary)

        views.setTextViewText(R.id.widget_day, orDash(s.dayTraffic))
        views.setTextColor(R.id.widget_day, c.accent)
        views.setTextViewText(R.id.widget_month, orDash(s.monthTraffic))
        views.setTextColor(R.id.widget_month, c.textPrimary)

        views.setTextViewText(R.id.widget_meta, metaText(s))
        views.setTextColor(R.id.widget_meta, c.textSecondary)
        applyCarrierLogo(views, s)

        // 更新时间兼作离线指示：正常显示 HH:mm，拉取失败则标红
        if (s.online) {
            views.setTextViewText(R.id.widget_updated, s.updatedClock())
            views.setTextColor(R.id.widget_updated, c.textMuted)
        } else {
            views.setTextViewText(R.id.widget_updated, if (hasData) "离线" else "--")
            views.setTextColor(R.id.widget_updated, c.bad)
        }
    }

    /** 4×1：信号柱 / 制式 / 今日流量 / 温度·电量 / 刷新 */
    private fun render4x1(
        context: Context,
        views: RemoteViews,
        s: WidgetSnapshot,
        c: WidgetColors
    ) {
        val hasData = s.updatedAt > 0L
        views.setTextViewText(R.id.widget_nettype, orDash(s.networkType))
        views.setTextColor(R.id.widget_nettype, c.accent)

        if (s.online) {
            views.setTextViewText(R.id.widget_day, "今日 ${orDash(s.dayTraffic)}")
            views.setTextColor(R.id.widget_day, c.textPrimary)
        } else {
            // 单行布局没有独立的位置放错误信息，就直接顶掉流量区
            views.setTextViewText(R.id.widget_day, s.message.ifBlank { "设备连接失败" })
            views.setTextColor(R.id.widget_day, c.bad)
        }

        val meta = joinMeta(listOf(s.temp, batteryText(s), s.powerLabel))
        views.setTextViewText(R.id.widget_meta, meta.ifBlank { if (hasData) s.updatedClock() else "" })
        views.setTextColor(R.id.widget_meta, c.textSecondary)
        applyCarrierLogo(views, s)
    }

    /** 2×1：信号柱 / 电量 / 今日流量 / 刷新（最紧凑，去掉一切标签） */
    private fun render2x1(
        context: Context,
        views: RemoteViews,
        s: WidgetSnapshot,
        c: WidgetColors
    ) {
        views.setTextViewText(R.id.widget_battery, batteryText(s))
        views.setTextColor(R.id.widget_battery, c.textPrimary)
        if (s.online) {
            views.setTextViewText(R.id.widget_day, "今日 ${orDash(s.dayTraffic)}")
            views.setTextColor(R.id.widget_day, c.accent)
        } else {
            views.setTextViewText(R.id.widget_day, s.message.ifBlank { "设备连接失败" })
            views.setTextColor(R.id.widget_day, c.bad)
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

    private const val REQ_LAUNCH = 100
    private const val REQ_REFRESH = 101
}
