package dev.dreamz.llmwizard.core.knowledge

/**
 * The bundled articles plus search (BUILD_PLAN E5.1). Each file in `knowledge/` starts with a
 * header between `---` lines (`key: value`), then the Markdown body. `index.txt` lists the files,
 * because a jar on Android cannot list its own resource directories.
 */
class KnowledgeBase(val articles: List<Article>) {
    private val byId = articles.associateBy { it.id }

    operator fun get(id: String): Article? = byId[id]

    fun byCategory(c: ArticleCategory) = articles.filter { it.category == c }

    /** Title hits rank above tag hits, which rank above body hits. Every word must match somewhere. */
    fun search(query: String): List<Article> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return articles
        return articles.mapNotNull { a ->
            val title = a.title.lowercase(); val body = a.markdown.lowercase(); val tags = a.tags.joinToString(" ")
            if (!words.all { it in title || it in tags || it in body || it in a.summary.lowercase() }) return@mapNotNull null
            a to words.sumOf { w -> (if (w in title) 10 else 0) + (if (w in tags) 5 else 0) + body.windowed(w.length).count { it == w }.coerceAtMost(5) }
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** Related articles that exist, in the order the article lists them. */
    fun related(a: Article) = a.relatedArticles.mapNotNull { byId[it] }

    companion object {
        private const val ROOT = "/llmwizard/knowledge/"

        fun bundled(): KnowledgeBase {
            val index = resource("index.txt") ?: return KnowledgeBase(emptyList())
            return KnowledgeBase(index.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { name -> parse(requireNotNull(resource(name)) { "knowledge/$name is listed in index.txt but missing" }) })
        }

        private fun resource(name: String) = KnowledgeBase::class.java.getResourceAsStream(ROOT + name)?.readBytes()?.toString(Charsets.UTF_8)

        fun parse(text: String): Article {
            val t = text.replace("\r\n", "\n")
            require(t.startsWith("---\n")) { "article must start with a --- header" }
            val end = t.indexOf("\n---\n", 4)
            require(end > 0) { "article header is not closed" }
            val head = t.substring(4, end).lines().filter { it.isNotBlank() }
                .associate { line -> line.substringBefore(':').trim() to line.substringAfter(':', "").trim() }
            val body = t.substring(end + 5).trim()
            fun need(k: String) = requireNotNull(head[k]?.takeIf { it.isNotEmpty() }) { "article header lacks '$k'" }
            fun list(k: String) = head[k].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val words = body.split(Regex("\\s+")).count { it.isNotBlank() }
            return Article(
                id = need("id"), title = need("title"),
                category = ArticleCategory.valueOf(need("category")),
                tags = list("tags").toSet(),
                difficulty = Difficulty.valueOf(need("difficulty")),
                readMinutes = maxOf(1, (words + 199) / 200),
                summary = need("summary"), markdown = body,
                relatedArticles = list("related"),
                version = head["version"]?.toIntOrNull() ?: 1,
            )
        }
    }
}
