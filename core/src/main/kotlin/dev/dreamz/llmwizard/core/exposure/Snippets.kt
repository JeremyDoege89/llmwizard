package dev.dreamz.llmwizard.core.exposure

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Ready-made config for other apps (PLAN.md 5.9). Data, because those apps change their formats. */
@Serializable
data class Snippet(val id: String, val title: String, val text: String) {
    fun render(base: String, model: String) = text.replace("{{base}}", base).replace("{{model}}", model)
}

@Serializable
data class SnippetBundle(val version: Int, val note: String = "", val snippets: List<Snippet>) {
    companion object {
        fun bundled(): SnippetBundle = Json { ignoreUnknownKeys = true }.decodeFromString(serializer(),
            requireNotNull(SnippetBundle::class.java.getResourceAsStream("/llmwizard/catalog/snippets.json")).readBytes().toString(Charsets.UTF_8))
    }
}
