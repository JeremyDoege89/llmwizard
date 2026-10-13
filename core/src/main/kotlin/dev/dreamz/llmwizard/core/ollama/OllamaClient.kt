package dev.dreamz.llmwizard.core.ollama

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class OllamaException(message: String, val status: Int? = null) : IOException(message)

/**
 * Ollama's native API (PLAN.md 5.8). Built over a [SocketFactory] so the app and CLI can
 * hand it SSH tunnel sockets; tests hand it plain sockets to a mock server.
 */
class OllamaClient(socketFactory: SocketFactory, private val base: HttpUrl = "http://127.0.0.1:11434".toHttpUrl(), tunnel: Boolean = true) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(socketFactory)
        .proxy(Proxy.NO_PROXY)
        .protocols(listOf(Protocol.HTTP_1_1))
        .apply { if (tunnel) dns(LoopbackDns) }
        .connectTimeout(30, TimeUnit.SECONDS)
        // A large model loading from disk on a CPU-only machine can take minutes before the first byte.
        .readTimeout(15, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    private object LoopbackDns : Dns {
        override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String) = base.newBuilder().addPathSegments(path.trimStart('/')).build()

    private suspend inline fun <reified T> call(request: Request): T = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) throw OllamaException(errorText(body) ?: "HTTP ${resp.code}", resp.code)
            json.decodeFromString<T>(body)
        }
    }

    private fun errorText(body: String) = runCatching { json.parseToJsonElement(body).let { (it as? kotlinx.serialization.json.JsonObject)?.get("error")?.toString()?.trim('"') } }.getOrNull()

    private fun post(path: String, body: String) = Request.Builder().url(url(path)).post(body.toRequestBody(jsonType)).build()
    private fun get(path: String) = Request.Builder().url(url(path)).get().build()
    private fun modelBody(model: String, extra: Map<String, JsonPrimitive> = emptyMap()) =
        buildJsonObject { put("model", model); extra.forEach { (k, v) -> put(k, v) } }.toString()

    suspend fun version(): String = call<VersionResponse>(get("api/version")).version
    suspend fun tags(): List<LocalModel> = call<TagsResponse>(get("api/tags")).models
    suspend fun ps(): List<RunningModel> = call<PsResponse>(get("api/ps")).models
    suspend fun show(model: String): ShowResponse = call(post("api/show", modelBody(model)))

    suspend fun delete(model: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("api/delete")).delete(modelBody(model).toRequestBody(jsonType)).build()
        http.newCall(req).execute().use { if (!it.isSuccessful) throw OllamaException(errorText(it.body.string()) ?: "HTTP ${it.code}", it.code) }
    }

    /** Makes [config]'s tuned copy of a model (`/api/create` with `from`); replaces an earlier copy of the same name. */
    suspend fun create(config: dev.dreamz.llmwizard.core.config.ModelConfig) {
        val resp = call<CreateResponse>(post("api/create", config.createBody().toString()))
        resp.error?.let { throw OllamaException(it) }
    }

    /** Unloads a model from memory (a generate call with keep_alive 0). */
    suspend fun unload(model: String) {
        call<GenerateResponse>(post("api/generate", modelBody(model, mapOf("keep_alive" to JsonPrimitive(0), "stream" to JsonPrimitive(false)))))
    }

    suspend fun generate(model: String, prompt: String, options: Map<String, JsonPrimitive> = emptyMap()): GenerateResponse {
        val body = buildJsonObject {
            put("model", model); put("prompt", prompt); put("stream", false)
            put("options", buildJsonObject { options.forEach { (k, v) -> put(k, v) } })
        }.toString()
        return call(post("api/generate", body))
    }

    /** Non-streamed chat, used for tool proposals. */
    suspend fun chatOnce(request: ChatRequest): ChatChunk =
        call(post("api/chat", json.encodeToString(ChatRequest.serializer(), request.copy(stream = false))))

    /** Streamed chat: one [ChatChunk] per line, the last with timings. Cancelling the flow cancels the call. */
    fun chat(request: ChatRequest): Flow<ChatChunk> = flow {
        val call = http.newCall(post("api/chat", json.encodeToString(ChatRequest.serializer(), request.copy(stream = true))))
        val resp = call.execute()
        try {
            if (!resp.isSuccessful) throw OllamaException(errorText(resp.body.string()) ?: "HTTP ${resp.code}", resp.code)
            val source = resp.body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val chunk = json.decodeFromString(ChatChunk.serializer(), line)
                chunk.error?.let { throw OllamaException(it) }
                emit(chunk)
                if (chunk.done) break
            }
        } finally {
            resp.close()
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
