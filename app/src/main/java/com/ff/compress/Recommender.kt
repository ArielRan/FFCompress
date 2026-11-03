package com.ff.compress

/**
 * 参数推荐引擎：根据源视频信息推荐压缩参数，并给出解释。
 *
 * 核心思路（v2）：
 * 1. 只看「视频轨」码率，不看文件总码率——音频占比高的低码率长视频，
 *    用总码率判断会得出「还有很大压缩空间」的错误结论。
 * 2. 压缩收益的上限由 CRF23 在该分辨率下的典型码率决定：
 *    源码率低于它时，重新编码不可能变小，只会变糊 → 直接标记跳过。
 * 3. 需要压缩时优先「码率预算」模式：目标码率严格低于源码率，
 *    保证输出一定比源小，而不是交给 CRF 自己发挥。
 */
object Recommender {

    data class Recommendation(
        val engine: Engine,
        val targetHeight: Int,        // 0=保持原分辨率
        val targetBitrateMbps: Float, // 0=CRF 模式
        val threadLimit: Int,
        val preset: String,
        val explanation: String,      // 给用户看的推荐理由
        val estRatio: Float,          // 预估体积比例（输出/输入）
        val skip: Boolean = false,    // 源已高度压缩，压缩收益极小
    )

    /**
     * libx264 CRF23 + medium 在该「短边」分辨率下的典型视频码率（Mbps，经验值）。
     * 用短边而不是高度：竖屏 1080×2400 的高度 2400 并不代表 2K 画质。
     */
    private fun crfTypicalMbps(shortSide: Int): Double = when {
        shortSide >= 2000 -> 14.0
        shortSide >= 1440 -> 7.0
        shortSide >= 1000 -> 4.0     // 1080P
        shortSide >= 700 -> 2.0      // 720P
        shortSide >= 450 -> 1.0      // 480P
        shortSide >= 300 -> 0.55     // 360P
        else -> 0.3
    }

    /** 该分辨率「再压就明显糊」的码率下限（Mbps） */
    private fun qualityFloorMbps(shortSide: Int): Double = when {
        shortSide >= 2000 -> 5.0
        shortSide >= 1440 -> 2.5
        shortSide >= 1000 -> 1.4
        shortSide >= 700 -> 0.8
        shortSide >= 450 -> 0.45
        shortSide >= 300 -> 0.25
        else -> 0.12
    }

    /** 短边（竖屏/横屏统一），用于判定画质档位 */
    private fun shortSide(w: Int, h: Int): Int {
        val a = if (w > 0) w else h
        val b = if (h > 0) h else w
        return minOf(a, b)
    }

    /**
     * 默认编码线程数：核心数的一半，夹在 2~6 之间。
     * 设计意图：压缩任务不能把手机跑满——留一半核心给系统和前台应用，
     * 8 核手机默认 4 线程、4 核设备默认 2 线程，用户仍可在参数里手动调 1~8。
     */
    private fun defaultThreads(): Int =
        ((Runtime.getRuntime().availableProcessors() + 1) / 2).coerceIn(2, 6)

