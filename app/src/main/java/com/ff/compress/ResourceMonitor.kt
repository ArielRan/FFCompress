package com.ff.compress

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 资源监控：每 1 秒采样
 * - 本进程 CPU%：/proc/self/stat 的 utime+stime 增量（始终可用，含 FFmpeg 线程）
 * - 全机 CPU%：Android 9+ SELinux 禁止 App 读 /proc/stat，
 *   故先试 /proc/stat，失败则用 `top -b -n 2` 采样，都失败则标记不可用（-1）
 * - 内存：Debug.getMemoryInfo(PSS) + ActivityManager
 * - IO：/proc/self/io write_bytes 增量
 */
object ResourceMonitor {
    private const val CLOCK_TICKS = 100L // getconf CLK_TCK 通常为 100
    private var job: Job? = null

    private fun readSelfCpuTicks(): Long {
        val stat = try { File("/proc/self/stat").readText() } catch (e: Exception) { return 0 }
        val parts = stat.substringAfterLast(")").trim().split(" ")
        val utime = parts.getOrNull(11)?.toLongOrNull() ?: 0
        val stime = parts.getOrNull(12)?.toLongOrNull() ?: 0
        return utime + stime
    }

    /** /proc/stat 增量（旧系统可用；Android 9+ 多半被拒） */
    private fun readProcStat(): Pair<Long, Long>? {
        val line = try { File("/proc/stat").bufferedReader().readLine() } catch (e: Exception) { return null }
        val vals = line.removePrefix("cpu ").trim().split(" ").mapNotNull { it.toLongOrNull() }
        if (vals.size < 4) return null
        val idle = vals[3] + (vals.getOrNull(4) ?: 0L)
        return Pair(idle, vals.sum())
    }

    /** `top -b -n 2 -d 1` 采样全机 CPU%（两次快照差值，约 1 秒） */
    private fun readSystemCpuViaTop(): Float? {
        return try {
            val proc = ProcessBuilder("/system/bin/top", "-b", "-n", "2", "-d", "1")
                .redirectErrorStream(true)
                .start()
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            // 取最后一条 cpu 汇总行：400%cpu 12%user 3%sys 380%idle ...
            val line = text.lineSequence().lastOrNull { it.contains("%cpu") } ?: return null
            val total = Regex("([\\d.]+)%cpu").find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
            val idle = Regex("([\\d.]+)%idle").find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
            if (total <= 0f) null else ((total - idle) * 100f / total).coerceIn(0f, 100f)
        } catch (e: Exception) {
            null
        }
    }

    private fun readWriteBytes(): Long = try {
        File("/proc/self/io").readLines()
            .firstOrNull { it.startsWith("write_bytes:") }
            ?.substringAfter(":")?.trim()?.toLongOrNull() ?: 0
    } catch (e: Exception) { 0 }

    private fun readMemory(ctx: Context): Pair<Float, Float> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mb = 1024f * 1024f
        return if (am != null) {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            Pair(mi.availMem / mb, mi.totalMem / mb)
        } else Pair(0f, 0f)
    }

    fun start(ctx: Context) {
        stop()
        val appCtx = ctx.applicationContext
        var lastSelfTicks = readSelfCpuTicks()
        var lastSysStat = readProcStat()
        var lastWrite = readWriteBytes()
        job = CompressionBus.scope.launch {
            var tick = 0
            var systemCpu = -1f          // -1 = 暂不可用
            var systemCpuUsable = true   // 连续失败 3 次后停用，每 30 秒重试一次
            var sysFail = 0
            while (isActive) {
                delay(1000)
                tick++

                val ticks = readSelfCpuTicks()
                val appCpu = (ticks - lastSelfTicks) * 100f / CLOCK_TICKS
                lastSelfTicks = ticks

                // 系统 CPU 每 2 秒采样一次（top 采样本身约耗时 1 秒）
                val shouldProbe = systemCpuUsable || tick % 30 == 0
                if (tick % 2 == 0 && shouldProbe) {
                    val v = withContext(Dispatchers.IO) {
                        readProcStat()?.let { sys ->
                            val prev = lastSysStat
                            lastSysStat = sys
                            if (prev != null && sys.second > prev.second) {
                                val dTotal = sys.second - prev.second
                                val dIdle = sys.first - prev.first
                                (dTotal - dIdle) * 100f / dTotal
                            } else null
                        } ?: readSystemCpuViaTop()
                    }
                    if (v == null) {
                        sysFail++
                        if (sysFail >= 3) systemCpuUsable = false
                        systemCpu = -1f
                    } else {
                        sysFail = 0
                        systemCpuUsable = true
                        systemCpu = v.coerceIn(0f, 100f)
                    }
                }

                val write = readWriteBytes()
                val ioMBs = (write - lastWrite) / (1024f * 1024f)
                lastWrite = write

                val (avail, total) = readMemory(appCtx)
                val dm = Debug.MemoryInfo()
                Debug.getMemoryInfo(dm)

                CompressionBus.updateResource(
                    ResourceStats(
                        appCpuPercent = appCpu.coerceAtLeast(0f),
                        systemCpuPercent = systemCpu,
                        appMemMB = dm.totalPss.toFloat() / 1024f,
                        availMemMB = avail,
                        totalMemMB = total,
                        ioWriteMBs = ioMBs.coerceAtLeast(0f),
                    )
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
