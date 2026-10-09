package dev.dreamz.llmwizard.core.recipe

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `~/.local/state/llmwizard/installed.json` on the target: everything the app created there.
 * Uninstall removes only what is listed, so an Ollama the user installed themselves is never
 * touched; a reinstalled app or a second phone reads it to recognise an existing setup.
 */
@Serializable
data class Manifest(
    val version: Int = 1,
    val created: List<ManifestEntry> = emptyList(),
    /** Facts steps reported (ollama.version, ollama.sha256, …) and settings last applied. */
    val settings: Map<String, String> = emptyMap(),
    val history: List<HistoryEntry> = emptyList(),
) {
    fun has(kind: String, name: String) = created.any { it.kind == kind && it.name == name }
    fun names(kind: String) = created.filter { it.kind == kind }.map { it.name }

    fun plus(entries: List<ManifestEntry>): Manifest =
        copy(created = created + entries.filterNot { e -> has(e.kind, e.name) })

    fun minus(kind: String, name: String) = copy(created = created.filterNot { it.kind == kind && it.name == name })

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(text: String): Manifest = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class ManifestEntry(val kind: String, val name: String, val by: String, val at: String)

@Serializable
data class HistoryEntry(val recipe: String, val recipeVersion: Int, val at: String, val result: String)
