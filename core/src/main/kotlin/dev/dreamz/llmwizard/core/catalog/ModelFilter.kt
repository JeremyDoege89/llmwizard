package dev.dreamz.llmwizard.core.catalog

import kotlinx.serialization.Serializable

/**
 * Narrows what the recommender may pick (BUILD_PLAN E1.3). Fit is still checked first; the filter
 * only decides which fitting models are offered. The default leaves uncensored models out, so the
 * plain setup flow recommends exactly what it did before they were added.
 */
@Serializable
data class ModelFilter(
    /** Keep models carrying any of these tags; null means any tag. */
    val tags: Set<String>? = null,
    /** false: leave uncensored models out. true: only uncensored models. null: both. */
    val uncensored: Boolean? = false,
    val minQuality: Int? = null,
    /** true: tool calling required. false: only models without it. null: either. */
    val supportsTools: Boolean? = null,
    val maxDownloadBytes: Long? = null,
    /** Matches name, tag or any catalog tag, ignoring case. */
    val query: String? = null,
) {
    fun matches(e: ModelEntry): Boolean =
        (tags == null || tags.any { it in e.tags }) &&
            (uncensored == null || e.uncensored == uncensored) &&
            (minQuality == null || e.quality >= minQuality) &&
            (supportsTools == null || e.supportsTools == supportsTools) &&
            (maxDownloadBytes == null || e.downloadBytes <= maxDownloadBytes) &&
            (query.isNullOrBlank() || query.trim().let { q ->
                e.name.contains(q, ignoreCase = true) || e.ollamaTag.contains(q, ignoreCase = true) || e.tags.any { it.contains(q, ignoreCase = true) }
            })

    companion object {
        val DEFAULT = ModelFilter()
        val EVERYTHING = ModelFilter(uncensored = null)
        val UNCENSORED = ModelFilter(uncensored = true)
    }
}
