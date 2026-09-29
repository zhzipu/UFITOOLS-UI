package com.ufitools.client.data

import kotlin.math.roundToInt

/**
 * 写给网页版 UFI-TOOLS 的配色参数。
 *
 * ## 网页版配色是怎么工作的
 *
 * 设备网页版（`/script/theme.js`，v0.3.2）把配色存在 localStorage，
 * 页面加载时用 **HSV** 算出 CSS 变量：
 *
 * ```js
 * const { r, g, b } = hsvToRgb(currentHue, currentSaturation, currentValue);
 * document.documentElement.style.setProperty('--dark-bgi-color', `rgba(...)`);
 * ```
 *
 * | 键 | 含义 | 默认 |
 * |---|---|---|
 * | `themeColor` | 色相 h | 201 |
 * | `colorPer` | 色相滑块 0~100，`h = colorPer / 100 * 300` | 67 |
 * | `saturationPer` | HSV 的 S（0~100 → 0~1） | 100 |
 * | `brightPer` | HSV 的 V（0~100 → 0~1） | 21 |
 * | `opacityPer` | 背景色 alpha（0~100 → 0~1） | 21 |
 * | `textColor` | 文字色（网页版接受任意 CSS 颜色字符串） | 白 |
 * | `textColorPer` | 文字灰阶 0~100（仅用于滑块显示） | 100 |
 *
 * ⚠️ **色相滑块只有 300°**（`h = value / 100 * 300`），而 `hsvToRgb` 按标准 0~360 解释 h，
 * 所以滑块选不到 300°~360° 那段。换算时色相值必须**原样**传，不能压缩，否则整体偏色。
 *
 * ## 背景色直接取 App 的页面背景色（顶栏色）
 *
 * 需求是「网页背景与 App 顶栏一致」。App 的 `Scaffold(containerColor = AppTheme.pageBg)`
 * 决定了整页底色，顶部标题区也在这片底色上 —— 所以**顶栏色就是 `pageBg`**，
 * 取 [Palette] 的 `pageBgLight` / `pageBgDark`。
 *
 * ⚠️ 别跟底栏搞混：底栏是 `NavigationBar(containerColor = AppTheme.cardBg)`，
 * 用的是 `cardBg`，与 `pageBg` 是两个不同的色值。
 * 梦幻紫主题下实测：顶栏 `rgb(26,22,48)`（pageBgDark）、底栏 `rgb(39,32,68)`（cardBgDark）。
 *
 * ⚠️ **光写 `--dark-bg-color` 还不够**：`theme.js` 会把 `#BG_OVERLAY` 元素染成
 * `rgba(主题色, opacityPer)` 并盖在 body 之上，叠加后背景会明显偏离目标色。
 * 所以调用方还要把该元素强制置为透明（见 [com.ufitools.client.ui.screens.WebScreen]）。
 *
 * ⚠️ **`--dark-bg-color` 不在 theme.js 管辖的 8 个变量之内**（它写死在 style.css 的
 * `:root` 里），而 `body { background-color: var(--dark-bg-color) }` 正是页面底色 ——
 * 必须由我们用内联样式覆盖，否则底色恒为那串硬编码的 `#1f2937`。
 *
 * @param bodyBgCss            `--dark-bg-color` 的值（不透明实色，取自 App 的 pageBg）
 * @param bodyBgTransparentCss `--dark-bg-color-transparent`，alpha 沿用原设计 `0xae/255 ≈ 0.68`
 * @param textColorCss         `textColor`，取自 App 的 `textPrimary` 以保证与背景的对比度
 * @param textColorPer         `textColorPer`，仅供网页版滑块显示（不影响实际颜色）
 * @param hue300               `themeColor`；null = 不同步色调（强调色是纯灰、没有色调可同步）
 * @param colorPer             `colorPer`；与 [hue300] 同时为 null
 * @param saturationPer        `saturationPer`；与 [hue300] 同时为 null
 */
data class WebTheme(
    val bodyBgCss: String,
    val bodyBgTransparentCss: String,
    val textColorCss: String,
    val textColorPer: Int,
    val hue300: Int?,
    val colorPer: Int?,
    val saturationPer: Int?
)

object WebThemeMapper {

