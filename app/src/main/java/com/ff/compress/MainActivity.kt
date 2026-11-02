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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

private fun fmtEta(sec: Long): String = "%d 分 %02d 秒".format(sec / 60, sec % 60)

private fun presetLabel(p: String) = when (p) {
    "veryfast" -> "极快"
    "slow" -> "高压缩"
    else -> "均衡"
}

/** 任务使用的主要压缩参数摘要 */
private fun paramSummary(t: TaskEntity): String {
    val eng = if (t.engine == Engine.SOFTWARE.name) "FFmpeg 软编" else "硬件编码"
    val res = if (t.targetHeight == 0) "原分辨率" else "${t.targetHeight}P"
    val br = if (t.targetBitrateMbps == 0f) "自动CRF" else "${t.targetBitrateMbps.toInt()}M"
    return if (t.engine == Engine.SOFTWARE.name) {
        "$eng · $res · $br · ${t.threadLimit}线程 · ${presetLabel(t.preset)}"
    } else "$eng · $res · $br"
}

private enum class Page(val label: String) { TASKS("任务"), DONE("已完成") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).taskDao() }
    val tasks by dao.observeAll().collectAsState(initial = emptyList())
    val runtime by TaskRuntime.current.collectAsState()
    val res by CompressionBus.res.collectAsState()

    var page by remember { mutableStateOf(Page.TASKS) }
    var editingTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    val pending = tasks.filter { it.status == TaskStatus.PENDING }
    val runningTask = tasks.firstOrNull { it.status == TaskStatus.RUNNING }
    val failed = tasks.filter { it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED }
    val doneList = tasks.filter { it.status == TaskStatus.DONE }
    val totalSaved = doneList.sumOf { it.sourceSize - it.outputSize }
    val isRunning = runtime != null && runningTask != null

    // ---- 资源监控：软件在前台期间持续采样（不只在压缩时） ----
    DisposableEffect(Unit) {
        ResourceMonitor.start(context)
        onDispose {
            // 若后台压缩服务仍在跑，保留监控；否则停止采样省电
            if (TaskRuntime.current.value == null) ResourceMonitor.stop()
        }
    }

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
            var skipCount = 0
            var skipName = ""
            uris.forEach { uri ->
                val meta = MetadataReader.read(context, uri)
                val name = queryName(context, uri) ?: "视频"
                val size = querySize(context, uri)
                val rec = meta?.let { Recommender.recommend(it) }
                // 码率缺失时用「体积/时长」推算，避免推荐引擎拿到 0 后凭空猜参数
                val srcBps = when {
                    meta == null -> 0L
                    meta.bitrateBps > 0 -> meta.bitrateBps
                    size > 0 && meta.durationMs > 0 -> size * 8000 / meta.durationMs
                    else -> 0L
                }
                dao.insert(
                    TaskEntity(
                        uri = uri.toString(),
                        displayName = name,
                        sourceSize = size,
                        durationMs = meta?.durationMs ?: 0,
                        width = meta?.width ?: 0,
                        height = meta?.height ?: 0,
                        sourceBitrate = srcBps,
                        engine = (rec?.engine ?: Engine.HARDWARE).name,
                        targetHeight = rec?.targetHeight ?: 0,
                        targetBitrateMbps = rec?.targetBitrateMbps ?: 0f,
                        threadLimit = rec?.threadLimit ?: 4,
                        preset = rec?.preset ?: "medium",
                    )
                )
                if (rec?.skip == true) {
                    skipCount++
                    if (skipName.isEmpty()) skipName = name
                }
            }
            if (skipCount > 0) {
                android.widget.Toast.makeText(
                    context,
                    "已添加 ${uris.size} 个视频；其中 $skipCount 个（如 $skipName）码率已经很低，" +
                        "再压体积只会小一点点、画质却会下降，可在参数里看到详细说明",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FFCompress 视频压缩") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                listOf(Page.TASKS to pending.size + failed.size, Page.DONE to doneList.size).forEach { (p, n) ->
                    NavigationBarItem(
                        selected = page == p,
                        onClick = { page = p },
                        icon = { if (p == Page.TASKS) Icon(Icons.Filled.PlayArrow, null) else Icon(Icons.Filled.Check, null) },
                        label = { Text(if (n > 0) "${p.label} ($n)" else p.label) },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // ===== 顶部固定：资源监控（可折叠，软件内常驻） =====
            ResourceBar(res, compactRunning = isRunning)

            // ===== 页面内容 =====
            when (page) {
                Page.TASKS -> TasksPage(
                    pending, failed, runningTask, runtime,
                    onPick = {
                        pickVideos.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    onStart = { CompressionService.start(context) },
                    onCancel = { CompressionService.cancelCurrent(context) },
                    onEdit = { editingTask = it },
                    onRemove = { scope.launch { dao.delete(it) } },
                    onRetry = { scope.launch { dao.reset(it.id) } },
                )
                Page.DONE -> DonePage(
                    doneList, totalSaved,
                    onPlay = { play(context, it) },
                    onShare = { share(context, it) },
                    onDelete = { scope.launch { dao.delete(it) } },
                    onClear = { scope.launch { dao.clearFinished() } },
                )
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

    // ---- 设置（全局存储位置） ----
    if (showSettings) {
        StorageSettingsDialog(onDismiss = { showSettings = false })
    }
}

// ================= 设置：全局存储位置 =================

@Composable
private fun StorageSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(StoragePrefs.mode(context)) }
    var subfolder by remember { mutableStateOf(StoragePrefs.subfolder(context)) }
    var treeUri by remember { mutableStateOf(StoragePrefs.treeUri(context)) }
    val currentDesc = when (mode) {
        StorageMode.MEDIA -> "内部存储/Movies/${subfolder.ifBlank { "FFCompress" }}（相册可见）"
        StorageMode.PRIVATE -> "Android/data/com.ff.compress/files/Movies（仅本 App，卸载会删除）"
        StorageMode.TREE -> {
            val name = treeUri?.let {
                runCatching {
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, Uri.parse(it))?.name
                }.getOrNull()
            }
            if (treeUri == null) "尚未选择文件夹" else "自定义文件夹：${name ?: treeUri}"
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            OutputStore.takePersistable(context, uri)
            treeUri = uri.toString()
            mode = StorageMode.TREE
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置", fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("压缩后的视频保存到", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                StorageMode.entries.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == m, onClick = { mode = m })
                        Text(m.label, fontSize = 14.sp)
                    }
                }
                if (mode == StorageMode.MEDIA) {
                    OutlinedTextField(
                        value = subfolder,
                        onValueChange = { subfolder = it },
                        label = { Text("子文件夹名（Movies/ 下）", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (mode == StorageMode.TREE) {
                    OutlinedButton(onClick = { treePicker.launch(null) }) { Text("选择文件夹…") }
                }
                HorizontalDivider()
                Text("当前位置：$currentDesc", fontSize = 12.sp, color = Color(0xFF3D5A80))
                Text(
                    "说明：公共视频目录和自定义文件夹的文件在系统相册/文件管理器里可见，卸载 App 不会删除；私有目录随 App 卸载一起清除。",
                    fontSize = 11.sp, color = Color.Gray,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                StoragePrefs.setMode(context, mode)
                StoragePrefs.setSubfolder(context, subfolder)
                StoragePrefs.setTreeUri(context, treeUri)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ================= 顶部固定资源监控条 =================

@Composable
private fun ResourceBar(res: ResourceStats, compactRunning: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val memUsedPct =
        if (res.totalMemMB > 0) ((res.totalMemMB - res.availMemMB) / res.totalMemMB * 100).toInt() else 0
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val sysCpuText =
                    if (res.systemCpuPercent < 0) "CPU —" else "CPU ${res.systemCpuPercent.toInt()}%"
                Text(
                    "$sysCpuText  App ${res.appCpuPercent.toInt()}%  Mem $memUsedPct%" +
                        if (compactRunning) "  压缩中" else "",
                    fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                Text("磁盘 ${"%.1f".format(res.ioWriteMBs)} MB/s", fontSize = 11.sp, color = Color.Gray)
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null, tint = Color.Gray,
                    modifier = Modifier.padding(start = 4.dp).size(18.dp),
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    HorizontalDivider()
                    Text(
                        if (res.systemCpuPercent < 0) "本机 CPU：不可用（Android 9+ 系统限制，App 无法读取 /proc/stat）"
                        else "本机 CPU：${"%.1f".format(res.systemCpuPercent)} %",
                        fontSize = 12.sp,
                    )
                    Text("本 App CPU（含压缩线程）：${"%.1f".format(res.appCpuPercent)} %", fontSize = 12.sp)
                    Text("App 内存：${"%.0f".format(res.appMemMB)} MB", fontSize = 12.sp)
                    Text("系统内存：已用 $memUsedPct%（可用 ${"%.0f".format(res.availMemMB)} / ${"%.0f".format(res.totalMemMB)} MB）", fontSize = 12.sp)
                    Text("磁盘写入：${"%.2f".format(res.ioWriteMBs)} MB/s", fontSize = 12.sp)
                }
            }
        }
    }
}

// ================= Tab 1：任务列表 =================

@Composable
private fun TasksPage(
    pending: List<TaskEntity>,
    failed: List<TaskEntity>,
    runningTask: TaskEntity?,
    runtime: RuntimeInfo?,
    onPick: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onEdit: (TaskEntity) -> Unit,
    onRemove: (TaskEntity) -> Unit,
    onRetry: (TaskEntity) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPick, modifier = Modifier.weight(1f)) {
                    Text(if (pending.isEmpty()) "选择视频" else "继续添加 (${pending.size})")
                }
                Button(onClick = onStart, enabled = pending.isNotEmpty() && runningTask == null) {
                    Text(if (runningTask != null) "压缩中…" else "开始压缩")
                }
            }
        }

        if (pending.isEmpty() && failed.isEmpty() && runningTask == null) {
            item {
                Text(
                    "暂无任务，点上方「选择视频」批量添加",
                    color = Color.Gray, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }

        // ---- 运行中任务：独立进度条 ----
        runningTask?.let { t ->
            val rt = runtime
            item(key = "running") {
                val animProgress by animateFloatAsState(
                    targetValue = rt?.progress ?: 0f,
                    animationSpec = tween(durationMillis = 300),
                    label = "taskProgress",
                )
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t.displayName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("压缩中", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        LinearProgressIndicator(
                            progress = { animProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${((rt?.progress ?: 0f) * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            val eta = rt?.etaSec ?: -1
                            Text(
                                if (eta < 0) "剩余时长估算中…" else "剩约 ${fmtEta(eta)}",
                                fontSize = 12.sp, color = Color.Gray,
                            )
                            val ratio = rt?.estRatio ?: 0.7f
                            Text("预计体积 ${fmtRatio(t.sourceSize, (t.sourceSize * ratio).toLong())}", fontSize = 12.sp, color = Color.Gray)
                        }
                        Text("参数：${paramSummary(t)}", fontSize = 12.sp, color = Color(0xFF3D5A80))
                        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消当前任务") }
                    }
                }
            }
        }

        // ---- 排队中 ----
        if (pending.isNotEmpty()) {
            item { Text("排队中 (${pending.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            items(pending, key = { it.id }) { t ->
                TaskCard(t) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onEdit(t) }) { Text("参数") }
                        TextButton(onClick = { onRemove(t) }) { Text("移除") }
                    }
                }
            }
        }

        if (failed.isNotEmpty()) {
            item { Text("失败 / 已取消", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            items(failed, key = { it.id }) { t ->
                TaskCard(t) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (t.status == TaskStatus.FAILED) {
                            TextButton(onClick = { onRetry(t) }) { Text("重试") }
                        }
                        TextButton(onClick = { onRemove(t) }) { Text("移除") }
                    }
                }
            }
        }
    }
}

// ================= Tab 2：已完成列表 =================

@Composable
private fun DonePage(
    doneList: List<TaskEntity>,
    totalSaved: Long,
    onPlay: (TaskEntity) -> Unit,
    onShare: (TaskEntity) -> Unit,
    onDelete: (TaskEntity) -> Unit,
    onClear: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        if (doneList.isEmpty()) {
            item {
                Text(
                    "还没有已完成的压缩任务",
                    color = Color.Gray, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("共 ${doneList.size} 个 · 节省 ${fmtSize(totalSaved)} · 点卡片播放", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    TextButton(onClick = onClear) { Text("清空记录") }
                }
            }
            items(doneList, key = { it.id }) { t ->
                TaskCard(t, onClick = { onPlay(t) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { onPlay(t) }, label = { Text("▶ 播放") })
                        AssistChip(onClick = { onShare(t) }, label = { Text("分享") })
                        AssistChip(onClick = { onDelete(t) }, label = { Text("删除") })
                    }
                }
            }
        }
    }
}

