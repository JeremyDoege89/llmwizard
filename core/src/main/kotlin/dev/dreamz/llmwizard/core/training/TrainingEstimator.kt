package dev.dreamz.llmwizard.core.training

import dev.dreamz.llmwizard.core.catalog.ModelEntry
import dev.dreamz.llmwizard.core.scan.HardwareProfile
import kotlin.math.max

/**
 * How long a job will take (BUILD_PLAN E3.7). Training on a processor is compute-bound, unlike
 * chat, which is bandwidth-bound: each trained token costs about 6 × parameters floating-point
 * operations with LoRA and gradient checkpointing (forward, backward through the activations,
 * and the recomputed forward), 4 × without checkpointing.
 *
 * Processor throughput is cores × clock × FP32 operations per cycle (AVX 16, AVX2+FMA 32,
 * AVX-512 64) × [efficiency]. The efficiency is a starting point to calibrate from measured jobs,
 * the same way the speed constants were. Everything here is an estimate (SPEC.md rule 7).
 */
class TrainingEstimator(
    /**
     * Calibrated 2026-10-13: LoRA on qwen3:0.6b (0.75B in the catalog), 20 threads of the DL380p's
     * E5-2680 v2 (AVX, no AVX2) in an LXD container, measured 24–25 trained tokens/s; 0.25 had
     * predicted 50. Larger models use the cores better, so this is conservative for them.
     */
    private val efficiency: Double = 0.125,
    /** An RTX 3090 at fp16/bf16 with tensor cores, at a typical 35% utilization. */
    private val gpuFlops: Double = 71e12 * 0.35,
) {
    data class Estimate(
        val totalSeconds: Long,
        val tokensPerSecond: Double,
        val trainedTokens: Long,
        val explanation: String,
        val gpuComparison: String,
        val memoryBytes: Long,
    )

    fun cpuFlops(p: HardwareProfile, threads: Int? = null): Double {
        val cpu = p.cpu
        val cores = threads ?: cpu?.physicalCores ?: cpu?.logicalCpus?.let { max(it / 2, 1) } ?: 4
        val ghz = cpu?.model?.let { Regex("@\\s*([0-9.]+)\\s*GHz").find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: 2.5
        val perCycle = when {
            cpu?.has("avx512f") == true -> 64.0
            cpu?.has("avx2") == true && cpu.has("fma") == true -> 32.0
            cpu?.has("avx") == true -> 16.0
            else -> 8.0
        }
        return cores * ghz * 1e9 * perCycle * efficiency
    }

    fun estimate(p: HardwareProfile, model: ModelEntry, dataset: TrainingDataset, job: TrainingJob): Estimate {
        val params = model.paramsB * 1e9
        val flopsPerToken = (if (job.gradientCheckpointing) 6 else 4) * params
        // Each sample is cut at maxSeqLength; short samples are not padded up much with batch size 1.
        val perSample = if (dataset.sampleCount > 0) dataset.approxTokens.toDouble() / dataset.sampleCount else job.maxSeqLength.toDouble()
        val tokens = (dataset.sampleCount * minOf(perSample, job.maxSeqLength.toDouble()) * job.epochs).toLong()
        val tps = cpuFlops(p, job.threads) / flopsPerToken
        val seconds = (tokens / tps).toLong() + 300 // plus loading and export
        val gpuSeconds = (tokens * flopsPerToken / gpuFlops).toLong() + 120
        // fp32 weights + activations + adapter optimizer state, roughly.
        val memory = (params * 4 * 1.3).toLong() + 2_000_000_000L
        return Estimate(
            seconds, tps, tokens,
            "About ${duration(seconds)} on this machine (estimate): ${"%,d".format(tokens)} tokens to train " +
                "(${dataset.sampleCount} samples × ${job.epochs} epochs) at roughly ${"%.1f".format(tps)} tokens/s. " +
                "Needs about ${"%.0f".format(memory / 1e9)} GB of memory while training.",
            "A used RTX 3090 would take about ${duration(gpuSeconds)}.",
            memory,
        )
    }

    companion object {
        fun duration(s: Long): String = when {
            s < 90 -> "a minute"
            s < 3600 -> "${(s + 30) / 60} minutes"
            s < 48 * 3600 -> "%.1f hours".format(s / 3600.0).replace(".0 hours", " hours")
            else -> "${(s + 43_200) / 86_400} days"
        }
    }
}
