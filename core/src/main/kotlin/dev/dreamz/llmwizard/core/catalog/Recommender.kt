package dev.dreamz.llmwizard.core.catalog

import dev.dreamz.llmwizard.core.scan.HardwareProfile
import dev.dreamz.llmwizard.core.scan.SmiState
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What the user said they want the model for. */
@Serializable
enum class Intent(val use: String, val label: String) {
    CHAT("chat", "everyday chat"),
    CODE("code", "programming help"),
    ASSISTANT("tools", "the phone assistant (tool calls)"),
}

@Serializable enum class Placement { GPU, CPU }

@Serializable
data class Estimate(val lowTps: Double, val highTps: Double, val speedClass: SpeedClass) {
    val midTps: Double get() = sqrt(lowTps * highTps)
    /** Always says it is an estimate (SPEC.md rule 7). */
    fun describe(): String = "${speedClass.label}, about ${fmt(lowTps)}–${fmt(highTps)} tokens/s (estimate)"

    private fun fmt(v: Double) = if (v >= 10) "%.0f".format(v) else "%.1f".format(v)
}

@Serializable
data class Candidate(
    val entry: ModelEntry,
    val fits: Boolean,
    /** Why it does not fit, in plain words; null when it fits. */
    val fitProblem: String? = null,
    val placement: Placement? = null,
    val estimate: Estimate? = null,
    val memoryNeededBytes: Long,
    val matchesIntent: Boolean,
    /** Plain-English "why this model" for the model card and the install flow (BUILD_PLAN E1.5). */
    val explanation: String = "",
)

@Serializable
data class Pick(val candidate: Candidate, val reason: String)

@Serializable
data class Recommendation(
    val pick: Pick?,
    val faster: Pick?,
    val smarter: Pick?,
    /** True when nothing reached "usable" and [pick] is the least bad option. */
    val leastBad: Boolean,
    /** Plain-English caveats: unknown memory, intent mismatch, measured accuracy missing. */
    val notes: List<String>,
    /** Every catalog model that passed the filter, fitting or not. */
    val candidates: List<Candidate>,
    val bandwidthLabel: String?,
    /** Every catalog model, filter ignored: for looking up an installed model's estimate. */
    val allCandidates: List<Candidate> = candidates,
)

/**
 * Fit is a hard gate; speed is an estimate from memory bandwidth (PLAN.md 5.5).
 * Every constant comes from the catalog so it can be tuned without an app release.
 */
class Recommender(private val catalog: Catalog) {
    private val c = catalog.constants

    fun recommend(
        p: HardwareProfile,
        intent: Intent = Intent.CHAT,
        contextLength: Int = c.defaultContextLength,
        filter: ModelFilter = ModelFilter.DEFAULT,
    ): Recommendation {
        val notes = mutableListOf<String>()
        val ram = p.memory?.availableBytes ?: p.memory?.totalBytes?.let { it - 1_500_000_000L }
        if (ram == null) notes += "Could not read how much memory this machine has, so nothing can be checked for fit. Scan again, or enter it by hand."
        val vram = vramBytes(p)
        val cpuBw = cpuBandwidth(p)
        val freeDisk = modelsDiskFree(p)
        if (freeDisk == null) notes += "Could not read free disk space; make sure there is room for the download."
        val needRuntime = p.ollama?.installed != true

        val all = catalog.models.map { e -> evaluate(e, p, intent, contextLength, ram, vram, cpuBw, freeDisk, needRuntime) }
        val candidates = all.filter { filter.matches(it.entry) }
        if (candidates.isEmpty()) notes += "No model in the catalog matches the current filter."
        val fitting = candidates.filter { it.fits && it.estimate != null }
        val preferred = fitting.filter { it.matchesIntent }.ifEmpty { fitting }

        val usable = preferred.filter { it.estimate!!.speedClass.atLeast(SpeedClass.USABLE) }
        val order = compareByDescending<Candidate> { it.entry.quality }.thenByDescending { it.estimate!!.midTps }
        val leastBad = usable.isEmpty() && preferred.isNotEmpty()
        val pick = (usable.sortedWith(order).firstOrNull()
            ?: preferred.maxByOrNull { it.estimate!!.midTps })

        val faster = pick?.let { pk ->
            // Only alternatives still worth using: at most one quality step down.
            fitting.filter {
                it !== pk && it.estimate!!.midTps > pk.estimate!!.midTps * 1.25 &&
                    it.entry.quality >= pk.entry.quality - 1 && (it.matchesIntent || !pk.matchesIntent)
            }.sortedWith(order).firstOrNull()
        }
        val smarter = pick?.let { pk ->
            // Painfully slow options are offered only when nothing better exists.
            fitting.filter { it !== pk && it.entry.quality > pk.entry.quality && (it.matchesIntent || !pk.matchesIntent) }
                .sortedWith(compareBy<Candidate> { it.estimate!!.speedClass == SpeedClass.PAINFUL }.then(order)).firstOrNull()
        }

        if (pick != null && !pick.matchesIntent) {
            notes += "None of the models that fit here is meant for ${intent.label}. ${pick.entry.name} will work, but expect weaker results."
        }
        if (intent == Intent.ASSISTANT && pick != null && pick.entry.toolAccuracy == null) {
            notes += "How reliably ${pick.entry.name} picks the right phone action has not been measured yet."
        }
        if (pick == null && ram != null && candidates.isNotEmpty()) {
            notes += "No model in the catalog fits on this machine."
        }

        return Recommendation(
            pick = pick?.let { Pick(it, pickReason(it, leastBad)) },
            faster = faster?.let { Pick(it, "Faster: ${it.entry.name}, ${it.estimate!!.describe()}. ${qualityCompare(it, pick)}") },
            smarter = smarter?.let { Pick(it, "Smarter: ${it.entry.name}, ${it.estimate!!.describe()}. ${slowerNote(it, pick)}") },
            leastBad = leastBad,
            notes = notes,
            candidates = candidates,
            bandwidthLabel = cpuBw?.label,
            allCandidates = all,
        )
    }

