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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 串行压缩队列服务：
 * - 从 Room 取下一条 PENDING 任务执行，直到队列清空
 * - 当前任务可取消（标记 CANCELLED 后继续下一条）
 * - 实时进度/ETA/预估比例通过 TaskRuntime 推给 UI，进度节流写库
 */
class CompressionService : Service() {

    private var queueJob: Job? = null
    private var engine: CompressionEngine? = null
    @Volatile private var cancelCurrent = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTI_ID, buildNotification("压缩队列待命", null, 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (queueJob == null) runQueue()
            ACTION_CANCEL_CURRENT -> {
                cancelCurrent = true
                engine?.cancel()
            }
            ACTION_STOP_UI -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun runQueue() {
        queueJob = CompressionBus.scope.launch(Dispatchers.Default) {
            val dao = AppDatabase.get(this@CompressionService).taskDao()
            var done = 0
            while (true) {
                val t = dao.nextPending() ?: break
                runSingle(dao, t)
                done++
            }
            if (done > 0) {
                notify(buildNotification("队列完成：本次压缩了 $done 个视频", null, 100))
            }
            TaskRuntime.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            queueJob = null
        }
    }

    private suspend fun runSingle(dao: TaskDao, t: TaskEntity) {
        val startedAt = SystemClock.elapsedRealtime()
        val estRatio = Recommender.estimateRatio(t)
        dao.update(t.copy(status = TaskStatus.RUNNING, progress = 0f))
        TaskRuntime.update(RuntimeInfo(t.id, "压缩中…", 0f, -1, estRatio))
        ResourceMonitor.start(this)
        cancelCurrent = false

        // 引擎先写临时文件，完成后按全局存储配置落到目标位置
        val out = File(cacheDir, "enc_${System.currentTimeMillis()}.mp4")
        var lastDb = 0L
        var maxProgress = 0f // 进度只进不退，防止任何引擎回调回跳导致进度条闪烁

        try {
            engine = if (t.engine == Engine.SOFTWARE.name) SoftwareCompressor() else HardwareCompressor()
            engine!!.compress(
                this,
                Uri.parse(t.uri),
                out,
                t.options(),
            ) { rawP ->
                val p = if (rawP > maxProgress) rawP.also { maxProgress = it } else maxProgress
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val eta = if (p >= 0.05f) {
                    (((elapsed / p) * (1 - p)) / 1000).toLong().coerceAtLeast(0)
                } else -1
                TaskRuntime.update(RuntimeInfo(t.id, "压缩中…", p, eta, estRatio))
                val now = SystemClock.elapsedRealtime()
                if (now - lastDb > 1000) {
                    lastDb = now
                    // 原子条件更新：只有仍处于 RUNNING 的行才会被写进度
                    CompressionBus.scope.launch { dao.updateProgress(t.id, p) }
                }
                notify(buildNotification("${t.displayName} ${(p * 100).toInt()}%", t.displayName, (p * 100).toInt()))
            }

            val now = System.currentTimeMillis()
            when {
                cancelCurrent -> {
                    out.delete()
                    dao.update(
                        t.copy(status = TaskStatus.CANCELLED, progress = 0f, finishedAt = now)
                    )
                }
                out.exists() && out.length() > 0 -> {
                    val size = out.length()
                    val saved = withContext(Dispatchers.IO) {
                        OutputStore.save(this@CompressionService, out, OutputStore.buildFileName())
                    }
                    dao.update(
                        t.copy(
                            status = TaskStatus.DONE, progress = 1f,
                            outputPath = saved.path, outputUri = saved.uri,
                            outputSize = size, finishedAt = now,
                        )
                    )
                }
                else -> {
                    out.delete()
                    dao.update(
                        t.copy(status = TaskStatus.FAILED, error = "引擎未产出有效文件", finishedAt = now)
                    )
                }
            }
        } catch (e: Exception) {
            out.delete()
            dao.update(
                t.copy(status = TaskStatus.FAILED, error = e.message ?: e.javaClass.simpleName, finishedAt = System.currentTimeMillis())
            )
        } finally {
            engine = null
            ResourceMonitor.stop()
            TaskRuntime.clear()
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

    private fun buildNotification(text: String, taskName: String?, progress: Int): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(if (taskName != null) "FFCompress：$taskName" else "FFCompress")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(taskName != null && progress in 1..99)
        if (progress in 1..99) b.setProgress(100, progress, false)
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
        const val ACTION_CANCEL_CURRENT = "com.ff.compress.CANCEL_CURRENT"
        const val ACTION_STOP_UI = "com.ff.compress.STOP_UI"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CompressionService::class.java).setAction(ACTION_START))
        }

        fun cancelCurrent(ctx: Context) {
            ctx.startService(Intent(ctx, CompressionService::class.java).setAction(ACTION_CANCEL_CURRENT))
        }
    }
}
