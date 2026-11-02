package com.ff.compress

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 前台压缩服务：通知栏进度、可取消、资源监控 */
class CompressionService : Service() {

    private var job: Job? = null
    private var engine: CompressionEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTI_ID, buildNotification("准备中…", 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                engine?.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val uri = intent.getParcelableExtra<Uri>(EXTRA_URI)
                val opts = intent.getSerializableExtra(EXTRA_OPTS) as? CompressOptions ?: CompressOptions()
                if (uri != null && job == null) runJob(uri, opts)
            }
            ACTION_STOP_UI -> {
                // 任务已完成/失败后由 UI 触发，停止前台
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun runJob(uri: Uri, opts: CompressOptions) {
        CompressionBus.updateState { CompressionState(running = true, stage = "准备中…") }
        ResourceMonitor.start(this)
        engine = if (opts.engine == Engine.HARDWARE) HardwareCompressor() else SoftwareCompressor()

        job = CompressionBus.scope.launch(Dispatchers.Default) {
            try {
                val outDir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: filesDir
                val name = "FFCompress_${System.currentTimeMillis()}.mp4"
                val out = File(outDir, name)

                CompressionBus.updateState { it.copy(stage = "压缩中…") }
                engine!!.compress(
                    this@CompressionService, uri, out, opts
                ) { p ->
                    CompressionBus.updateState { it.copy(progress = p) }
                    notify(buildNotification("压缩中… ${(p * 100).toInt()}%", (p * 100).toInt()))
                }

                if (out.exists() && out.length() > 0) {
                    CompressionBus.updateState {
                        it.copy(running = false, progress = 1f, stage = "完成", outputPath = out.absolutePath)
                    }
                } else {
                    CompressionBus.updateState {
                        it.copy(running = false, stage = "失败", error = "引擎未产出有效文件")
                    }
                }
            } catch (e: Exception) {
                CompressionBus.updateState {
                    it.copy(running = false, stage = "失败", error = e.message ?: e.javaClass.simpleName)
                }
            } finally {
                ResourceMonitor.stop()
                notify(buildNotification("任务结束", 100, silent = true))
                job = null
            }
        }
    }

    override fun onDestroy() {
        engine?.cancel()
        ResourceMonitor.stop()
        super.onDestroy()
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "视频压缩", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String, progress: Int, silent: Boolean = false): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("FFCompress")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(!silent)
        if (progress in 1..99) {
            b.setProgress(100, progress, false)
        }
        return b.build()
    }

    private fun notify(n: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTI_ID, n)
    }

    companion object {
        private const val CHANNEL_ID = "compress"
        private const val NOTI_ID = 1
        const val ACTION_START = "com.ff.compress.START"
        const val ACTION_CANCEL = "com.ff.compress.CANCEL"
        const val ACTION_STOP_UI = "com.ff.compress.STOP_UI"
        const val EXTRA_URI = "uri"
        const val EXTRA_OPTS = "opts"

        fun start(ctx: Context, uri: Uri, opts: CompressOptions) {
            val i = Intent(ctx, CompressionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_OPTS, opts)
            ctx.startForegroundService(i)
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, CompressionService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