    private fun evaluate(
        e: ModelEntry, p: HardwareProfile, intent: Intent, ctx: Int,
        ram: Long?, vram: Long?, cpuBw: Bandwidth?, freeDisk: Long?, needRuntime: Boolean,
    ): Candidate {
        val kv = kvBytes(e, ctx)
        val need = e.downloadBytes + kv + c.fixedOverheadBytes
        val matches = when (intent) {
            Intent.ASSISTANT -> e.supportsTools
            else -> intent.use in e.uses
        }
        val diskNeed = e.downloadBytes + c.diskSpareBytes + if (needRuntime) c.runtimeBytes else 0
        val cand = when {
            freeDisk != null && freeDisk < diskNeed ->
                Candidate(e, false, "Needs ${gb(diskNeed)} of free disk, there is ${gb(freeDisk)}.", memoryNeededBytes = need, matchesIntent = matches)
            vram != null && need <= vram * c.memoryHeadroomFraction -> {
                val tps = c.kGpu * gpuBandwidth(p) * 1e9 / (e.downloadBytes * e.activeFraction)
                Candidate(e, true, null, Placement.GPU, estimate(tps * 0.8, tps * 1.2), need, matches)
            }
            ram == null -> Candidate(e, false, "Memory unknown.", memoryNeededBytes = need, matchesIntent = matches)
            need > ram * c.memoryHeadroomFraction ->
                Candidate(e, false, "Needs about ${gb(need)} of memory, ${gb((ram * c.memoryHeadroomFraction).toLong())} is usable here.", memoryNeededBytes = need, matchesIntent = matches)
            else -> {
                val bw = cpuBw!!
                val touched = e.downloadBytes * e.activeFraction
                Candidate(e, true, null, Placement.CPU,
                    estimate(c.k * bw.lowGBs * 1e9 / touched, c.k * bw.highGBs * 1e9 / touched), need, matches)
            }
        }
        return cand.copy(explanation = explain(cand))
    }

    /**
     * KV cache for [ctx] tokens. A model trained for less context than asked for never uses more
     * than its own window, so that caps the estimate.
     */
    fun kvBytes(e: ModelEntry, ctx: Int): Long {
        val used = min(ctx, e.recommendedContext ?: ctx)
        return c.kvBytesPer1kTokensBySize.first { e.paramsB <= it.maxParamsB }.bytes * used / 1024
    }

    /** "Why this model", in the order a person asks: how fast, what it is, what it costs, the catch. */
    fun explain(cand: Candidate): String {
        val e = cand.entry
        val parts = mutableListOf<String>()
        val est = cand.estimate
        parts += when {
            est != null -> {
                val where = if (cand.placement == Placement.GPU) "on the graphics card" else "on the processor"
                "${e.name} should run at ${est.describe()} $where."
            }
            cand.fitProblem != null -> "${e.name} does not fit here: ${cand.fitProblem}"
            else -> "${e.name}: speed unknown until the machine is scanned."
        }
        val size = "%.1fB".format(e.paramsB).replace(".0B", "B")
        parts += when {
            e.isMoe -> "It is ${article(size)} $size mixture-of-experts that reads only about ${"%.1f".format(e.activeParamsB)}B parameters per word, which is why it is quicker than its size suggests."
            else -> "It is ${article(size)} $size model; every word it writes reads all of its weights."
        }
        if (e.uncensored) parts += "It is uncensored: refusal training was removed, so it answers instead of arguing about what you asked."
        parts += "${gb(e.downloadBytes)} download, about ${gb(cand.memoryNeededBytes)} of memory while loaded."
        if (e.nonCommercial) parts += "Its licence (${e.license}) does not allow commercial use."
        e.recommendedContext?.let { if (it < c.defaultContextLength) parts += "It only remembers about ${it / 1024}K tokens of conversation." }
        e.toolAccuracy?.let { parts += "Picked the right phone action in ${"%.0f".format(it * 100)}% of the eval cases (measured)." }
        tradeOff(cand)?.let { parts += "Trade-off: $it" }
        return parts.joinToString(" ")
    }

