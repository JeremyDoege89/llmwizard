package dev.dreamz.llmwizard.core.ollama

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** A measured result, as opposed to the recommender's estimate (SPEC.md rule 7). */
@Serializable
data class BenchmarkResult(
    val model: String,
    val tokensPerSecond: Double,
    val loadSeconds: Double,
    val promptTokensPerSecond: Double?,
    val generatedTokens: Int,
    val at: String,
)

object Benchmark {
    /** Fixed so that results are comparable between runs and machines. */
    const val PROMPT = "Explain in about one hundred words why the sky is blue. Plain prose, no lists."

    suspend fun run(client: OllamaClient, model: String, now: () -> String = { java.time.Instant.now().toString() }): BenchmarkResult {
        val r = client.generate(model, PROMPT, mapOf("num_predict" to JsonPrimitive(128), "temperature" to JsonPrimitive(0), "seed" to JsonPrimitive(42)))
        r.error?.let { throw OllamaException(it) }
        val evalCount = r.evalCount ?: throw OllamaException("Ollama did not report timings")
        val evalNs = r.evalDuration ?: throw OllamaException("Ollama did not report timings")
        return BenchmarkResult(
            model = model,
            tokensPerSecond = evalCount / (evalNs / 1e9),
            loadSeconds = (r.loadDuration ?: 0) / 1e9,
            promptTokensPerSecond = if (r.promptEvalCount != null && (r.promptEvalDuration ?: 0) > 0) r.promptEvalCount / (r.promptEvalDuration!! / 1e9) else null,
            generatedTokens = evalCount,
            at = now(),
        )
    }
}
