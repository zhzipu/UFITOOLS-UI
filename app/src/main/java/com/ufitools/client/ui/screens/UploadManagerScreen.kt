package com.ufitools.client.ui.screens

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufitools.client.model.UploadedFile
import com.ufitools.client.model.bytesToHumanOrZero
import com.ufitools.client.ui.components.AppCard
import com.ufitools.client.ui.components.EmptyHint
import com.ufitools.client.ui.components.ErrorBanner
import com.ufitools.client.ui.components.OptionChip
import com.ufitools.client.ui.components.SectionTitle
import com.ufitools.client.ui.components.SubPageScaffold
import com.ufitools.client.ui.theme.AppTheme
import com.ufitools.client.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件 / 图片上传管理（API 文档 §11）。
 *
 * 两条上传通道：
 * - **图片**（`/api/upload_img`）：≤10MB，上传后落在 `/uploads/<name>`，
 *   免鉴权可直接访问，适合放灯箱图、快捷入口图标；
 * - **任意文件**（`/api/upload_file`）：≤500MB。
 *
 * 用系统文档选择器（SAF）拿 URI，再读成字节做 multipart——
 * 这样不依赖 `READ_EXTERNAL_STORAGE` 权限，Android 13+ 也不用申请媒体权限。
 */
@Composable
fun UploadManagerScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loading = "uploads" in vm.pageLoading
    val error = vm.pageError["uploads"]

    var uploading by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<UploadedFile?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshUploads() }

    fun toast(msg: String) {
        Toast.makeText(context, if (msg == "success") "完成" else msg, Toast.LENGTH_SHORT).show()
    }

    /** 把 SAF 返回的 uri 落到缓存目录，再走 multipart。用缓存文件是因为要拿到真实文件名与长度。 */
    fun uploadFromUri(uri: android.net.Uri?, imageOnly: Boolean) {
        if (uri == null) return
        uploading = true
        scope.launch {
            val msg = try {
                val f = withContext(Dispatchers.IO) {
                    val name = queryDisplayName(context, uri) ?: "upload_${System.currentTimeMillis()}"
                    val dst = File(context.cacheDir, name)
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "无法读取所选文件" }
                        dst.outputStream().use { out -> input.copyTo(out) }
                    }
                    dst
                }
                vm.uploadFile(f, imageOnly)
            } catch (e: Exception) {
                e.message ?: "上传失败"
            }
            uploading = false
            toast(msg)
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uploadFromUri(uri, imageOnly = true)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uploadFromUri(uri, imageOnly = false)
    }

    SubPageScaffold(
        title = "文件管理",
        onBack = { nav.popBackStack() },
        loading = loading || uploading,
        onRefresh = { scope.launch { vm.refreshUploads() } }
    ) {
        ErrorBanner(error)

        AppCard {
            SectionTitle("上传")
            Spacer(Modifier.height(8.dp))
            Row {
                OptionChip("上传图片", false, enabled = !uploading) {
                    pickImage.launch("image/*")
                }
                OptionChip("上传文件", false, enabled = !uploading) {
                    pickFile.launch("*/*")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "图片最大 10MB，其它文件最大 500MB。图片上传后可直接通过直链访问。",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.textSecondary
            )
        }

        AppCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("已上传（${vm.uploads.size}）")
                if (vm.uploads.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }, enabled = !loading) {
                        Text("清空", color = AppTheme.textSecondary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (vm.uploads.isEmpty()) {
                EmptyHint("上传目录为空")
            } else {
                vm.uploads.forEach { f ->
                    UploadRow(
                        file = f,
                        vm = vm,
                        onDelete = { pendingDelete = f }
                    )
                }
            }
        }
    }

    pendingDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除文件") },
            text = { Text("确定删除「${f.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { toast(vm.deleteUpload(f.name)) }
                }) { Text("删除", color = AppTheme.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = AppTheme.textSecondary)
                }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空上传目录") },
            text = { Text("将删除全部已上传文件，不可撤销。确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { toast(vm.deleteAllUploads()) }
                }) { Text("清空", color = AppTheme.accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("取消", color = AppTheme.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun UploadRow(file: UploadedFile, vm: MainViewModel, onDelete: () -> Unit) {
    var thumb by remember(file.name) { mutableStateOf<android.graphics.Bitmap?>(null) }

    // 图片才去拉缩略图，其它类型直接跳过，避免多余请求
    LaunchedEffect(file.name, file.isImage) {
        if (!file.isImage) return@LaunchedEffect
        val bytes = vm.fetchUploadBytes(file.url) ?: return@LaunchedEffect
        thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppTheme.textPrimary.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                val bmp = thumb
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = file.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        file.name.substringAfterLast('.', "").uppercase().take(4).ifBlank { "?" },
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.textSecondary
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.textPrimary,
                    maxLines = 1
                )
                Text(
                    "${bytesToHumanOrZero(file.size.toDouble())} · ${formatTime(file.mtime)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.textSecondary
                )
            }
            TextButton(onClick = onDelete) { Text("删除", color = AppTheme.textSecondary) }
        }
    }
}

/** 从 content:// URI 里取出用户可见的文件名 */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
} catch (_: Exception) {
    null
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "-"
    // 设备给的 mtime 有时是秒级，小于 1e12 时按秒处理
    val real = if (ms < 1_000_000_000_000L) ms * 1000 else ms
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(real))
}
