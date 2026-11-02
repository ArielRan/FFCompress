package com.ff.compress

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

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

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.2f GB".format(bytes / 1e9)
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1e3)
    else -> "$bytes B"
}

private fun fmtDuration(ms: Long): String {
    val s = TimeUnit.MILLISECONDS.toSeconds(ms)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun fmtRatio(src: Long, out: Long): String =
    if (src <= 0) "" else {
        val saved = 1 - out.toDouble() / src
        if (saved > 0) "-${(saved * 100).toInt()}%" else "+${(-saved * 100).toInt()}%"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).taskDao() }
    val tasks by dao.observeAll().collectAsState(initial = emptyList())
    val runtime by TaskRuntime.current.collectAsState()
    val res by CompressionBus.res.collectAsState()

    var editingTask by remember { mutableStateOf<TaskEntity?>(null) }

    val pending = tasks.filter { it.status == TaskStatus.PENDING }
    val runningTask = tasks.firstOrNull { it.status == TaskStatus.RUNNING }
    val doneList = tasks.filter { it.status == TaskStatus.DONE }
    val otherList = tasks.filter { it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED }
    val totalSaved = doneList.sumOf { it.sourceSize - it.outputSize }

    // ---- 权限 ----
    val notiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notiLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---- 批量选视频 ----
    val pickVideos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            uris.forEach { uri ->
                val meta = MetadataReader.read(context, uri)
                val name = queryName(context, uri) ?: "视频"
                val rec = meta?.let { Recommender.recommend(it) }
                dao.insert(
                    TaskEntity(
                        uri = uri.toString(),
                        displayName = name,
                        sourceSize = querySize(context, uri),
                        durationMs = meta?.durationMs ?: 0,
                        width = meta?.width ?: 0,
                        height = meta?.height ?: 0,
                        sourceBitrate = meta?.bitrateBps ?: 0,
                        engine = (rec?.engine ?: Engine.HARDWARE).name,
                        targetHeight = rec?.targetHeight ?: 0,
                        targetBitrateMbps = rec?.targetBitrateMbps ?: 0f,
                        threadLimit = rec?.threadLimit ?: 4,
                        preset = rec?.preset ?: "medium",
                    )
                )
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("FFCompress 视频压缩") }) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(
                        onClick = {
                            pickVideos.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (pending.isEmpty()) "选择视频" else "继续添加 (${pending.size})") }
                    Button(
                        onClick = { CompressionService.start(context) },
                        enabled = pending.isNotEmpty() && runtime == null,
                    ) { Text("开始压缩") }
                }
            }

            // ---- 当前任务 ----
            runningTask?.let { t ->
                item {
                    val rt = runtime
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(t.displayName, fontWeight = FontWeight.Bold)
                            LinearProgressIndicator(progress = { rt?.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${((rt?.progress ?: 0f) * 100).toInt()}%", fontSize = 13.sp)
                                val eta = rt?.etaSec ?: -1
                                Text(
                                    if (eta < 0) "剩余时长估算中…" else "剩约 ${fmtEta(eta)}",
                                    fontSize = 13.sp,
                                )
                                val ratio = rt?.estRatio ?: 0.7f
                                Text("预计体积 ${fmtRatio(t.sourceSize, (t.sourceSize * ratio).toLong())}", fontSize = 13.sp)
                            }
                            OutlinedButton(onClick = { CompressionService.cancelCurrent(context) }) { Text("取消当前") }
                        }
                    }
                }
            }

            // ---- 待压缩队列 ----
            if (pending.isNotEmpty()) {
                item { Text("待压缩 (${pending.size})", fontWeight = FontWeight.Bold) }
                items(pending, key = { it.id }) { t ->
                    TaskCard(t) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editingTask = t }) { Text("参数") }
                            TextButton(onClick = { scope.launch { dao.delete(t) } }) { Text("移除") }
                        }
                    }
                }
            }

            // ---- 产出列表 ----
            if (doneList.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("产出 (${doneList.size})，共节省 ${fmtSize(totalSaved)}", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { scope.launch { dao.clearFinished() } }) { Text("清空") }
                    }
                }
                items(doneList, key = { it.id }) { t ->
                    TaskCard(t) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = { share(context, t) },
                                label = { Text("分享") },
                            )
                            AssistChip(
                                onClick = { scope.launch { dao.delete(t) } },
                                label = { Text("删除") },
                            )
                        }
                    }
                }
            }

            // ---- 失败/取消 ----
            if (otherList.isNotEmpty()) {
                item { Text("失败 / 已取消", fontWeight = FontWeight.Bold) }
                items(otherList, key = { it.id }) { t ->
                    TaskCard(t) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (t.status == TaskStatus.FAILED) {
                                TextButton(onClick = { scope.launch { dao.reset(t.id) } }) { Text("重试") }
                            }
                            TextButton(onClick = { scope.launch { dao.delete(t) } }) { Text("移除") }
                        }
                    }
                }
            }

            // ---- 资源监控 ----
            item {
                Text("实时资源监控", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("本机 CPU：${"%.1f".format(res.systemCpuPercent)} %")
                        Text("压缩进程 CPU：${"%.1f".format(res.appCpuPercent)} %")
                        Text("App 内存：${"%.0f".format(res.appMemMB)} MB")
                        Text("系统可用内存：${"%.0f".format(res.availMemMB)} / ${"%.0f".format(res.totalMemMB)} MB")
                        Text("磁盘写入：${"%.2f".format(res.ioWriteMBs)} MB/s")
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // ---- 参数编辑弹窗 ----
    editingTask?.let { t ->
        ParamEditDialog(
            task = t,
            onDismiss = { editingTask = null },
            onSave = { updated ->
                scope.launch {
                    dao.update(updated)
                    editingTask = null
                }
            },
        )
    }
}

private fun fmtEta(sec: Long): String = "%d 分 %02d 秒".format(sec / 60, sec % 60)

@Composable
private fun TaskCard(t: TaskEntity, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t.displayName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                if (t.status == TaskStatus.DONE && t.sourceSize > 0) {
                    Text(fmtRatio(t.sourceSize, t.outputSize), color = Color(0xFF1E7B34), fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "${fmtSize(t.sourceSize)} · ${t.width}×${t.height} · ${fmtDuration(t.durationMs)} · " +
                    "${"%.1f".format(t.sourceBitrate / 1e6)} Mbps",
                fontSize = 12.sp, color = Color.Gray,
            )
            t.error?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
        }
    }
}

