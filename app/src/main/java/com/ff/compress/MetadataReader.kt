package com.ff.compress

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/**
 * 读取视频元信息（分辨率/时长/码率）。
 *
 * 关键点：MediaMetadataRetriever 的 METADATA_KEY_BITRATE 是「整个文件」的码率（视频+音频），
 * 用它来算压缩收益会失真——尤其是音频占比很高的低码率长视频。
 * 所以这里再用 MediaExtractor 逐轨读取，拿到视频轨和音频轨各自的码率。
 */
object MetadataReader {
    data class VideoMeta(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val bitrateBps: Long,            // 文件总码率
        val videoBitrateBps: Long = 0,   // 视频轨码率（0=未知）
        val audioBitrateBps: Long = 0,   // 音频轨码率（0=无音轨或未知）
        val audioMime: String? = null,   // 如 audio/mp4a-latm
        val sizeBytes: Long = 0,
    ) {
        /** 视频轨码率：轨道信息缺失时按「总码率-音频」或「文件体积/时长」推算 */
        val effectiveVideoBitrateBps: Long
            get() = when {
                videoBitrateBps > 0 -> videoBitrateBps
                bitrateBps > 0 && audioBitrateBps > 0 -> (bitrateBps - audioBitrateBps).coerceAtLeast(1)
                bitrateBps > 0 -> bitrateBps
                durationMs > 0 && sizeBytes > 0 -> sizeBytes * 8000 / durationMs   // bytes*8*1000/ms
                else -> 0
            }
    }

    fun read(ctx: Context, uri: Uri): VideoMeta? {
        val size = querySize(ctx, uri)

        var durationMs = 0L
        var width = 0
        var height = 0
        var totalBps = 0L
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            totalBps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0
            // 部分机型需旋转后才拿到真实显示尺寸
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val tmp = width; width = height; height = tmp }
        } catch (e: Exception) {
            return null
        } finally {
            r.release()
        }
        if (durationMs <= 0) return null

        var vBps = 0L
        var aBps = 0L
        var aMime: String? = null
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                val trackBps = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    try { f.getInteger(MediaFormat.KEY_BIT_RATE).toLong() } catch (e: Exception) { 0L }
                } else 0L
                when {
                    mime.startsWith("video/") -> {
                        vBps = trackBps
                        if (width <= 0 && f.containsKey(MediaFormat.KEY_WIDTH)) {
                            width = f.getInteger(MediaFormat.KEY_WIDTH)
                            height = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else 0
                        }
                    }
                    mime.startsWith("audio/") -> { aBps = trackBps; aMime = mime }
                }
            }
        } catch (e: Exception) {
            // 某些内容源 MediaExtractor 打不开，退回 retriever 的信息即可
        } finally {
            try { ex.release() } catch (e: Exception) { }
        }

        return VideoMeta(
            durationMs = durationMs,
            width = width,
            height = height,
            bitrateBps = totalBps,
            videoBitrateBps = vBps,
            audioBitrateBps = aBps,
            audioMime = aMime,
            sizeBytes = size,
        )
    }

    private fun querySize(ctx: Context, uri: Uri): Long = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst() && idx >= 0) c.getLong(idx) else 0
        } ?: 0
    } catch (e: Exception) { 0 }
}
