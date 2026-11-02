package com.ff.compress

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val state by CompressionBus.state.collectAsState()
    val res by CompressionBus.res.collectAsState()

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var engine by remember { mutableStateOf(Engine.HARDWARE) }
    var height by remember { mutableStateOf(0) }
    var bitrate by remember { mutableStateOf(0f) }
    var threads by remember { mutableStateOf(4) }
    var preset by remember { mutableStateOf("medium") }

    // 通知权限（Android 13+）
    val notiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notiLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 任务结束后停止前台服务
    LaunchedEffect(state.running) {
        if (!state.running && state.progress >= 1f) {
            context.startService(
                android.content.Intent(context, CompressionService::class.java)
                    .setAction(CompressionService.ACTION_STOP_UI)
            )
        }
    }

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        pickedUri = uri
        pickedName = uri?.let { queryName(context, it) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("FFCompress 视频压缩") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 选视频 ----
            Button(onClick = { pickVideo.launch("video/*") }, modifier = Modifier.fillMaxWidth()) {
                Text(if (pickedUri == null) "选择视频" else "已选：${pickedName ?: "视频"}")
            }

            // ---- 引擎 ----
            Text("压缩引擎", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Engine.entries.forEach { e ->
                    FilterChip(
                        selected = engine == e,
                        onClick = { engine = e },
                        label = { Text(e.label) },
                    )
                }
            }
            if (engine == Engine.HARDWARE) {
                Text("硬件编码：低 CPU、省电、速度快", color = Color(0xFF1E7B34), fontSize = 13.sp)
            } else {
                Text("libx264 软编：压缩率最优；CPU 占用由下方线程数档位控制", color = Color(0xFFB3660F), fontSize = 13.sp)
            }
            if (engine == Engine.SOFTWARE) {
                Text("编码速度档位（preset）", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("veryfast" to "极快", "medium" to "均衡", "slow" to "高压缩").forEach { (p, label) ->
                        FilterChip(
                            selected = preset == p,
                            onClick = { preset = p },
                            label = { Text(label) },
                        )
                    }
                }
            }

            // ---- 分辨率 ----
            Text("目标分辨率", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "原分辨率", 1080 to "1080P", 720 to "720P", 480 to "480P").forEach { (h, label) ->
                    FilterChip(selected = height == h, onClick = { height = h }, label = { Text(label) })
                }
            }

            // ---- 码率 ----
            Text("码率（0=自动）", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0f to "自动", 4f to "4 Mbps", 2f to "2 Mbps", 1f to "1 Mbps").forEach { (b, label) ->
                    FilterChip(selected = bitrate == b, onClick = { bitrate = b }, label = { Text(label) })
                }
            }

            // ---- 资源档位（软编生效）----
            Text("CPU 档位（FFmpeg 软编生效：线程数）", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = threads.toFloat(),
                    onValueChange = { threads = it.toInt() },
                    valueRange = 1f..8f,
                    steps = 6,
                    modifier = Modifier.weight(1f),
                )
                Text("${threads} 线程", modifier = Modifier.padding(start = 8.dp))
            }

            // ---- 操作按钮 ----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val uri = pickedUri ?: return@Button
                        val opts = CompressOptions(engine, height, bitrate, threads, preset)
                        CompressionService.start(context, uri, opts)
                    },
                    enabled = pickedUri != null && !state.running,
                    modifier = Modifier.weight(1f),
                ) { Text("开始压缩") }
                OutlinedButton(
                    onClick = { CompressionService.cancel(context) },
                    enabled = state.running,
                ) { Text("取消") }
            }

            // ---- 进度 ----
            if (state.running || state.progress > 0f) {
                Column {
                    Text(state.stage)
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    state.outputPath?.let {
                        Text("输出：$it", fontSize = 12.sp, color = Color.Gray)
                    }
                    state.error?.let {
                        Text("错误：$it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }

            // ---- 资源监控 ----
            Text("实时资源监控", fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("本机 CPU：${"%.1f".format(res.systemCpuPercent)} %")
                    Text("压缩进程 CPU：${"%.1f".format(res.appCpuPercent)} %")
                    Text("App 内存：${"%.0f".format(res.appMemMB)} MB")
                    Text("系统可用内存：${"%.0f".format(res.availMemMB)} / ${"%.0f".format(res.totalMemMB)} MB")
                    Text("磁盘写入：${"%.2f".format(res.ioWriteMBs)} MB/s")
                }
            }
        }
    }
}

private fun queryName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        }
    } catch (e: Exception) { null }
}
