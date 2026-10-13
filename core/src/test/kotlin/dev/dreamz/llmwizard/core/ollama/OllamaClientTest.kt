package dev.dreamz.llmwizard.core.ollama

import dev.dreamz.llmwizard.core.ssh.Auth
import dev.dreamz.llmwizard.core.ssh.HostKeyPolicy
import dev.dreamz.llmwizard.core.ssh.MemoryHostKeyStore
import dev.dreamz.llmwizard.core.ssh.SshjTransport
import dev.dreamz.llmwizard.core.ssh.TestSshServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OllamaClientTest {
    private val ssh = TestSshServer()
    private val ollama = MockWebServer()

    @BeforeEach fun start() = ollama.start()
    @AfterEach fun stop() { ollama.close(); ssh.close() }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    private fun <T> withClient(block: suspend (OllamaClient) -> T): T = runBlocking {
        SshjTransport().connect(ssh.target(), Auth.Password(ssh.password.toCharArray()), HostKeyPolicy(MemoryHostKeyStore(), true)).use { s ->
            val client = OllamaClient(TunnelSocketFactory({ s }, "127.0.0.1", ollama.port))
            try { block(client) } finally { client.close() }
        }
    }

    @Test fun `version and tags travel through the SSH tunnel`() {
        ollama.enqueue(json("""{"version":"0.40.2"}"""))
        ollama.enqueue(json("""{"models":[{"name":"qwen3:0.6b","size":522653277,"details":{"parameter_size":"751.63M","quantization_level":"Q4_K_M","family":"qwen3"},"new_field":1}]}"""))
        withClient { c ->
            assertEquals("0.40.2", c.version())
            val m = c.tags().single()
            assertEquals("qwen3:0.6b", m.name)
            assertEquals("Q4_K_M", m.details?.quantizationLevel)
        }
        val req = ollama.takeRequest()
        assertEquals("/api/version", req.url.encodedPath)
        // Ollama refuses unexpected Host headers while bound to loopback.
        assertEquals("127.0.0.1:11434", req.headers["Host"])
    }

    @Test fun `streamed chat yields chunks and Ollama's own timing`() {
        val lines = listOf(
            """{"model":"m","message":{"role":"assistant","content":"Hel"},"done":false}""",
            """{"model":"m","message":{"role":"assistant","content":"lo"},"done":false}""",
            """{"model":"m","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","load_duration":2000000000,"eval_count":40,"eval_duration":4000000000}""",
        )
        ollama.enqueue(MockResponse.Builder().body(lines.joinToString("\n") + "\n").addHeader("Content-Type", "application/x-ndjson").build())
        val chunks = withClient { c -> c.chat(ChatRequest("m", listOf(ChatMessage("user", "hi")))).toList() }
        assertEquals("Hello", chunks.mapNotNull { it.message?.content }.joinToString(""))
        assertEquals(10.0, chunks.last().tokensPerSecond!!, 0.001)
        val body = ollama.takeRequest().body!!.utf8()
        assertTrue("\"stream\":true" in body, body)
    }

    @Test fun `tool calls are parsed from a non-streamed reply`() {
        ollama.enqueue(json("""{"model":"m","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"sms.send","arguments":{"to":"Mike","body":"running late"}}}]},"done":true}"""))
        val reply = withClient { c -> c.chatOnce(ChatRequest("m", listOf(ChatMessage("user", "text Mike I'm late")))) }
        val call = reply.message!!.toolCalls!!.single().function
        assertEquals("sms.send", call.name)
        assertEquals("\"Mike\"", call.arguments["to"].toString())
    }

    @Test fun `errors carry Ollama's message`() {
        ollama.enqueue(json("""{"error":"model 'nope' not found"}""", 404))
        val e = assertThrows<OllamaException> { withClient { c -> c.show("nope") } }
        assertEquals("model 'nope' not found", e.message)
        assertEquals(404, e.status)
    }

    @Test fun `benchmark reports measured tokens per second and load time`() {
        ollama.enqueue(json("""{"response":"...","done":true,"load_duration":3500000000,"prompt_eval_count":20,"prompt_eval_duration":1000000000,"eval_count":128,"eval_duration":16000000000}"""))
        val r = withClient { c -> Benchmark.run(c, "m") }
        assertEquals(8.0, r.tokensPerSecond, 0.001)
        assertEquals(3.5, r.loadSeconds, 0.001)
        assertEquals(20.0, r.promptTokensPerSecond!!, 0.001)
    }
}

class OllamaCreateTest {
    private val ssh = TestSshServer()
    private val ollama = MockWebServer()

    @BeforeEach fun start() = ollama.start()
    @AfterEach fun stop() { ollama.close(); ssh.close() }

    @Test fun `create posts the config and surfaces Ollama's error`() = runBlocking {
        ollama.enqueue(MockResponse.Builder().code(200).body("""{"status":"success"}""").build())
        ollama.enqueue(MockResponse.Builder().code(404).body("""{"error":"model 'nope' not found"}""").build())
        SshjTransport().connect(ssh.target(), Auth.Password(ssh.password.toCharArray()), HostKeyPolicy(MemoryHostKeyStore(), true)).use { s ->
            val c = OllamaClient(TunnelSocketFactory({ s }, "127.0.0.1", ollama.port))
            try {
                c.create(dev.dreamz.llmwizard.core.config.ModelConfig("qwen3:0.6b", "tiny-terse", systemPrompt = "Be brief.", temperature = 0.2))
                val req = ollama.takeRequest()
                assertEquals("/api/create", req.url.encodedPath)
                val body = req.body!!.utf8()
                assertTrue("\"from\":\"qwen3:0.6b\"" in body && "\"system\":\"Be brief.\"" in body && "\"temperature\":0.2" in body, body)
                val e = assertThrows<OllamaException> { runBlocking { c.create(dev.dreamz.llmwizard.core.config.ModelConfig("nope", "nope-2")) } }
                assertEquals("model 'nope' not found", e.message)
            } finally { c.close() }
        }
    }
}
