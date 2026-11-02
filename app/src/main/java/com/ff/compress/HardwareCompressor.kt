package com.ff.compress

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.common.Effect
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.coroutines.resume

/** 压缩引擎统一接口 */
interface CompressionEngine {
    /** 阻塞/挂起直到完成，返回输出文件；失败抛异常 */
    suspend fun compress(
        ctx: Context,
        input: Uri,
        output: File,
        opts: CompressOptions,
        onProgress: (Float) -> Unit,
    )

    fun cancel()
}

/** 硬件编码引擎：Media3 Transformer（低 CPU、省电） */
class HardwareCompressor : CompressionEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var cancelled = false
    @Volatile private var lastError: String? = null

    override fun cancel() {
        cancelled = true
        mainHandler.post { transformer?.cancel() }
    }

    override suspend fun compress(
        ctx: Context,
        input: Uri,
        output: File,
        opts: CompressOptions,
        onProgress: (Float) -> Unit,
    ) {
        lastError = null
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        cancelled = false
        mainHandler.post {
            try {
                val videoEffects: List<androidx.media3.common.Effect> = if (opts.targetHeight > 0) {
                    listOf(Presentation.createForHeight(opts.targetHeight))
                } else {
                    emptyList()
                }
                val effects = Effects(emptyList(), videoEffects)

                val builder = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)

                if (opts.targetBitrateMbps > 0f) {
                    val settings = VideoEncoderSettings.Builder()
                        .setBitrate((opts.targetBitrateMbps * 1_000_000).toInt())
                        .build()
                    builder.setEncoderFactory(
                        DefaultEncoderFactory.Builder(ctx)
                            .setRequestedVideoEncoderSettings(settings)
                            .build()
                    )
                }

                val t = builder
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: androidx.media3.transformer.Composition, result: ExportResult) {
                            if (!cancelled) cont.resume(Unit)
                        }
                        override fun onError(composition: androidx.media3.transformer.Composition, result: ExportResult, exportException: ExportException) {
                            lastError = exportException.message ?: "未知编码错误"
                            if (!cancelled) cont.resume(Unit)
                        }
                    })
                    .build()
                transformer = t

                val mediaItem = EditedMediaItem.Builder(MediaItem.fromUri(input))
                    .setEffects(effects)
                    .build()
                t.start(mediaItem, output.absolutePath)

                // 轮询进度
                val holder = androidx.media3.transformer.ProgressHolder()
                mainHandler.post(object : Runnable {
                    override fun run() {
                        if (cancelled) return
                        val state = t.getProgress(holder)
                        onProgress(holder.progress / 100f)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE && holder.progress < 100) {
                            mainHandler.postDelayed(this, 200)
                        } else if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(1f)
                        } else {
                            mainHandler.postDelayed(this, 200)
                        }
                    }
                })
            } catch (e: Exception) {
                if (!cancelled) {
                    lastError = e.message ?: e.javaClass.simpleName
                    cont.resume(Unit)
                }
            }
        }
        }
        lastError?.let { throw IllegalStateException("硬件编码失败：$it") }
    }
}
