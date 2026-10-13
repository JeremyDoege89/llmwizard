package dev.dreamz.llmwizard.core.config

import dev.dreamz.llmwizard.core.catalog.Catalog
import dev.dreamz.llmwizard.core.catalog.ModelEntry
import dev.dreamz.llmwizard.core.catalog.Recommender
import dev.dreamz.llmwizard.core.catalog.Recommender.Companion.gb

/**
 * What a longer context costs in memory (BUILD_PLAN E2.3). The KV cache grows with every token
 * kept, so this uses the catalog's per-size KV rule, the same one the recommender's fit check uses.
 * All numbers are estimates.
 */
class ContextAdvisor(private val catalog: Catalog) {
    private val recommender = Recommender(catalog)

    data class Row(val contextLength: Int, val totalBytes: Long, val growthPercent: Int, val fits: Boolean?, val beyondTraining: Boolean)

    fun rows(e: ModelEntry, availableRamBytes: Long?, lengths: List<Int> = DEFAULT_LENGTHS): List<Row> {
        val base = total(e, lengths.first())
        val usable = availableRamBytes?.let { (it * catalog.constants.memoryHeadroomFraction).toLong() }
        return lengths.map { len ->
            val t = total(e, len)
            Row(len, t, ((t - base) * 100 / base).toInt(), usable?.let { t <= it }, e.recommendedContext?.let { len > it } ?: false)
        }
    }

    fun total(e: ModelEntry, ctx: Int): Long = e.downloadBytes + recommender.kvBytes(e, ctx) + catalog.constants.fixedOverheadBytes

    /** The table and one sentence of advice, as the plan's example shows it. */
    fun describe(e: ModelEntry, availableRamBytes: Long?, lengths: List<Int> = DEFAULT_LENGTHS): String = buildString {
        val rows = rows(e, availableRamBytes, lengths)
        appendLine("Context length sets how much of the conversation the model keeps in mind; it costs memory (the KV cache). Estimates:")
        rows.forEachIndexed { i, r ->
            val growth = if (i == 0) "" else " (+${r.growthPercent}%)"
            val flag = when {
                r.beyondTraining -> " — longer than ${e.name} was trained for, no benefit"
                r.fits == false -> " — does not fit in memory"
                else -> ""
            }
            appendLine("  ${r.contextLength / 1024}K tokens: about ${gb(r.totalBytes)}$growth$flag")
        }
        val best = rows.lastOrNull { it.fits != false && !it.beyondTraining }
        when {
            availableRamBytes == null -> append("Free memory is unknown, so fit could not be checked.")
            best == null -> append("Even the shortest setting is tight on this machine.")
            else -> append("With ${gb(availableRamBytes)} free, up to ${best.contextLength / 1024}K tokens fits.")
        }
    }

    companion object {
        val DEFAULT_LENGTHS = listOf(4096, 8192, 16384, 32768, 65536, 131072)
    }
}