    /**
     * 饱和度低于此值就不做色调同步。
     *
     * 「默认」主题的强调色是 `#222222`（饱和度 0，纯灰），没有色调可同步；
     * 硬写进去会把网页版变成灰调，反而丢掉它默认蓝色调的色彩层次。
     * 此时**只同步背景与文字**，色调保持网页版原样。
     */
    private const val MIN_SATURATION = 0.12f

    /** 色相滑块量程上限（对应 `h = colorPer / 100 * 300`） */
    private const val HUE_SLIDER_MAX = 300f

    /** 超过此色相就认为"离红色端更近"，改取 0°（360° ≡ 0°） */
    private const val HUE_WRAP_THRESHOLD = 330f

    /** `--dark-bg-color-transparent` 的 alpha，沿用原设计 `#1f2937ae` 的 `0xae/255` */
    private const val BODY_BG_TRANSPARENT_ALPHA = 0.68f

    /**
     * 由 App 当前配色算出网页版参数。
     *
     * @param accentArgb      强调色（对应 `AppTheme.accent`）
     * @param pageBgArgb      **页面背景色**（`AppTheme.pageBg`），即顶栏那片的颜色，用作网页背景
     * @param textPrimaryArgb 主文字色（`AppTheme.textPrimary`），保证与背景的对比度
     */
    fun from(accentArgb: Long, pageBgArgb: Long, textPrimaryArgb: Long): WebTheme {
        // ── 背景与文字：直接取 App 的颜色，用途就是"与顶栏一致" ──
        val (cr, cg, cb) = split(pageBgArgb)
        val bodyBg = "rgb($cr, $cg, $cb)"
        val bodyBgTransparent = "rgba($cr, $cg, $cb, $BODY_BG_TRANSPARENT_ALPHA)"

        val (tr, tg, tb) = split(textPrimaryArgb)
        val textColor = "rgb($tr, $tg, $tb)"
        // 网页版滑块按 0~100 显示灰阶，这里按感知亮度折算，纯粹为了滑块位置好看
        val textColorPer = (luminance(tr, tg, tb) * 100).roundToInt().coerceIn(0, 100)

        // ── 色调（可选）：从强调色取色相与饱和度，让按钮/标题跟随 App 主题 ──
        val (ar, ag, ab) = split(accentArgb)
        val (hue360, sat, _) = rgbToHsv(ar, ag, ab)
        if (sat < MIN_SATURATION) {
            return WebTheme(
                bodyBgCss = bodyBg,
                bodyBgTransparentCss = bodyBgTransparent,
                textColorCss = textColor,
                textColorPer = textColorPer,
                hue300 = null, colorPer = null, saturationPer = null
            )
        }

        // 色相保持原值，只处理滑块量程边界（见 KDoc 里 300° 的说明）
        val hueOnSlider = when {
            hue360 <= HUE_SLIDER_MAX -> hue360
            hue360 < HUE_WRAP_THRESHOLD -> HUE_SLIDER_MAX
            else -> 0f
        }

        return WebTheme(
            bodyBgCss = bodyBg,
            bodyBgTransparentCss = bodyBgTransparent,
            textColorCss = textColor,
            textColorPer = textColorPer,
            hue300 = hueOnSlider.toInt().coerceIn(0, 300),
            colorPer = (hueOnSlider / HUE_SLIDER_MAX * 100f).toInt().coerceIn(0, 100),
            saturationPer = (sat * 100f).toInt().coerceIn(0, 100)
        )
    }

    private fun split(argb: Long): Triple<Int, Int, Int> = Triple(
        ((argb shr 16) and 0xFF).toInt(),
        ((argb shr 8) and 0xFF).toInt(),
        (argb and 0xFF).toInt()
    )

    /** 感知亮度（0~1），用于决定网页版文字滑块的位置 */
    private fun luminance(r: Int, g: Int, b: Int): Float =
        (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f

    /**
     * RGB → HSV，自己实现而不依赖 `android.graphics.Color`：
     * 本类在 `data` 包、可能被纯 JVM 单测覆盖，不该引入 Android 框架依赖。
     *
     * @return (色相 0~360, 饱和度 0~1, 明度 0~1)
     */
    private fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min

        val hue = when {
            delta == 0f -> 0f
            max == rf -> 60f * (((gf - bf) / delta) % 6f)
            max == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val sat = if (max == 0f) 0f else delta / max
        return Triple(hue, sat, max)
    }
}
