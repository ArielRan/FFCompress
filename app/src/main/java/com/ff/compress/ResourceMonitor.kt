package com.ff.compress

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.core.content.ContextCompat
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
 * - 整机算力占比：appCpu% / 核心数（appCpu 以「单核 100%」计，此值 = 本 App 吃掉整机多少算力）
 * - 热状态：PowerManager.getCurrentThermalStatus（Android 10+ 官方 Thermal API，无需权限）
 * - 内存：Debug.getMemoryInfo(PSS) + ActivityManager
 * - IO：/proc/self/io write_bytes 增量
 */
object ResourceMonitor {
    private const val CLOCK_TICKS = 100L // getconf CLK_TCK 通常为 100
    private var job: Job? = null
    /** 租约计数：UI 与 Service 各持一份，归零才真正停表 */
    private var leases = 0
    private var lastCtx: Context? = null

    /** 设备可用核心数（已考虑 cpuset 限制，比 /proc/cpuinfo 更贴近实际可调度核数） */
    private val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private fun readSelfCpuTicks(): Long {
        val stat = try { File("/proc/self/stat").readText() } catch (e: Exception) { return 0 }
        val parts = stat.substringAfterLast(")").trim().split(" ")
        val utime = parts.getOrNull(11)?.toLongOrNull() ?: 0
        val stime = parts.getOrNull(12)?.toLongOrNull() ?: 0
        return utime + stime
    }

    /**
     * 系统热状态（Android 10+ Thermal API）
     * 0 NONE / 1 LIGHT / 2 MODERATE / 3 SEVERE / 4 CRITICAL / 5 EMERGENCY / 6 SHUTDOWN
     */
    private fun readThermalStatus(ctx: Context): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus ?: -1
        } else -1
    } catch (e: Exception) {
        -1
    }

    /** 电池温度（℃）：读 ACTION_BATTERY_CHANGED 的粘性广播，无需权限；注意是 0.1℃ 单位 */
    private fun readBatteryTemp(ctx: Context): Float = try {
        val i = ContextCompat.registerReceiver(
            ctx, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val raw = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (raw == Int.MIN_VALUE) -1f else raw / 10f
    } catch (e: Exception) {
        -1f
    }


    /** /proc/stat 增量（旧系统可用；Android 9+ 多半被拒） */
    private fun readProcStat(): Pair<Long, Long>? {
        val line = try { File("/proc/stat").bufferedReader().readLine() } catch (e: Exception) { return null }
        val vals = line.removePrefix("cpu ").trim().split(" ").mapNotNull { it.toLongOrNull() }
        if (vals.size < 4) return null
        val idle = vals[3] + (vals.getOrNull(4) ?: 0L)
        return Pair(idle, vals.sum())
    }

    /**
     * `top -b -n 2 -d 1` 采样全机 CPU%（两次快照差值，约 1 秒）
     *
     * ⚠️ 关键坑：App 启动的 top 继承 untrusted_app SELinux 域，同样读不到 /proc/stat。
     * 此时 toybox top 不报错，而是输出一条退化的假汇总行：
     *     400%cpu  0%user  0%nice  0%sys  400%idle ...
     * （total 和 idle 恒相等、user/sys 恒为 0）——解析它会得到永远 0% 的"假成功"。
     * 这里通过 user/sys 全 0 且 idle≈total 识别假数据，返回 null 让 UI 显示"—"。
     */
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
            val user = Regex("([\\d.]+)%user").find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            val sys = Regex("([\\d.]+)%sys(?!i)").find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            if (total <= 0f) return null
            // user/sys 全 0 且接近满 idle：top 自己也读不到 /proc/stat，输出的是假数据
            if (user <= 0f && sys <= 0f && idle >= total * 0.999f) return null
            ((total - idle) * 100f / total).coerceIn(0f, 100f)
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

    /**
     * 启动采样（引用计数）
     *
     * UI 和后台 Service 都会各自持有一个租约：Service 压缩结束时释放它那一份，
     * 只要界面还开着，采样就继续跑，数值能自然回落到 0；
     * 两边都释放后才真正停表。早期版本直接 stop() 会把协程掐掉，
     * 于是 StateFlow 里冻结着最后一帧（例如 App 509%），界面永远显示压缩时的峰值。
     */
    @Synchronized
    fun start(ctx: Context) {
        leases++
        lastCtx = ctx.applicationContext
        if (job?.isActive == true) return
        val appCtx = ctx.applicationContext
        var lastSelfTicks = readSelfCpuTicks()
        var lastSysStat = readProcStat()
        var lastWrite = readWriteBytes()
        job = CompressionBus.scope.launch {
            var tick = 0
            var systemCpu = -1f          // -1 = 暂不可用
            var systemCpuUsable = true   // 连续失败 3 次后停用，每 30 秒重试一次
            var sysFail = 0
            var thermal = -1
            var batteryTemp = -1f
            while (isActive) {
                delay(1000)
                tick++

                val ticks = readSelfCpuTicks()
                val appCpu = (ticks - lastSelfTicks) * 100f / CLOCK_TICKS
                lastSelfTicks = ticks

                // 热状态 / 电池温度每 2 秒读一次（粘性广播开销可忽略，但没必要每秒发）
                if (tick % 2 == 0) {
                    thermal = readThermalStatus(appCtx)
                    batteryTemp = readBatteryTemp(appCtx)
                }

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

                val appCpuSafe = appCpu.coerceAtLeast(0f)
                CompressionBus.updateResource(
                    ResourceStats(
                        appCpuPercent = appCpuSafe,
                        systemCpuPercent = systemCpu,
                        cores = cores,
                        // appCpu 是「单核 100%」口径，除以核数即占整机总算力的比例
                        capacityPercent = (appCpuSafe / cores).coerceIn(0f, 100f),
                        thermalStatus = thermal,
                        batteryTempC = batteryTemp,
                        appMemMB = dm.totalPss.toFloat() / 1024f,
                        availMemMB = avail,
                        totalMemMB = total,
                        ioWriteMBs = ioMBs.coerceAtLeast(0f),
                    )
                )
            }
        }
    }

    /** 释放一份租约；归零时停表，并把占用类指标归零（保留内存/温度等静态信息） */
    @Synchronized
    fun stop() {
        if (leases > 0) leases--
        if (leases > 0) return
        job?.cancel()
        job = null
        publishIdleSnapshot()
    }

    /** 无任务时的快照：CPU / 磁盘归零，内存与温度仍是真实值 */
    private fun publishIdleSnapshot() {
        val ctx = lastCtx ?: return
        val (avail, total) = readMemory(ctx)
        val dm = Debug.MemoryInfo()
        Debug.getMemoryInfo(dm)
        CompressionBus.updateResource(
            ResourceStats(
                appCpuPercent = 0f,
                systemCpuPercent = -1f,
                cores = cores,
                capacityPercent = 0f,
                thermalStatus = readThermalStatus(ctx),
                batteryTempC = readBatteryTemp(ctx),
                appMemMB = dm.totalPss.toFloat() / 1024f,
                availMemMB = avail,
                totalMemMB = total,
                ioWriteMBs = 0f,
            )
        )
    }
}
