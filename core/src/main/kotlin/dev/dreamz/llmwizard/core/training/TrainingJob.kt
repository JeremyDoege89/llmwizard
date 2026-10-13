package dev.dreamz.llmwizard.core.training

import dev.dreamz.llmwizard.core.shell.Shell
import kotlinx.serialization.Serializable

@Serializable
enum class TrainingMethod {
    /** LoRA on full-precision weights: the method that works on a processor. */
    LORA,
    /** LoRA on 4-bit weights. Needs bitsandbytes on a graphics card; refused on CPU-only servers. */
    QLORA,
}

/**
 * How the trained result reaches Ollama. Only [MERGED]: the adapter is merged into the base
 * weights, converted to GGUF and quantized with llama.cpp. A separate adapter (`ADAPTER` in a
 * Modelfile) was tried first; Ollama 0.40.2 answers "LoRA adapters are no longer supported".
 */
@Serializable
enum class ExportMode { MERGED }

@Serializable
enum class TrainingStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

/** Where a job is, as the job itself reports it in `progress.json`. */
@Serializable
enum class TrainingStage { STARTING, DOWNLOADING, TRAINING, EXPORTING, REGISTERING, DONE, FAILED, CANCELLED }

/**
 * One fine-tuning job (BUILD_PLAN E3.4). This, written as `config.json`, is everything the fixed
 * training script reads: the script itself never changes with the job, so nothing the user types
 * becomes code.
 */
@Serializable
data class TrainingJob(
    val id: String,
    /** Ollama tag of the base model; the result is layered on or replaces this. */
    val baseModel: String,
    /** Hugging Face repository with the same model's original weights (training cannot use GGUF). */
    val hfRepo: String,
    val dataset: String,
    val method: TrainingMethod = TrainingMethod.LORA,
    /** Ollama name for the trained model. */
    val outputName: String,
    val exportMode: ExportMode = ExportMode.MERGED,
    /**
     * Size of the result, made with llama.cpp's llama-quantize. Ollama 0.40 cannot do it on import
     * on Linux: it does not quantize GGUF, and its safetensors import needs Apple's MLX runtime.
     */
    val quantize: String = "q4_K_M",
    val learningRate: Double = 2e-4,
    val epochs: Int = 3,
    val batchSize: Int = 1,
    val gradientAccumulationSteps: Int = 4,
    val maxSeqLength: Int = 1024,
    val loraRank: Int = 16,
    val loraAlpha: Int = 32,
    val loraDropout: Double = 0.05,
    val warmupSteps: Int = 10,
    val weightDecay: Double = 0.01,
    val optimizer: String = "adamw_torch",
    /** Saves a checkpoint this often, so a stopped job resumes close to where it was. */
    val saveSteps: Int = 25,
    /** Processor threads for PyTorch; null lets the script use the physical core count. */
    val threads: Int? = null,
    /** Trades about a third more compute for much less memory. */
    val gradientCheckpointing: Boolean = true,
    /** Where Ollama listens, as seen from the server. */
    val ollamaApi: String = "http://127.0.0.1:11434",
    val createdAt: String = "",
) {
    fun problems(): List<String> = buildList {
        if (runCatching { Shell.identifier(id) }.isFailure) add("Bad job id.")
        if (runCatching { Shell.modelTag(baseModel) }.isFailure) add("The base model name is not valid.")
        if (!HF_REPO.matches(hfRepo)) add("The Hugging Face repository must look like owner/name.")
        if (runCatching { Shell.identifier(dataset) }.isFailure) add("Pick a dataset.")
        if (runCatching { Shell.modelTag(outputName) }.isFailure || ':' in outputName && outputName.substringAfter(':').isEmpty()) add("The new model name may use lowercase letters, digits, '.', '_', '-' and one ':'.")
        if (outputName == baseModel) add("The new model needs its own name, or the base would be overwritten.")
        if (method == TrainingMethod.QLORA) add("QLoRA needs a graphics card; on a processor use LoRA.")
        if (quantize !in QUANTS) add("Quantization must be one of ${QUANTS.joinToString()}.")
        if (learningRate !in 1e-6..1e-2) add("Learning rate must be between 0.000001 and 0.01.")
        if (epochs !in 1..20) add("Epochs must be 1 to 20.")
        if (batchSize !in 1..64) add("Batch size must be 1 to 64.")
        if (gradientAccumulationSteps !in 1..256) add("Gradient accumulation must be 1 to 256.")
        if (maxSeqLength !in 128..32768) add("Max sequence length must be 128 to 32768.")
        if (loraRank !in setOf(4, 8, 16, 32, 64, 128)) add("LoRA rank must be 4, 8, 16, 32, 64 or 128.")
        if (loraAlpha !in 1..512) add("LoRA alpha must be 1 to 512.")
        if (loraDropout !in 0.0..0.5) add("LoRA dropout must be 0 to 0.5.")
        if (warmupSteps !in 0..10_000) add("Warmup steps must be 0 to 10000.")
        if (weightDecay !in 0.0..1.0) add("Weight decay must be 0 to 1.")
        if (optimizer !in OPTIMIZERS) add("Optimizer must be one of ${OPTIMIZERS.joinToString()}.")
        if (saveSteps !in 1..100_000) add("Checkpoint interval must be at least 1 step.")
        threads?.let { if (it !in 1..512) add("Threads must be 1 to 512.") }
        if (runCatching { Shell.bind(ollamaApi.removePrefix("http://")) }.isFailure) add("Ollama address is not valid.")
    }

    companion object {
        val HF_REPO = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")
        val QUANTS = listOf("q4_K_M", "q5_K_M", "q6_K", "q8_0", "f16")
        val OPTIMIZERS = listOf("adamw_torch", "adamw_torch_fused", "sgd", "adafactor")
    }
}

/** `progress.json`, written by the training script after every step and stage change. */
@Serializable
data class TrainingProgress(
    val stage: TrainingStage = TrainingStage.STARTING,
    val message: String? = null,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val currentEpoch: Double = 0.0,
    val totalEpochs: Int = 0,
    val loss: Double? = null,
    val learningRate: Double? = null,
    val tokensPerSecond: Double? = null,
    val estimatedRemainingSeconds: Long? = null,
    val elapsedSeconds: Long = 0,
    /** Set when the job ends: the Ollama name the result was registered as. */
    val ollamaModel: String? = null,
    val error: String? = null,
    val updatedAt: String? = null,
) {
    val fraction: Double get() = if (totalSteps > 0) currentStep.toDouble() / totalSteps else 0.0
}
