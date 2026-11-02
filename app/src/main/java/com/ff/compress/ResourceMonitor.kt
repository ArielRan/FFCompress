package com.ff.compress

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 资源监控：每秒采样一次
 * - 本进程 CPU%：/proc/self/stat 的 utime+stime 增量
 * - 全机 CPU%：/proc/stat 各核增量
 * - 内存：Debug.getMemoryInfo(PSS) + ActivityManager
 * - IO：/proc/self/io write_bytes 增量
 */
object ResourceMonitor {
    private const val CLOCK_TICKS = 100L // getconf CLK_TCK 通常为 100
    private var job: Job? = null

    private fun readProcLong(path: String): Long? = try {
        File(path).readText().trim().toLong()
    } catch (e: Exception) {
        null
    }

    private fun readSelfCpuTicks(): Long {
        val stat = try { File("/proc/self/stat").readText() } catch (e: Exception) { return 0 }
        val parts = stat.substringAfterLast(")").trim().split(" ")
        // parts[0]=pid 之后：state 是 parts[1]（原文件里 utime=第14字段）
        // 因为进程名可含空格已用 ')' 分隔，这里 utime = parts[11], stime = parts[12]（0-based）
        val utime = parts.getOrNull(11)?.toLongOrNull() ?: 0
        val stime = parts.getOrNull(12)?.toLongOrNull() ?: 0
        return utime + stime
    }

    private fun readSystemCpu(): LongArray? {
        val line = try { File("/proc/stat").bufferedReader().readLine() } catch (e: Exception) { return null }
        val vals = line.removePrefix("cpu ").trim().split(" ").mapNotNull { it.toLongOrNull() }
        if (vals.size < 4) return null
        val idle = vals[3] + (vals.getOrNull(4) ?: 0) // idle + iowait
        val total = vals.sum()
        return longArrayOf(idle, total)
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
        var lastSys = readSystemCpu()
        var lastWrite = readWriteBytes()
        job = CompressionBus.scope.launch {
            while (isActive) {
                delay(1000)
                val ticks = readSelfCpuTicks()
                val appCpu = (ticks - lastSelfTicks) * 100f / CLOCK_TICKS
                lastSelfTicks = ticks

                val sys = readSystemCpu()
                var sysCpu = 0f
                val prevSys = lastSys
                if (sys != null && prevSys != null) {
                    val dTotal = sys[1] - prevSys[1]
                    val dIdle = sys[0] - prevSys[0]
                    if (dTotal > 0) sysCpu = (dTotal - dIdle) * 100f / dTotal
                    lastSys = sys
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
                        systemCpuPercent = sysCpu.coerceIn(0f, 100f),
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
