package com.ff.compress

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** 输出位置模式 */
enum class StorageMode(val label: String) {
    MEDIA("公共视频目录"),
    PRIVATE("应用私有目录"),
    TREE("自定义文件夹"),
}

/**
 * 全局存储配置（SharedPreferences）。
 * 一次设置对所有任务生效。
 */
object StoragePrefs {
    private const val FILE = "storage"
    private const val KEY_MODE = "mode"
    private const val KEY_SUB = "subfolder"
    private const val KEY_TREE = "tree_uri"

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mode(ctx: Context): StorageMode = runCatching {
        StorageMode.valueOf(p(ctx).getString(KEY_MODE, StorageMode.MEDIA.name)!!)
    }.getOrDefault(StorageMode.MEDIA)

    fun setMode(ctx: Context, m: StorageMode) = p(ctx).edit().putString(KEY_MODE, m.name).apply()

    fun subfolder(ctx: Context): String =
        p(ctx).getString(KEY_SUB, "FFCompress")?.takeIf { it.isNotBlank() } ?: "FFCompress"

    fun setSubfolder(ctx: Context, s: String) =
        p(ctx).edit().putString(KEY_SUB, s.trim().ifBlank { "FFCompress" }).apply()

    fun treeUri(ctx: Context): String? = p(ctx).getString(KEY_TREE, null)

    fun setTreeUri(ctx: Context, uri: String?) = p(ctx).edit().putString(KEY_TREE, uri).apply()

    /** 给用户看的当前位置说明 */
    fun describe(ctx: Context): String = when (mode(ctx)) {
        StorageMode.MEDIA -> "内部存储/Movies/${subfolder(ctx)}（相册可见）"
        StorageMode.PRIVATE -> "Android/data/com.ff.compress/files/Movies（仅本 App，卸载会删除）"
        StorageMode.TREE -> {
            val uri = treeUri(ctx)
            if (uri == null) "未选择文件夹" else {
                val name = runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(uri))?.name }.getOrNull()
                "自定义文件夹：${name ?: uri}"
            }
        }
    }
}

/** 把临时输出文件落到用户配置的位置 */
object OutputStore {
    data class Saved(val path: String?, val uri: String?)

    fun buildFileName(): String =
        "FFCompress_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.mp4"

    /**
     * @param temp 引擎写出的临时文件
     * @return path=真实路径（私有模式），uri=内容 Uri（媒体库/SAF 模式）
     */
    fun save(ctx: Context, temp: File, displayName: String): Saved {
        require(temp.exists() && temp.length() > 0) { "引擎未产出有效文件" }
        when (StoragePrefs.mode(ctx)) {
            StorageMode.PRIVATE -> {
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, displayName)
                temp.copyTo(dest, overwrite = true)
                temp.delete()
                return Saved(dest.absolutePath, null)
            }
            StorageMode.MEDIA -> {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/" + StoragePrefs.subfolder(ctx))
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val uri = resolver.insert(collection, values)
                    ?: throw IllegalStateException("无法在媒体库创建文件")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        temp.inputStream().use { it.copyTo(out) }
                    } ?: throw IllegalStateException("无法写入媒体库")
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
                temp.delete()
                return Saved(null, uri.toString())
            }
            StorageMode.TREE -> {
                val treeStr = StoragePrefs.treeUri(ctx)
                    ?: throw IllegalStateException("未选择自定义文件夹")
                val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(treeStr))
                    ?: throw IllegalStateException("自定义文件夹不可用，请重新选择")
                val doc = dir.createFile("video/mp4", displayName)
                    ?: throw IllegalStateException("无法在所选文件夹创建文件")
                try {
                    ctx.contentResolver.openOutputStream(doc.uri)?.use { out ->
                        temp.inputStream().use { it.copyTo(out) }
                    } ?: throw IllegalStateException("无法写入所选文件夹")
                } catch (e: Exception) {
                    runCatching { doc.delete() }
                    throw e
                }
                temp.delete()
                return Saved(null, doc.uri.toString())
            }
        }
    }

    /** 保存后给用户看的实际位置 */
    fun describeSaved(ctx: Context, saved: Saved): String =
        saved.path ?: run {
            val uri = saved.uri
            if (uri != null) "内部存储/Movies/${StoragePrefs.subfolder(ctx)}" else ""
        }

    fun takePersistable(ctx: Context, uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
}
