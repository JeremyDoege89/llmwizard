package dev.dreamz.llmwizard.core.config

import dev.dreamz.llmwizard.core.shell.Shell
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A tuned copy of an installed model (BUILD_PLAN E2.1): Ollama's Modelfile settings, held as data.
 *
 * It reaches the server through Ollama's `/api/create` (`from` + `system` + `parameters`), not a
 * shell script, so the free-text system prompt never passes through a shell. [toModelfile] is the
 * same thing in Modelfile form, shown to the user before anything is created. Undo is deleting
 * [customTag], which the server screen already does.
 */
@Serializable
data class ModelConfig(
    val baseModel: String,
    val customTag: String,
    val systemPrompt: String? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val repeatPenalty: Double? = null,
    val contextLength: Int? = null,
    val seed: Int? = null,
    /** Layers offloaded to a graphics card; 0 keeps everything on the processor. */
    val numGpu: Int? = null,
    val mirostat: Int? = null,
    val mirostatTau: Double? = null,
    val mirostatEta: Double? = null,
    val stopTokens: List<String>? = null,
) {
    /** Problems in plain words; empty when the config can be sent. */
    fun problems(): List<String> = buildList {
        if (runCatching { Shell.modelTag(baseModel) }.isFailure) add("The base model name is not valid.")
        if (runCatching { Shell.modelTag(customTag) }.isFailure) add("The new name may use lowercase letters, digits, '.', '_', '-' and one ':'.")
        if (customTag == baseModel) add("The new name must differ from the base model, or the base would be overwritten.")
        systemPrompt?.let { if (it.length > MAX_SYSTEM_PROMPT) add("The system prompt is longer than $MAX_SYSTEM_PROMPT characters.") }
        range("Temperature", temperature, 0.0, 2.0)
        range("Top-p", topP, 0.0, 1.0)
        range("Top-k", topK?.toDouble(), 1.0, 100.0)
        range("Repeat penalty", repeatPenalty, 0.0, 2.0)
        range("Context length", contextLength?.toDouble(), 512.0, 262_144.0)
        range("GPU layers", numGpu?.toDouble(), 0.0, 999.0)
        if (mirostat != null && mirostat !in 0..2) add("Mirostat must be 0 (off), 1 or 2.")
        range("Mirostat tau", mirostatTau, 0.0, 20.0)
        range("Mirostat eta", mirostatEta, 0.0, 1.0)
        stopTokens?.let { s ->
            if (s.size > 8) add("At most 8 stop sequences.")
            if (s.any { it.isEmpty() || it.length > 64 || '\n' in it }) add("Stop sequences must be one line, 1 to 64 characters.")
        }
    }

    private fun MutableList<String>.range(name: String, v: Double?, lo: Double, hi: Double) {
        if (v != null && (v.isNaN() || v < lo || v > hi)) add("$name must be between ${num(lo)} and ${num(hi)}.")
    }

    /** Ollama parameter names and values, only the ones that are set. */
    fun parameters(): JsonObject = buildJsonObject {
        temperature?.let { put("temperature", it) }
        topP?.let { put("top_p", it) }
        topK?.let { put("top_k", it) }
        repeatPenalty?.let { put("repeat_penalty", it) }
        contextLength?.let { put("num_ctx", it) }
        seed?.let { put("seed", it) }
        numGpu?.let { put("num_gpu", it) }
        mirostat?.let { put("mirostat", it) }
        mirostatTau?.let { put("mirostat_tau", it) }
        mirostatEta?.let { put("mirostat_eta", it) }
        stopTokens?.takeIf { it.isNotEmpty() }?.let { s -> putJsonArray("stop") { s.forEach { add(JsonPrimitive(it)) } } }
    }

    /** The `/api/create` body. Call only when [problems] is empty. */
    fun createBody(): JsonObject {
        val p = problems()
        require(p.isEmpty()) { p.joinToString(" ") }
        return buildJsonObject {
            put("model", customTag)
            put("from", baseModel)
            systemPrompt?.takeIf { it.isNotBlank() }?.let { put("system", it) }
            parameters().takeIf { it.isNotEmpty() }?.let { put("parameters", it) }
            put("stream", false)
        }
    }

    /** The same settings as a Modelfile, for the preview and for people who want to copy it. */
    fun toModelfile(): String = buildString {
        appendLine("FROM $baseModel")
        systemPrompt?.takeIf { it.isNotBlank() }?.let { appendLine("SYSTEM \"\"\"${it.replace("\"\"\"", "\\\"\\\"\\\"")}\"\"\"") }
        parameters().forEach { (k, v) ->
            if (k == "stop") stopTokens!!.forEach { appendLine("PARAMETER stop ${JsonPrimitive(it)}") }
            else appendLine("PARAMETER $k $v")
        }
    }.trimEnd()

    companion object {
        const val MAX_SYSTEM_PROMPT = 16_000
        private fun num(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }
}
