package com.ff.compress

/**
 * 参数推荐引擎：根据源视频信息推荐压缩参数，并给出解释。
 *
 * 规则思路：
 * - 手机录像/下载视频的码率通常远超该分辨率所需（x264 CRF23 的典型水平）
 * - 目标：画质肉眼无损的前提下最大化体积缩减
 * - 源码率本身已很低（压过很多轮）时，提示用户避免二次劣化
 */
object Recommender {

    data class Recommendation(
        val engine: Engine,
        val targetHeight: Int,       // 0=保持原分辨率
        val targetBitrateMbps: Float,// 0=CRF 模式
        val threadLimit: Int,
        val preset: String,
        val explanation: String,     // 给用户看的推荐理由
        val estRatio: Float,         // 预估体积比例（输出/输入），用于 UI 预估
    )

    fun recommend(meta: MetadataReader.VideoMeta): Recommendation {
        val srcMbps = meta.bitrateBps / 1_000_000.0

        // 1. 目标分辨率：>2K 降到 1080P，其余保持
        val targetHeight = if (meta.height > 2000) 1080 else 0
        val effectiveHeight = if (targetHeight > 0) targetHeight else meta.height

        // 2. CRF23（软编 medium）在该分辨率下的典型码率，经验值
        val crfTypicalMbps = when {
            effectiveHeight >= 2000 -> 14.0
            effectiveHeight >= 1000 -> 5.0
            effectiveHeight >= 700 -> 2.5
            effectiveHeight >= 450 -> 1.2
            else -> 0.8
        }

        val sizeInfo = "源视频 ${meta.width}×${meta.height}、" +
            "码率 ${"%.1f".format(srcMbps)} Mbps、" +
            "时长 ${meta.durationMs / 1000} 秒"

        return when {
            // 情况 A：码率明显虚高 → CRF 全量压缩，收益最大
            srcMbps >= crfTypicalMbps * 1.8 -> Recommendation(
                engine = Engine.SOFTWARE,
                targetHeight = targetHeight,
                targetBitrateMbps = 0f,
                threadLimit = 4,
                preset = "medium",
                explanation = "$sizeInfo。\n码率远超 ${effectiveHeight}P 画质所需（约 $crfTypicalMbps Mbps）" +
                    if (targetHeight > 0) "，且分辨率过高，日常观看 1080P 足够。\n推荐 libx264 恒定质量(CRF 23)" +
                        "，预计体积减少 60% 以上，肉眼看不出差别。" else "。\n推荐 libx264 恒定质量(CRF 23)" +
                    "，预计体积减少 60% 以上，画质基本无损。",
                estRatio = (1.0 - crfTypicalMbps / srcMbps).coerceIn(0.15, 0.85).toFloat(),
            )

            // 情况 B：码率略高 → 仍用 CRF，收益中等
            srcMbps >= crfTypicalMbps * 1.15 -> Recommendation(
                engine = Engine.SOFTWARE,
                targetHeight = targetHeight,
                targetBitrateMbps = 0f,
                threadLimit = 4,
                preset = "medium",
                explanation = "$sizeInfo。\n码率略高于 ${effectiveHeight}P 所需（约 $crfTypicalMbps Mbps）" +
                    "。\n推荐 libx264 恒定质量(CRF 23)，预计体积减少 15~40%，画质无损。",
                estRatio = (1.0 - crfTypicalMbps / srcMbps).coerceIn(0.10, 0.45).toFloat(),
            )

            // 情况 C：码率已经很低 → 提示勿过度压缩，用硬编小幅降码率
            else -> {
                val target = (srcMbps * 0.7).coerceIn(0.5, 2.0)
                Recommendation(
                    engine = Engine.HARDWARE,
                    targetHeight = 0,
                    targetBitrateMbps = target.toFloat(),
                    threadLimit = 4,
                    preset = "medium",
                    explanation = "$sizeInfo。\n⚠️ 该视频码率已接近 ${effectiveHeight}P 的合理下限" +
                        "（约 $crfTypicalMbps Mbps），继续压缩画质会明显劣化。\n保守推荐：硬件编码 + " +
                        "${"%.1f".format(target)} Mbps 码率，预计小幅缩小 20~30%；如非必要建议跳过此视频。",
                    estRatio = 0.72f,
                )
            }
        }
    }

    /** 运行中根据参数预估最终体积比例 */
    fun estimateRatio(t: TaskEntity): Float {
        return if (t.targetBitrateMbps > 0f) {
            // 码率模式：输出 ≈ 目标码率 × 时长（视频+音频 128k）
            val srcMbps = t.sourceBitrate / 1_000_000.0
            val outMbps = t.targetBitrateMbps + 0.128
            if (srcMbps <= 0) 0.7f else (outMbps / srcMbps).coerceIn(0.05, 1.0).toFloat()
        } else {
            // CRF 模式：按目标分辨率查典型码率
            val effectiveHeight = if (t.targetHeight > 0) t.targetHeight else t.height
            val crfMbps = when {
                effectiveHeight >= 2000 -> 14.0
                effectiveHeight >= 1000 -> 5.0
                effectiveHeight >= 700 -> 2.5
                effectiveHeight >= 450 -> 1.2
                else -> 0.8
            }
            val srcMbps = t.sourceBitrate / 1_000_000.0
            if (srcMbps <= 0) 0.7f else (crfMbps / srcMbps).coerceIn(0.05, 1.0).toFloat()
        }
    }
}
