package dev.dreamz.llmwizard.core.knowledge

import kotlinx.serialization.Serializable

/** One knowledge-base article (BUILD_PLAN E5.1). Stored as Markdown with a small header in `knowledge/`. */
@Serializable
data class Article(
    val id: String,
    val title: String,
    val category: ArticleCategory,
    val tags: Set<String>,
    val difficulty: Difficulty,
    val readMinutes: Int,
    /** One or two sentences, shown on the article card. */
    val summary: String,
    val markdown: String,
    val relatedArticles: List<String> = emptyList(),
    val version: Int = 1,
)

@Serializable
enum class ArticleCategory(val label: String) {
    GETTING_STARTED("Getting started"),
    MODELS("Models"),
    TRAINING("Training"),
    OPTIMIZATION("Optimization"),
    AGENTS("Agents"),
    HARDWARE("Hardware"),
    SECURITY("Security & uncensoring"),
    REFERENCE("Reference"),
}

@Serializable
enum class Difficulty { BEGINNER, INTERMEDIATE, ADVANCED }