// ================= 任务卡片（含源大小 + 参数摘要） =================

@Composable
private fun TaskCard(t: TaskEntity, onClick: (() -> Unit)? = null, actions: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t.displayName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.status == TaskStatus.DONE && t.sourceSize > 0) {
                    val savedRatio = 1 - t.outputSize.toDouble() / t.sourceSize
                    Text(
                        fmtRatio(t.sourceSize, t.outputSize),
                        color = if (savedRatio < 0.05) Color(0xFFB26A00) else Color(0xFF1E7B34),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // 源视频信息
            Text(
                "源：${fmtSize(t.sourceSize)} · ${t.width}×${t.height} · ${fmtDuration(t.durationMs)} · " +
                    "${"%.2f".format(t.sourceBitrate / 1e6)} Mbps",
                fontSize = 12.sp, color = Color.Gray,
            )
            // 主要压缩参数
            Text("参数：${paramSummary(t)}", fontSize = 12.sp, color = Color(0xFF3D5A80))
            // 收益提示：源码率已很低时提前说清楚，避免"压完没变化"的困惑
            if (t.status == TaskStatus.PENDING) {
                val est = Recommender.estimateRatio(t)
                if (est > 0.9f) {
                    Text(
                        "提示：该视频码率已接近 ${t.height}P 的画质下限，预计只能缩小 " +
                            "${((1 - est) * 100).toInt().coerceAtLeast(1)}%，画质会下降，建议跳过",
                        fontSize = 11.sp, color = Color(0xFFB26A00),
                    )
                }
            }
            // 产出信息
            if (t.status == TaskStatus.DONE) {
                Text("产出：${fmtSize(t.outputSize)}（${fmtSize(t.sourceSize)} → ${fmtSize(t.outputSize)}）", fontSize = 12.sp)
                if (t.sourceSize > 0 && t.outputSize.toDouble() / t.sourceSize > 0.95) {
                    Text(
                        "几乎无变化：源视频已经是低码率编码，重新编码省不下体积",
                        fontSize = 11.sp, color = Color(0xFFB26A00),
                    )
                }
            }
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
        val meta = MetadataReader.VideoMeta(
            durationMs = task.durationMs,
            width = task.width,
            height = task.height,
            bitrateBps = task.sourceBitrate,
            sizeBytes = task.sourceSize,
        )
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

/** 已完成的视频：媒体库/自定义文件夹用内容 Uri，私有目录用 FileProvider */
private fun videoUri(context: android.content.Context, t: TaskEntity): Uri? {
    t.outputUri?.let { return Uri.parse(it) }
    val path = t.outputPath ?: return null
    val file = File(path)
    if (!file.exists()) return null
    return FileProvider.getUriForFile(context, "com.ff.compress.fileprovider", file)
}

private fun play(context: android.content.Context, t: TaskEntity) {
    val uri = videoUri(context, t)
    if (uri == null) {
        android.widget.Toast.makeText(context, "文件不存在或已被删除", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "未找到可播放的视频应用", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: android.content.Context, t: TaskEntity) {
    val uri = videoUri(context, t) ?: return
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
