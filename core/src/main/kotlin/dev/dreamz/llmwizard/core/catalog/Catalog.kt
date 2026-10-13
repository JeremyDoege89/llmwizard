package dev.dreamz.llmwizard.core.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The model catalog (PLAN.md 5.5): entries plus the tunable speed constants, all data. */
@Serializable
data class Catalog(val version: Int, val checked: String? = null, val constants: Constants, val models: List<ModelEntry>) {
    fun byTag(tag: String) = models.firstOrNull { it.ollamaTag == tag }
    fun byId(id: String) = models.firstOrNull { it.id == id }

    companion object {
        val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Catalog = json.decodeFromString(serializer(), text)
        fun bundled(): Catalog = parse(
            requireNotNull(Catalog::class.java.getResourceAsStream("/llmwizard/catalog/catalog.json")) { "bundled catalog missing" }
                .readBytes().toString(Charsets.UTF_8),
        )
    }
}

@Serializable
data class ModelEntry(
    val id: String,
    val name: String,
    val ollamaTag: String,
    val paramsB: Double,
    /** Parameters used per token for mixture-of-experts models; null for dense. */
    val activeParamsB: Double? = null,
    val quant: String,
    val downloadBytes: Long,
    val uses: List<String>,
    /** 1 (weak) to 5 (strong), an editorial judgement relative to the rest of the catalog. */
    val quality: Int,
    val supportsTools: Boolean = false,
    val minOllamaVersion: String = "0.0.0",
    /** Measured by the eval set (PLAN.md 5.14); null until measured. */
    val toolAccuracy: Double? = null,
    /** For filtering in the model browser: "chat", "coding", "reasoning", "research", "uncensored". */
    val tags: Set<String> = emptySet(),
    /** Refusal training removed (abliterated) or never added. Only offered when the user asks for it. */
    val uncensored: Boolean = false,
    /** Short licence id such as "apache-2.0" or "cc-by-nc-4.0"; null when not recorded. */
    val license: String? = null,
    /** Context length the model was trained for, when that is below what the app would ask for. */
    val recommendedContext: Int? = null,
    /** Model card; when null, [pageUrl] falls back to the model's page on ollama.com. */
    val infoUrl: String? = null,
    val notes: String,
) {
    val isMoe: Boolean get() = activeParamsB != null
    val pageUrl: String get() = infoUrl ?: ollamaTag.substringBefore(':').let { if ('/' in it) "https://ollama.com/$it" else "https://ollama.com/library/$it" }
    /** Licences that rule out commercial use, worth a line wherever the model is offered. */
    val nonCommercial: Boolean get() = license != null && (license.contains("-nc") || license.contains("non-production"))
    /** Share of the weights read for each generated token. */
    val activeFraction: Double get() = (activeParamsB ?: paramsB) / paramsB
}

@Serializable
data class Constants(
    val k: Double,
    val kGpu: Double = k,
    /** Speed factor when the CPU (or the VM) shows no AVX. Measured: 6.3 vs 19.8 tok/s on twin VMs. */
    val noAvxFactor: Double = 0.25,
    val memoryHeadroomFraction: Double,
    val fixedOverheadBytes: Long,
    val diskSpareBytes: Long,
    val runtimeBytes: Long,
    val defaultContextLength: Int,
    val kvBytesPer1kTokensBySize: List<KvRule>,
    val classes: List<ClassRule>,
    val cpuBandwidth: List<CpuBandwidthRule>,
    val gpuBandwidth: List<GpuBandwidthRule>,
)

@Serializable data class KvRule(val maxParamsB: Double, val bytes: Long)
@Serializable data class ClassRule(val name: SpeedClass, val minTps: Double)
@Serializable data class CpuBandwidthRule(val match: String, val label: String, val lowGBs: Double, val highGBs: Double, val perCoreGBs: Double, @kotlinx.serialization.SerialName("_calibration") val calibration: String? = null)
@Serializable data class GpuBandwidthRule(val match: String, @kotlinx.serialization.SerialName("GBs") val gbs: Double)

@Serializable
enum class SpeedClass(val label: String) {
    FAST("fast"), COMFORTABLE("comfortable"), USABLE("usable"), SLOW("slow"), PAINFUL("painful");

    fun atLeast(other: SpeedClass) = ordinal <= other.ordinal
}
