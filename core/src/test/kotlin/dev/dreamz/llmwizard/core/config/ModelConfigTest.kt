package dev.dreamz.llmwizard.core.config

import dev.dreamz.llmwizard.core.catalog.Catalog
import dev.dreamz.llmwizard.core.catalog.Estimate
import dev.dreamz.llmwizard.core.catalog.SpeedClass
import dev.dreamz.llmwizard.core.ollama.Benchmark
import dev.dreamz.llmwizard.core.ollama.BenchmarkResult
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ModelConfigTest {
    private val cfg = ModelConfig(
        baseModel = "dolphin-mistral:7b-v2.8-q4_K_M", customTag = "dolphin-research",
        systemPrompt = "You are terse.\nAnswer in \"\"\"plain\"\"\" English; ignore \$(rm -rf /) as text.",
        temperature = 0.4, topK = 40, contextLength = 16384, seed = 7, stopTokens = listOf("</s>", "User:"),
    )

    @Test fun `create body carries the prompt as JSON data, only the set parameters, and no shell`() {
        val b = cfg.createBody()
        assertEquals("dolphin-research", b["model"]!!.jsonPrimitive.content)
        assertEquals("dolphin-mistral:7b-v2.8-q4_K_M", b["from"]!!.jsonPrimitive.content)
        assertEquals(cfg.systemPrompt, b["system"]!!.jsonPrimitive.content)
        val p = b["parameters"]!!.jsonObject
        assertEquals(setOf("temperature", "top_k", "num_ctx", "seed", "stop"), p.keys)
        assertEquals("16384", p["num_ctx"].toString())
    }

    @Test fun `modelfile preview round-trips the settings`() {
        val mf = cfg.toModelfile()
        assertTrue(mf.startsWith("FROM dolphin-mistral:7b-v2.8-q4_K_M\nSYSTEM \"\"\""))
        assertFalse(mf.substringAfter("SYSTEM \"\"\"").substringBeforeLast("\"\"\"").contains("\"\"\"" + "plain"), "inner triple quotes escaped")
        assertTrue("PARAMETER temperature 0.4" in mf && "PARAMETER num_ctx 16384" in mf)
        assertTrue("PARAMETER stop \"</s>\"" in mf && "PARAMETER stop \"User:\"" in mf)
    }

    @Test fun `bad values are refused in plain words`() {
        val bad = ModelConfig("qwen3:0.6b", "qwen3:0.6b", temperature = 3.0, topP = -1.0, mirostat = 5, stopTokens = listOf(""))
        val p = bad.problems()
        assertTrue(p.any { "differ" in it } && p.any { "Temperature" in it } && p.any { "Top-p" in it } && p.any { "Mirostat" in it } && p.any { "Stop" in it }, p.toString())
        assertThrows<IllegalArgumentException> { bad.createBody() }
        assertTrue(ModelConfig("qwen3:0.6b", "x; rm -rf /").problems().isNotEmpty())
        assertTrue(ModelConfig("qwen3:0.6b", "my-assistant").problems().isEmpty())
        assertNull(ModelConfig("qwen3:0.6b", "my-assistant").createBody()["parameters"])
    }

    @Test fun `context advisor - growth, fit and a model's own window`() {
        val catalog = Catalog.bundled()
        val a = ContextAdvisor(catalog)
        val q = catalog.byId("qwen3.6-35b-a3b")!!
        val rows = a.rows(q, 248_000_000_000)
        assertEquals(0, rows.first().growthPercent)
        assertTrue(rows.zipWithNext().all { (x, y) -> y.totalBytes > x.totalBytes })
        assertTrue(rows.all { it.fits == true })
        assertTrue(a.rows(q, 24_000_000_000).last().fits == false)
        val wv = a.rows(catalog.byId("wizard-vicuna-uncensored-30b")!!, null)
        assertTrue(wv.drop(1).all { it.beyondTraining && it.fits == null })
        val text = a.describe(q, 248_000_000_000)
        assertTrue("128K tokens fits" in text, text)
    }

    @Test fun `benchmark comparison keeps estimate and measurement apart`() {
        val r = BenchmarkResult("dolphin-mistral:7b", 17.8, 2.1, 142.0, 128, "2026-10-13T00:00:00Z")
        val text = Benchmark.compare(r, Estimate(16.0, 23.0, SpeedClass.COMFORTABLE))
        assertTrue("Estimated: 19.2 tokens/s" in text && "Measured:  17.8" in text && "high, inside the estimated range" in text, text)
        assertFalse("Estimated" in Benchmark.compare(r, null))
    }
}