@Composable
private fun ParamEditDialog(task: TaskEntity, onDismiss: () -> Unit, onSave: (TaskEntity) -> Unit) {
    var engine by remember { mutableStateOf(task.engine) }
    var height by remember { mutableStateOf(task.targetHeight) }
    var bitrate by remember { mutableStateOf(task.targetBitrateMbps) }
    var threads by remember { mutableStateOf(task.threadLimit) }
    var preset by remember { mutableStateOf(task.preset) }

    // 推荐解释：始终按源视频信息重新计算，让用户知道"系统为什么这么推荐"
    val explanation = remember(task.id) {
        val meta = MetadataReader.VideoMeta(task.durationMs, task.width, task.height, task.sourceBitrate)
        if (task.durationMs > 0) Recommender.recommend(meta).explanation
        else "无法读取该视频信息（可能是不支持的格式），请手动设置参数。"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("压缩参数", fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(explanation, fontSize = 12.sp, color = Color(0xFF555555))
                HorizontalDivider()
                Text("压缩引擎", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Engine.entries.forEach { e ->
                        FilterChip(selected = engine == e.name, onClick = { engine = e.name }, label = { Text(e.label) })
                    }
                }
                Text("目标分辨率", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "原分辨率", 1080 to "1080P", 720 to "720P", 480 to "480P").forEach { (h, label) ->
                        FilterChip(selected = height == h, onClick = { height = h }, label = { Text(label) })
                    }
                }
                Text("码率（0=自动 CRF）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0f to "自动", 4f to "4M", 2f to "2M", 1f to "1M").forEach { (b, label) ->
                        FilterChip(selected = bitrate == b, onClick = { bitrate = b }, label = { Text(label) })
                    }
                }
                if (engine == Engine.SOFTWARE.name) {
                    Text("CPU 档位（线程数）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = threads.toFloat(),
                            onValueChange = { threads = it.toInt().coerceIn(1, 8) },
                            valueRange = 1f..8f,
                            steps = 6,
                            modifier = Modifier.weight(1f),
                        )
                        Text("$threads", modifier = Modifier.padding(start = 8.dp))
                    }
                    Text("编码速度（preset）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("veryfast" to "极快", "medium" to "均衡", "slow" to "高压缩").forEach { (p, label) ->
                            FilterChip(selected = preset == p, onClick = { preset = p }, label = { Text(label) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    task.copy(
                        engine = engine, targetHeight = height, targetBitrateMbps = bitrate,
                        threadLimit = threads, preset = preset,
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun share(context: android.content.Context, t: TaskEntity) {
    val path = t.outputPath ?: return
    val file = File(path)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "com.ff.compress.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享压缩后的视频"))
}

private fun queryName(context: android.content.Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
    }
} catch (e: Exception) { null }

private fun querySize(context: android.content.Context, uri: Uri): Long = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (c.moveToFirst() && idx >= 0) c.getLong(idx) else 0
    } ?: 0
} catch (e: Exception) { 0 }
