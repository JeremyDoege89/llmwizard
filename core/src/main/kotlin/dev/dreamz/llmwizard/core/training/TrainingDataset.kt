package dev.dreamz.llmwizard.core.training

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class DatasetFormat(val label: String) {
    CHAT_JSONL("Chat (JSONL with messages)"),
    INSTRUCT_JSONL("Instruction (JSONL with instruction/input/output)"),
    PLAIN_TEXT("Plain text"),
}

/** A dataset uploaded to the server (BUILD_PLAN E3.3); `meta.json` next to `data.jsonl`. */
@Serializable
data class TrainingDataset(
    val id: String,
    val name: String,
    val format: DatasetFormat,
    val path: String,
    val sampleCount: Int,
    val sizeBytes: Long,
    /** Rough token count (characters / 4), used by the time estimate. */
    val approxTokens: Long,
    val uploadedAt: String,
    val validated: Boolean,
)

/**
 * Checks a dataset on the phone before it is uploaded: detects the format, counts samples,
 * reports bad lines by number and normalizes everything to JSONL the training script reads
 * (`{"messages": …}` or `{"text": …}`). Instruction rows become a user/assistant exchange.
 */
object DatasetValidator {
    private val json = Json { ignoreUnknownKeys = true }
    private val ROLES = setOf("system", "user", "assistant")

    data class Result(
        val format: DatasetFormat?,
        val samples: Int,
        val approxTokens: Long,
        /** Plain-English problems with line numbers; empty when it can be used. */
        val problems: List<String>,
        /** Up to three samples, as text, for the preview. */
        val preview: List<String>,
        /** What gets uploaded: one normalized JSON object per line. */
        val normalized: String,
    ) {
        val ok: Boolean get() = format != null && problems.isEmpty() && samples > 0
    }

    fun detect(text: String): DatasetFormat {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return DatasetFormat.PLAIN_TEXT
        if (!first.startsWith("{")) return DatasetFormat.PLAIN_TEXT
        val o = runCatching { json.parseToJsonElement(first).jsonObject }.getOrNull() ?: return DatasetFormat.PLAIN_TEXT
        return when {
            "messages" in o -> DatasetFormat.CHAT_JSONL
            "instruction" in o || "output" in o -> DatasetFormat.INSTRUCT_JSONL
            else -> DatasetFormat.PLAIN_TEXT
        }
    }

    fun validate(text: String, format: DatasetFormat = detect(text), maxProblems: Int = 10): Result {
        val problems = mutableListOf<String>()
        val out = StringBuilder()
        val preview = mutableListOf<String>()
        var samples = 0
        var chars = 0L
        fun problem(s: String) { if (problems.size < maxProblems) problems += s else if (problems.size == maxProblems) problems += "…and more." }
        fun emit(o: JsonObject, show: String, size: Int) {
            out.append(o.toString()).append('\n'); samples++; chars += size
            if (preview.size < 3) preview += show.take(600)
        }
        when (format) {
            DatasetFormat.PLAIN_TEXT -> {
                // One document per paragraph (blank-line separated); single lines are their own documents when there are no blank lines.
                val docs = if (Regex("\\n\\s*\\n").containsMatchIn(text)) text.split(Regex("\\n\\s*\\n")) else text.lines()
                docs.map { it.trim() }.filter { it.isNotEmpty() }.forEach { d -> emit(JsonObject(mapOf("text" to JsonPrimitive(d))), d, d.length) }
            }
            else -> text.lineSequence().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachIndexed
                val n = i + 1
                val o = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                if (o == null) { problem("Line $n is not a JSON object."); return@forEachIndexed }
                if (format == DatasetFormat.CHAT_JSONL) {
                    val msgs = o["messages"] as? JsonArray
                    if (msgs == null || msgs.isEmpty()) { problem("Line $n has no \"messages\" list."); return@forEachIndexed }
                    val parsed = msgs.map { m -> runCatching { m.jsonObject["role"]!!.jsonPrimitive.content to m.jsonObject["content"]!!.jsonPrimitive.content }.getOrNull() }
                    if (parsed.any { it == null }) { problem("Line $n: every message needs \"role\" and \"content\" text."); return@forEachIndexed }
                    val pairs = parsed.filterNotNull()
                    val roles = pairs.map { it.first }
                    if (roles.any { it !in ROLES }) { problem("Line $n: roles must be system, user or assistant."); return@forEachIndexed }
                    if ("assistant" !in roles) { problem("Line $n has no assistant reply to learn from."); return@forEachIndexed }
                    val clean = JsonObject(mapOf("messages" to JsonArray(pairs.map { (r, c) -> JsonObject(mapOf("role" to JsonPrimitive(r), "content" to JsonPrimitive(c))) })))
                    emit(clean, pairs.joinToString("\n") { (r, c) -> "$r: $c" }, pairs.sumOf { it.second.length })
                } else {
                    val instr = o["instruction"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
                    val input = o["input"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
                    val output = o["output"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
                    if (instr.isBlank() || output.isBlank()) { problem("Line $n needs non-empty \"instruction\" and \"output\"."); return@forEachIndexed }
                    val user = if (input.isBlank()) instr else "$instr\n\n$input"
                    val clean = JsonObject(mapOf("messages" to JsonArray(listOf(
                        JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive(user))),
                        JsonObject(mapOf("role" to JsonPrimitive("assistant"), "content" to JsonPrimitive(output))),
                    ))))
                    emit(clean, "user: $user\nassistant: $output", user.length + output.length)
                }
            }
        }
        if (samples == 0 && problems.isEmpty()) problems += "The file has no samples."
        return Result(format, samples, chars / 4, problems, preview, out.toString())
    }
}
