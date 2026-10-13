package dev.dreamz.llmwizard.core.training

import dev.dreamz.llmwizard.core.Fixtures
import dev.dreamz.llmwizard.core.catalog.Catalog
import dev.dreamz.llmwizard.core.remote.Remote
import dev.dreamz.llmwizard.core.ssh.Auth
import dev.dreamz.llmwizard.core.ssh.HostKeyPolicy
import dev.dreamz.llmwizard.core.ssh.MemoryHostKeyStore
import dev.dreamz.llmwizard.core.ssh.SshjTransport
import dev.dreamz.llmwizard.core.ssh.TestSshServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DatasetValidatorTest {
    @Test fun `chat jsonl is detected, counted and bad lines reported by number`() {
        val text = """
            {"messages":[{"role":"user","content":"hi"},{"role":"assistant","content":"hello"}]}
            not json
            {"messages":[{"role":"user","content":"no reply"}]}
            {"messages":[{"role":"wizard","content":"x"},{"role":"assistant","content":"y"}]}
            {"messages":[{"role":"system","content":"be nice"},{"role":"user","content":"a"},{"role":"assistant","content":"b"}]}
        """.trimIndent()
        val r = DatasetValidator.validate(text)
        assertEquals(DatasetFormat.CHAT_JSONL, r.format)
        assertEquals(2, r.samples)
        assertEquals(listOf("Line 2 is not a JSON object.", "Line 3 has no assistant reply to learn from.", "Line 4: roles must be system, user or assistant."), r.problems)
        assertFalse(r.ok)
    }

    @Test fun `instruction rows become a user and assistant exchange`() {
        val r = DatasetValidator.validate("""{"instruction":"Translate","input":"hola","output":"hello"}""" + "\n" + """{"instruction":"Say hi","output":"hi"}""")
        assertEquals(DatasetFormat.INSTRUCT_JSONL, r.format)
        assertTrue(r.ok, r.problems.toString())
        val first = r.normalized.lines().first()
        assertTrue("\"role\":\"user\",\"content\":\"Translate\\n\\nhola\"" in first, first)
        assertTrue("\"role\":\"assistant\",\"content\":\"hello\"" in first)
        assertEquals("user: Say hi\nassistant: hi", r.preview[1])
    }

    @Test fun `plain text splits on blank lines, or by line when there are none`() {
        assertEquals(2, DatasetValidator.validate("para one\nstill one\n\npara two").samples)
        assertEquals(3, DatasetValidator.validate("a\nb\nc").samples)
        assertEquals(DatasetFormat.PLAIN_TEXT, DatasetValidator.detect("{\"text\": 1}"))
        assertFalse(DatasetValidator.validate("   ").ok)
    }
}

class TrainingJobTest {
    private val ok = TrainingJob("job-1", "qwen3:0.6b", "Qwen/Qwen3-0.6B", "ds-1", outputName = "qwen3-mine:0.6b")

    @Test fun `defaults are valid and bad values are named`() {
        assertEquals(emptyList<String>(), ok.problems())
        val bad = ok.copy(hfRepo = "not a repo", outputName = "qwen3:0.6b", baseModel = "qwen3:0.6b", method = TrainingMethod.QLORA, loraRank = 7, epochs = 0, quantize = "q2")
        val p = bad.problems()
        assertTrue(listOf("Hugging Face", "own name", "QLoRA", "rank", "Epochs", "Quantization").all { k -> p.any { k in it } }, p.toString())
    }

    @Test fun `progress parsing tolerates half-written files and the job state reads plainly`() {
        assertNull(ProgressParser.parse(""))
        assertNull(ProgressParser.parse("{\"stage\":\"TRAI"))
        val p = ProgressParser.parse("""{"stage":"TRAINING","currentStep":30,"totalSteps":120,"loss":1.234,"estimatedRemainingSeconds":5400,"newField":1}""")!!
        assertEquals(0.25, p.fraction)
        val running = JobState(ok, p, alive = true)
        assertEquals(TrainingStatus.RUNNING, running.status)
        assertEquals("Training: step 30 of 120, loss 1.234, about 1.5 hours left", running.summary)
        assertEquals(TrainingStatus.FAILED, running.copy(alive = false).status)
        assertTrue("Resume" in running.copy(alive = false).summary)
        assertEquals(TrainingStatus.PENDING, JobState(ok, null, false).status)
        assertEquals(TrainingStatus.COMPLETED, JobState(ok, p.copy(stage = TrainingStage.DONE), false).status)
    }

    @Test fun `estimate scales with model size and says it is an estimate`() {
        val dl380p = Fixtures.profile("dl380p-hyperv-ubuntu2404")
        val catalog = Catalog.bundled()
        val ds = TrainingDataset("ds-1", "d", DatasetFormat.CHAT_JSONL, "/x", 1000, 1, 1000L * 400, "", true)
        val est = TrainingEstimator()
        val small = est.estimate(dl380p, catalog.byTag("qwen3:0.6b")!!, ds, ok)
        val big = est.estimate(dl380p, catalog.byId("dolphin-mistral-7b")!!, ds, ok)
        assertTrue(big.totalSeconds > small.totalSeconds * 5)
        assertTrue("(estimate)" in big.explanation && "RTX 3090" in big.gpuComparison, big.explanation)
        assertEquals(1_200_000L, big.trainedTokens)
        assertTrue(big.memoryBytes > 30e9, "7B in fp32 needs >30 GB")
        assertEquals("1.5 hours", TrainingEstimator.duration(5400))
        assertEquals("3 days", TrainingEstimator.duration(3 * 86_400))
    }

