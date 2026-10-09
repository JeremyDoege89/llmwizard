package dev.dreamz.llmwizard.core.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Ollama's native API shapes (docs.ollama.com/api). Parsed with ignoreUnknownKeys: the API grows fields.

@Serializable data class VersionResponse(val version: String)

@Serializable data class TagsResponse(val models: List<LocalModel> = emptyList())

@Serializable
data class LocalModel(
    val name: String,
    val model: String? = null,
    val size: Long? = null,
    val digest: String? = null,
    @SerialName("modified_at") val modifiedAt: String? = null,
    val details: ModelDetails? = null,
)

@Serializable
data class ModelDetails(
    val family: String? = null,
    @SerialName("parameter_size") val parameterSize: String? = null,
    @SerialName("quantization_level") val quantizationLevel: String? = null,
    val format: String? = null,
)

@Serializable data class PsResponse(val models: List<RunningModel> = emptyList())

@Serializable
data class RunningModel(
    val name: String,
    val size: Long? = null,
    @SerialName("size_vram") val sizeVram: Long? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("context_length") val contextLength: Int? = null,
)

@Serializable
data class ShowResponse(
    val capabilities: List<String> = emptyList(),
    val details: ModelDetails? = null,
    val parameters: String? = null,
    val template: String? = null,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String = "",
    val thinking: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_name") val toolName: String? = null,
)

@Serializable data class ToolCall(val function: ToolCallFunction)
@Serializable data class ToolCallFunction(val name: String, val arguments: JsonObject = JsonObject(emptyMap()), val index: Int? = null)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val tools: List<JsonElement>? = null,
    val options: Map<String, JsonElement>? = null,
    @SerialName("keep_alive") val keepAlive: String? = null,
    val think: Boolean? = null,
)

/** One line of a streamed chat reply. The last one (done = true) carries the timings. */
@Serializable
data class ChatChunk(
    val model: String? = null,
    val message: ChatMessage? = null,
    val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,
    @SerialName("total_duration") val totalDuration: Long? = null,
    @SerialName("load_duration") val loadDuration: Long? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("prompt_eval_duration") val promptEvalDuration: Long? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("eval_duration") val evalDuration: Long? = null,
    val error: String? = null,
) {
    /** Generated tokens per second, measured by Ollama itself. */
    val tokensPerSecond: Double? get() = if (evalCount != null && evalDuration != null && evalDuration > 0) evalCount / (evalDuration / 1e9) else null
}

@Serializable
data class GenerateResponse(
    val response: String = "",
    val done: Boolean = false,
    @SerialName("load_duration") val loadDuration: Long? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("prompt_eval_duration") val promptEvalDuration: Long? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("eval_duration") val evalDuration: Long? = null,
    @SerialName("total_duration") val totalDuration: Long? = null,
    val error: String? = null,
)
