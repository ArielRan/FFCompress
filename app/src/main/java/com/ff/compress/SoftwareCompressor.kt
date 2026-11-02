package com.ff.compress

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * FFmpeg 软编引擎（libx264）。
 * 资源控制：
 * - -threads N 限制编码线程数（用户 CPU 档位）
 * - -preset 控制速度/压缩率（ultrafast..veryslow）
 * - 会话可随时 cancel，进程生命周期由 FFmpegKit 管理
 *
 * 注意：不使用 SAF 虚拟路径（saf:xx 在社区 fork 下解析不可靠），
 * 先把内容 URI 复制到 cacheDir 再用真实路径处理。
 */
class SoftwareCompressor : CompressionEngine {
    @Volatile private var cancelled = false
    private var sessionId: Long? = null

    override fun cancel() {
        cancelled = true
        sessionId?.let { FFmpegKit.cancel(it) }
    }

    override suspend fun compress(
        ctx: Context,
        input: Uri,
        output: File,
        opts: CompressOptions,
        onProgress: (Float) -> Unit,
    ) {
        // 1. 复制输入到缓存文件
        val cacheFile = File(ctx.cacheDir, "ffmpeg_input_${System.currentTimeMillis()}.mp4")
        try {
            ctx.contentResolver.openInputStream(input)?.use { ins ->
                cacheFile.outputStream().use { ins.copyTo(it) }
            } ?: throw IllegalStateException("无法读取所选视频")
        } catch (e: Exception) {
            cacheFile.delete()
            throw IllegalStateException("复制输入失败: ${e.message}")
        }

        try {
            // 2. 探测时长（进度用）+ 各轨码率（决定码率上限和音频处理方式）
            val info = FFprobeKit.getMediaInformation(cacheFile.absolutePath)
            val mi = info.mediaInformation
            val durationSec = mi?.duration?.toDoubleOrNull() ?: 0.0
            val streams = mi?.streams ?: emptyList()
            val vStream = streams.firstOrNull { it.type == "video" }
            val aStream = streams.firstOrNull { it.type == "audio" }
            val srcAudioKbps = aStream?.bitrate?.toLongOrNull()?.div(1000) ?: 0L
            val audioCodec = aStream?.codec?.lowercase() ?: ""
            var srcVideoKbps = vStream?.bitrate?.toLongOrNull()?.div(1000) ?: 0L
            if (srcVideoKbps <= 0 && durationSec > 0) {
                // 轨道码率缺失时用文件体积推算视频轨码率
                val totalKbps = (cacheFile.length() * 8.0 / 1000.0 / durationSec).toLong()
                srcVideoKbps = (totalKbps - srcAudioKbps).coerceAtLeast(1)
            }

            // 3. 组装命令
            val parts = mutableListOf("-y", "-i", cacheFile.absolutePath, "-map", "0:v:0")
            if (aStream != null) parts += listOf("-map", "0:a:0")
            parts += listOf("-sn", "-dn")
            if (opts.targetHeight > 0) {
                parts += listOf("-vf", "scale=-2:${opts.targetHeight}")
            }

            // 视频码率上限 = 源码率的 90%：无论 CRF 还是固定码率，都不会产出更大的文件
            val capKbps = if (srcVideoKbps > 0) (srcVideoKbps * 0.9).toInt().coerceAtLeast(120) else 0

            if (opts.targetBitrateMbps > 0f) {
                var kbps = (opts.targetBitrateMbps * 1000).toInt()
                if (capKbps > 0 && kbps > capKbps) kbps = capKbps
                parts += listOf(
                    "-c:v", "libx264",
                    "-preset", opts.preset,
                    "-b:v", "${kbps}k",
                    "-maxrate", "${(kbps * 1.5).toInt()}k",
                    "-bufsize", "${kbps * 2}k",
                )
            } else {
                parts += listOf("-c:v", "libx264", "-preset", opts.preset, "-crf", "23")
                // CRF 对已高度压缩的源会「越压越大」，这里钉一个上限兜底
                if (capKbps > 0) parts += listOf("-maxrate", "${capKbps}k", "-bufsize", "${capKbps * 2}k")
            }

            // 音频：源已经是低码率 AAC/MP3 时直接复制音轨，避免「视频省下来的被音频吃回去」
            val audioArgs = when {
                aStream == null -> listOf("-an")
                audioCodec in setOf("aac", "mp3") && srcAudioKbps in 16..160 -> listOf("-c:a", "copy")
                else -> {
                    val ab = if (srcAudioKbps in 48..128) srcAudioKbps else 96
                    listOf("-c:a", "aac", "-b:a", "${ab}k")
                }
            }

            parts += listOf("-threads", opts.threadLimit.toString())
            parts += audioArgs
            parts += listOf("-movflags", "+faststart")
            parts += output.absolutePath
            val cmd = parts.joinToString(" ")

            // 4. 异步执行，统计回调推进度，失败抛异常给上层展示
            suspendCancellableCoroutine { cont ->
                cancelled = false
                val session: FFmpegSession = FFmpegKit.executeAsync(
                    cmd,
                    { s ->
                        when {
                            ReturnCode.isSuccess(s.returnCode) -> cont.resume(Unit)
                            ReturnCode.isCancel(s.returnCode) -> cont.resume(Unit)
                            else -> {
                                val tail = s.allLogsAsString?.takeLast(300)?.trim()
                                if (!cancelled) {
                                    lastError = "FFmpeg 退出码 ${s.returnCode}${tail?.let { "：$it" } ?: ""}"
                                }
                                cont.resume(Unit)
                            }
                        }
                    },
                    null,
                    { statistics ->
                        if (durationSec > 0) {
                            val p = (statistics.time / 1000.0 / durationSec).toFloat()
                            onProgress(p.coerceIn(0f, 0.99f))
                        }
                    },
                )
                sessionId = session.sessionId
            }
            lastError?.let { throw IllegalStateException(it) }
        } finally {
            cacheFile.delete()
            lastError = null
        }
    }

    @Volatile private var lastError: String? = null
}
