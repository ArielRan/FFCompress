package com.ff.compress

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 压缩参数 */
data class CompressOptions(
    val engine: Engine = Engine.HARDWARE,
    val targetHeight: Int = 0,          // 0=保持原分辨率
    val targetBitrateMbps: Float = 0f,  // 0=自动
    val threadLimit: Int = 4,           // 软编 FFmpeg 线程数（资源控制核心）
    val preset: String = "medium",      // 软编速度/压缩率档位
) : java.io.Serializable

enum class Engine(val label: String) {
    HARDWARE("硬件编码"),
    SOFTWARE("FFmpeg 软编"),
}

/** 实时资源占用快照 */
data class ResourceStats(
    val appCpuPercent: Float = 0f,   // 本 App 进程 CPU 占用（相对单核）
    val systemCpuPercent: Float = 0f,// 全机 CPU 占用
    val appMemMB: Float = 0f,        // 本 App PSS 内存
    val availMemMB: Float = 0f,      // 系统可用内存
    val totalMemMB: Float = 0f,      // 系统总内存
    val ioWriteMBs: Float = 0f,      // 磁盘写速度 MB/s
)

/** 压缩任务状态 */
data class CompressionState(
    val running: Boolean = false,
    val stage: String = "",
    val progress: Float = 0f,        // 0~1
    val outputPath: String? = null,
    val error: String? = null,
)

/** 全局状态总线：Service 与 UI 之间的桥梁 */
object CompressionBus {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(CompressionState())
    val state: StateFlow<CompressionState> = _state

    private val _res = MutableStateFlow(ResourceStats())
    val res: StateFlow<ResourceStats> = _res

    internal fun updateState(transform: (CompressionState) -> CompressionState) {
        _state.value = transform(_state.value)
    }

    internal fun updateResource(stats: ResourceStats) {
        _res.value = stats
    }
}
