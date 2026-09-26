package com.ufitools.client.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufitools.client.R
import com.ufitools.client.model.carrierCode

/**
 * 运营商标识。
 *
 * 四家（移动/联通/电信/广电）用的是**官方 logo 素材**处理成的透明底 PNG
 * （原图 900×900 带白底 → 按 alpha 裁到内容边界 → 按 14dp 出齐五档密度）。
 * **刻意不做 tint**：品牌色本身就是识别信息，一旦套上主题强调色，四家全变成同一个色，
 * 反而分不出是哪一家了。
 *
 * 未收录的运营商（境外卡等）退回中性灰的矢量兜底图，不会误标成四大运营商中的某一家。
 * 处理脚本：`.workbuddy/tmp/carrier_logo_gen.py`（换素材时重跑即可）。
 */
@Composable
fun CarrierLogo(
    provider: String?,
    fullname: String? = null,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(carrierLogoRes(provider, fullname)),
        contentDescription = null,
        modifier = modifier.size(size)
    )
}

/** 依据运营商代号挑矢量标识资源；未收录走兜底 */
fun carrierLogoRes(provider: String?, fullname: String? = null): Int =
    carrierLogoResOf(carrierCode(provider, fullname))

/**
 * 运营商**代号** → 图标资源。
 *
 * 供桌面小组件使用：那边只有 [com.ufitools.client.widget.WidgetSnapshot] 里的代号字符串
 * （跨进程、无原始 `network_provider`），所以映射表必须收敛到这一处，
 * 否则 App 与小组件很容易各写一份、慢慢跑偏。
 */
fun carrierLogoResOf(code: String?): Int =
    when (code) {
        "CMCC" -> R.drawable.ic_carrier_cmcc
        "CUCC" -> R.drawable.ic_carrier_cucc
        "CTCC" -> R.drawable.ic_carrier_ctcc
        "CBN" -> R.drawable.ic_carrier_cbn
        else -> R.drawable.ic_carrier_generic
    }