    private fun tradeOff(cand: Candidate): String? {
        val cls = cand.estimate?.speedClass ?: return null
        return when {
            cls == SpeedClass.PAINFUL || cls == SpeedClass.SLOW -> "you will wait for every answer; good for jobs you can leave running, not for conversation."
            cand.entry.quality <= 2 -> "smaller models are quick but weaker at multi-step reasoning and long code."
            !cls.atLeast(SpeedClass.COMFORTABLE) -> "usable, but slower than reading speed."
            else -> null
        }
    }

    private fun estimate(low: Double, high: Double): Estimate {
        val mid = sqrt(low * high)
        val cls = c.classes.sortedByDescending { it.minTps }.first { mid >= it.minTps }.name
        return Estimate(low, high, cls)
    }

    data class Bandwidth(val lowGBs: Double, val highGBs: Double, val label: String)

    /** Effective memory bandwidth guessed from the CPU generation and the cores this machine gets. */
    fun cpuBandwidth(p: HardwareProfile): Bandwidth? {
        val cpu = p.cpu
        val model = cpu?.model ?: ""
        val rule = c.cpuBandwidth.first { Regex(it.match).containsMatchIn(model) }
        val cores = cpu?.physicalCores
            ?: cpu?.logicalCpus?.let { l -> cpu.threadsPerCore?.let { l / max(it, 1) } ?: max(l / 2, 1) }
        var low = rule.lowGBs
        var high = rule.highGBs
        if (cores != null) {
            // A VM or small machine with few cores cannot pull the full bandwidth.
            val cap = cores * rule.perCoreGBs
            high = min(high, cap)
            low = min(low, cap * 0.8)
        }
        // Without AVX llama.cpp falls back to much slower kernels. (AVX without AVX2 is the
        // calibration point for the old-Xeon row, so it gets no extra penalty.)
        if (cpu?.has("avx") == false) { low *= c.noAvxFactor; high *= c.noAvxFactor }
        return Bandwidth(low, high, rule.label + if (cores != null) ", $cores cores" else "")
    }

    private fun vramBytes(p: HardwareProfile): Long? {
        val n = p.nvidia ?: return null
        if (n.smi != SmiState.WORKING) return null
        // Ollama splits a model across cards, but one card is the conservative, faster case.
        return n.cards.mapNotNull { it.vramMiB }.maxOrNull()?.let { it * 1024 * 1024 }
    }

    private fun gpuBandwidth(p: HardwareProfile): Double {
        val name = p.nvidia?.cards?.firstOrNull()?.name ?: ""
        return c.gpuBandwidth.first { Regex(it.match).containsMatchIn(name) }.gbs
    }

    private fun modelsDiskFree(p: HardwareProfile): Long? =
        p.disk?.models?.firstOrNull()?.second?.freeBytes ?: p.disk?.root?.freeBytes

    private fun pickReason(cand: Candidate, leastBad: Boolean): String {
        val e = cand.entry
        val where = if (cand.placement == Placement.GPU) "on the graphics card" else "on the processor"
        val lead = if (leastBad) "Nothing here reaches a usable speed. The least bad option is ${e.name}"
        else "${e.name} is the best model that runs at a usable speed here"
        return "$lead: ${cand.estimate!!.describe()}, $where. ${e.notes}"
    }

    private fun qualityCompare(alt: Candidate, pick: Candidate?): String = when {
        pick == null -> ""
        alt.entry.quality < pick.entry.quality -> "Quicker replies, simpler answers."
        else -> "Quicker, and about as capable."
    }

    private fun slowerNote(alt: Candidate, pick: Candidate?): String = when {
        pick == null -> ""
        alt.estimate!!.speedClass.atLeast(SpeedClass.USABLE) -> "Better answers, still a usable speed."
        else -> "Better answers, but you will wait for them."
    }

    companion object {
        fun gb(b: Long) = "%.1f GB".format(b / 1e9)

        /** "an 8B", "an 11B", "a 7B": numbers are read aloud. */
        private fun article(size: String) = if (size.startsWith("8") || size.startsWith("11") || size.startsWith("18")) "an" else "a"
    }
}
