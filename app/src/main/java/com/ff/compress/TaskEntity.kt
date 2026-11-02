package com.ff.compress

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 压缩参数（可经 Intent 传递） */
data class CompressOptions(
    val engine: Engine = Engine.HARDWARE,
    val targetHeight: Int = 0,          // 0=保持原分辨率
    val targetBitrateMbps: Float = 0f,  // 0=自动(CRF)
    val threadLimit: Int = 4,           // 软编 FFmpeg 线程数
    val preset: String = "medium",      // 软编速度/压缩率档位
) : java.io.Serializable

enum class Engine(val label: String) {
    HARDWARE("硬件编码"),
    SOFTWARE("FFmpeg 软编"),
}

/** 压缩任务状态 */
object TaskStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val sourceSize: Long,        // 字节
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sourceBitrate: Long,     // bps
    // ---- 用户可调参数 ----
    val engine: String,          // HARDWARE / SOFTWARE
    val targetHeight: Int,       // 0=原分辨率
    val targetBitrateMbps: Float,// 0=自动(CRF)
    val threadLimit: Int,
    val preset: String,
    // ---- 运行结果 ----
    val status: String = TaskStatus.PENDING,
    val progress: Float = 0f,
    val outputPath: String? = null,
    val outputUri: String? = null,   // 媒体库 / SAF 模式下的内容 Uri
    val outputSize: Long = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
) {
    fun options() = CompressOptions(
        engine = if (engine == Engine.SOFTWARE.name) Engine.SOFTWARE else Engine.HARDWARE,
        targetHeight = targetHeight,
        targetBitrateMbps = targetBitrateMbps,
        threadLimit = threadLimit,
        preset = preset,
    )
}