    @Test fun `every trainable catalog entry has a well-formed repository`() {
        val trainable = Catalog.bundled().models.filter { it.trainable }
        assertTrue(trainable.size >= 5)
        trainable.forEach { assertTrue(TrainingJob.HF_REPO.matches(it.hfRepo!!), it.id) }
    }
}

/** The job lifecycle against a real shell, with a stand-in for the training script's Python. */
class TrainingRemoteTest {
    private val ssh = TestSshServer()
    @TempDir lateinit var tmp: File

    @AfterEach fun stop() = ssh.close()

    private fun <T> withTraining(block: suspend (Training) -> T): T = runBlocking {
        SshjTransport().connect(ssh.target(), Auth.Password(ssh.password.toCharArray()), HostKeyPolicy(MemoryHostKeyStore(), true)).use { s ->
            block(Training(Remote(s, File(tmp, "state").absolutePath), File(tmp, "data").absolutePath))
        }
    }

    /** A fake interpreter: reports a few training steps, then DONE, unless asked to cancel. */
    private fun fakePython() {
        val bin = File(tmp, "data/training/venv/bin").apply { mkdirs() }
        File(bin, "python").apply {
            writeText(
                "#!/bin/sh\necho \$\$ > pid\nfor i in 1 2 3; do\n  if [ -f cancel ]; then printf '{\"stage\":\"CANCELLED\",\"message\":\"Stopped\"}' > progress.json; exit 0; fi\n" +
                    "  printf '{\"stage\":\"TRAINING\",\"currentStep\":%s,\"totalSteps\":3}' \$i > progress.json; sleep 0.3\ndone\n" +
                    "printf '{\"stage\":\"DONE\",\"message\":\"Ready\",\"ollamaModel\":\"mine\"}' > progress.json\nrm -f pid\n",
            )
            setExecutable(true)
        }
        File(tmp, "data/training/llama.cpp").mkdirs()
        File(tmp, "data/training/llama.cpp/convert_hf_to_gguf.py").writeText("")
        File(tmp, "data/training/llama.cpp/bin").mkdirs()
        File(tmp, "data/training/llama.cpp/bin/llama-quantize").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
    }

    private suspend fun waitFor(t: Training, id: String, s: TrainingStatus): JobState {
        repeat(60) { val st = t.state(id)!!; if (st.status == s) return st; delay(100) }
        return t.state(id)!!
    }

    @Test fun `upload, prepare, run detached, finish, list, delete`() {
        fakePython()
        withTraining { t ->
            assertTrue(t.stackInstalled())
            val checked = DatasetValidator.validate("""{"instruction":"a","output":"b"}""")
            val ds = t.uploadDataset("tiny", checked)
            assertEquals(listOf(ds), t.datasets())
            assertTrue("\"assistant\"" in t.datasetHead(ds.id))
            val job = t.prepare(TrainingJob("job-a", "qwen3:0.6b", "Qwen/Qwen3-0.6B", ds.id, outputName = "mine"))
            assertEquals(TrainingStatus.PENDING, t.state(job.id)!!.status)
            assertTrue(File(tmp, "data/training/jobs/job-a/train.py").readText().contains("config.json"))
            t.start(job.id)
            val done = waitFor(t, job.id, TrainingStatus.COMPLETED)
            assertEquals(TrainingStatus.COMPLETED, done.status)
            assertEquals("mine", done.progress!!.ollamaModel)
            assertEquals(listOf("job-a"), t.jobs().map { it.job.id })
            t.delete(job.id); t.deleteDataset(ds.id)
            assertTrue(t.jobs().isEmpty() && t.datasets().isEmpty())
            assertNull(t.state("job-a"))
        }
    }

    @Test fun `cancel stops the job and resume starts it again`() {
        fakePython()
        withTraining { t ->
            val ds = t.uploadDataset("tiny", DatasetValidator.validate("x"))
            val job = t.prepare(TrainingJob("job-b", "qwen3:0.6b", "Qwen/Qwen3-0.6B", ds.id, outputName = "mine"))
            t.start(job.id)
            t.cancel(job.id)
            assertEquals(TrainingStatus.CANCELLED, waitFor(t, job.id, TrainingStatus.CANCELLED).status)
            t.start(job.id)
            assertEquals(TrainingStatus.COMPLETED, waitFor(t, job.id, TrainingStatus.COMPLETED).status)
        }
    }

    @Test fun `refuses an invalid job and a missing stack`() {
        withTraining { t ->
            assertFalse(t.stackInstalled())
            assertThrows<IllegalArgumentException> { runBlocking { t.prepare(TrainingJob("job c", "qwen3:0.6b", "x", "ds", outputName = "m")) } }
            val job = t.prepare(TrainingJob("job-c", "qwen3:0.6b", "Qwen/Qwen3-0.6B", "ds-1", outputName = "m"))
            val e = assertThrows<IllegalStateException> { runBlocking { t.start(job.id) } }
            assertTrue("not installed" in e.message!!)
        }
    }
}
