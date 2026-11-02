package com.ff.compress

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/** 读取视频元信息（分辨率/码率/时长） */
object MetadataReader {
    data class VideoMeta(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val bitrateBps: Long,
    )

    fun read(ctx: Context, uri: Uri): VideoMeta? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0
            if (dur <= 0) null else VideoMeta(dur, w, h, br)
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }
}
