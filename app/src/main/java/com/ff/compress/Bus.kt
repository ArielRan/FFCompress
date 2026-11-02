package com.ff.compress

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 实时资源占用快照（systemCpuPercent < 0 表示系统限制读不到） */
data class ResourceStats(
    val appCpuPercent: Float = 0f,
    val systemCpuPercent: Float = -1f,
    val appMemMB: Float = 0f,
    val availMemMB: Float = 0f,
    val totalMemMB: Float = 0f,
    val ioWriteMBs: Float = 0f,
)

/** 当前运行任务的实时信息（不落库，UI 专用） */
data class RuntimeInfo(
    val taskId: Long,
    val stage: String,
    val progress: Float,   // 0~1
    val etaSec: Long,      // -1 = 估算中
    val estRatio: Float,   // 预估输出/输入体积比
)

/** 资源监控状态总线 */
object CompressionBus {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _res = MutableStateFlow(ResourceStats())
    val res: StateFlow<ResourceStats> = _res

    internal fun updateResource(stats: ResourceStats) {
        _res.value = stats
    }
}

/** 当前任务运行时状态（进度/ETA/预估比例） */
object TaskRuntime {
    private val _current = MutableStateFlow<RuntimeInfo?>(null)
    val current: StateFlow<RuntimeInfo?> = _current

    internal fun update(info: RuntimeInfo) { _current.value = info }
    internal fun clear() { _current.value = null }
}