    fun recommend(meta: MetadataReader.VideoMeta): Recommendation {
        val srcVideoBps = meta.effectiveVideoBitrateBps
        val srcVideoMbps = srcVideoBps / 1_000_000.0
        val srcAudioMbps = meta.audioBitrateBps / 1_000_000.0
        val srcTotalMbps = if (meta.bitrateBps > 0) meta.bitrateBps / 1_000_000.0
        else (srcVideoMbps + srcAudioMbps).coerceAtLeast(0.001)

        val short = shortSide(meta.width, meta.height)
        val is4k = short > 2000
        // 4K 降到 1080P 只在源码率足够高（真 4K）时才划算，否则降分辨率也省不下体积
        val targetHeight = if (is4k && srcVideoMbps >= 8.0) 1080 else 0
        val effHeight = if (targetHeight > 0) targetHeight else short
        val crfMbps = crfTypicalMbps(effHeight)
        val floorMbps = qualityFloorMbps(effHeight)

        val sizeInfo = "源视频 ${meta.width}×${meta.height}、" +
            "视频码率 ${"%.2f".format(srcVideoMbps)} Mbps" +
            (if (srcAudioMbps > 0) "（音频 ${"%.0f".format(srcAudioMbps * 1000)} kbps）" else "") +
            "、时长 ${meta.durationMs / 1000} 秒"

        // 音频体积：软编里码率不涨（<=128k 时直接复制音轨），这里按上限估算
        val audioOutMbps = when {
            meta.audioBitrateBps <= 0 -> 0.0
            srcAudioMbps <= 0.128 -> srcAudioMbps
            else -> 0.128
        }
        fun ratioOf(videoMbps: Double) =
            ((videoMbps + audioOutMbps) / srcTotalMbps).coerceIn(0.05, 1.0).toFloat()

        // ---------- 情况 A：源码率已经很低，压不动 ----------
        if (srcVideoMbps > 0 && srcVideoMbps <= floorMbps * 1.08) {
            return Recommendation(
                engine = Engine.SOFTWARE,
                targetHeight = 0,
                targetBitrateMbps = 0f,
                threadLimit = defaultThreads(),
                preset = "medium",
                explanation = "$sizeInfo。\n" +
                    "⚠️ 该视频已经压得很狠了：${effHeight}P 画质再压到低于约 " +
                    "${"%.2f".format(floorMbps)} Mbps 就会明显变糊。\n" +
                    "再转一次体积最多只能小 5~10%，画质却会实打实下降，多数情况是白转。\n" +
                    "建议：跳过这个视频，保留原文件。（仍要压缩的话，输出会走 CRF23 并把码率" +
                    "钉在源码率 90% 以内，保证不会变大。）",
                estRatio = ratioOf(crfMbps.coerceAtMost(srcVideoMbps * 0.9)),
                skip = true,
            )
        }

        // ---------- 情况 B：码率虚高 → CRF 恒定质量，收益最大 ----------
        if (srcVideoMbps >= crfMbps * 1.4) {
            return Recommendation(
                engine = Engine.SOFTWARE,
                targetHeight = targetHeight,
                targetBitrateMbps = 0f,
                threadLimit = defaultThreads(),
                preset = "medium",
                explanation = "$sizeInfo。\n" +
                    "码率远超 ${effHeight}P 画质所需（CRF23 典型约 ${"%.1f".format(crfMbps)} Mbps）" +
                    (if (targetHeight > 0) "，且分辨率过高，日常观看 1080P 足够。\n" else "，压缩空间很大。\n") +
                    "推荐 libx264 恒定质量（CRF 23），预计体积减少 " +
                    "${((1 - ratioOf(crfMbps)) * 100).toInt().coerceIn(10, 90)}% 左右，画质基本无损。",
                estRatio = ratioOf(crfMbps),
            )
        }

        // ---------- 情况 C：中间地带 → 码率预算模式，保证一定变小 ----------
        val target = (srcVideoMbps * 0.72)
            .coerceIn(floorMbps, srcVideoMbps * 0.85)
            .let { (it * 100).toInt() / 100.0 }   // 取到 0.01 Mbps
        return Recommendation(
            engine = Engine.SOFTWARE,
            targetHeight = targetHeight,
            targetBitrateMbps = target.toFloat(),
            threadLimit = defaultThreads(),
            preset = "medium",
            explanation = "$sizeInfo。\n" +
                "码率略高于 ${effHeight}P 的画质下限（约 ${"%.2f".format(floorMbps)} Mbps），" +
                "用 CRF 重压收益不稳定（可能只小几个百分点）。\n" +
                "推荐 libx264 + ${"%.2f".format(target)} Mbps 目标码率（源码率的 ${((target / srcVideoMbps) * 100).toInt()}%），" +
                "预计体积减少 ${((1 - ratioOf(target)) * 100).toInt()}%，同时保证输出一定小于原文件。",
            estRatio = ratioOf(target),
        )
    }

    /** 运行中根据参数预估最终体积比例 */
    fun estimateRatio(t: TaskEntity): Float {
        val srcMbps = t.sourceBitrate / 1_000_000.0
        if (srcMbps <= 0) return 0.7f
        val videoOut = if (t.targetBitrateMbps > 0f) {
            t.targetBitrateMbps.toDouble()
        } else {
            val eff = if (t.targetHeight > 0) t.targetHeight
            else shortSide(t.width, t.height).takeIf { it > 0 } ?: t.height
            crfTypicalMbps(eff)
        }
        return ((videoOut + 0.128) / srcMbps).coerceIn(0.05, 1.0).toFloat()
    }
}
