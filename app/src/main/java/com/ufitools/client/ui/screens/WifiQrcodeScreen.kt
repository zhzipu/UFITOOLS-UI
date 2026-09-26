package com.ufitools.client.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.SubPageScaffold
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * WiFi 分享二维码（API 文档 §10）。
 *
 * 设备直接返回 PNG（`/api/wifi/qrcode?chip=0|1`），项目没有图片加载库，
 * 这里用 BitmapFactory 解码后交给 Compose 的 Image 渲染。
 * 二维码本体是黑白的，放在白底卡片上扫描识别率最好，所以没有跟随主题反色。
 */
@Composable
fun WifiQrcodeScreen(vm: MainViewModel, nav: NavHostController) {
    val scope = rememberCoroutineScope()
    var chip by remember { mutableStateOf(0) }

    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun load(c: Int) {
        loading = true
        failed = false
        scope.launch {
            val bytes = vm.wifiQrcode(c)
            bitmap = bytes?.takeIf { it.isNotEmpty() }?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }
            failed = bitmap == null
            loading = false
        }
    }

    LaunchedEffect(chip) { load(chip) }

    SubPageScaffold(
        title = "WiFi 二维码",
        onBack = { nav.popBackStack() },
        loading = loading,
        onRefresh = { load(chip) }
    ) {
        AppCard {
            SectionTitle("频段")
            Spacer(Modifier.height(8.dp))
            Row {
                OptionChip("2.4G", chip == 0, enabled = !loading) { chip = 0 }
                OptionChip("5G", chip == 1, enabled = !loading) { chip = 1 }
            }
        }

        AppCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.ui.graphics.Color.White),
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap
                when {
                    loading && bmp == null -> CircularProgressIndicator(
                        color = AppTheme.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp)
                    )

                    bmp != null -> Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "WiFi 二维码",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    )

                    else -> EmptyHint(
                        if (failed) "二维码获取失败，设备可能未开启该频段 WiFi" else "暂无二维码"
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "用手机相机扫描即可连接 WiFi。",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.textSecondary
            )
        }

        AppCard {
            SectionTitle("说明")
            Spacer(Modifier.height(6.dp))
            Text(
                "二维码由设备端实时生成，包含当前 SSID 与密码。\n" +
                    "若某个频段的 WiFi 处于关闭状态，该频段可能取不到二维码。",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.textSecondary
            )
        }
    }
}
