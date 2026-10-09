package dev.dreamz.llmwizard.core.recipe

import dev.dreamz.llmwizard.core.shell.Shell
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** A target whose state is a set of finished step ids; skipIf/verify scripts are looked up by step. */
class FakeOps(
    val done: MutableSet<String> = mutableSetOf(),
    val failing: Set<String> = emptySet(),
    var manifest: Manifest? = null,
) : TargetOps {
    val ranSteps = mutableListOf<String>()
    val undone = mutableListOf<String>()
    val probes = mutableListOf<String>()

    override suspend fun builtins() = mapOf("J" to "/home/wiz/.local/state/llmwizard/jobs/x", "STATE" to "/home/wiz/.local/state/llmwizard",
        "CACHE" to "/home/wiz/.local/state/llmwizard/cache", "HOME" to "/home/wiz", "USER" to "wiz")

    override suspend fun probe(body: String, purpose: String): Int {
        probes += purpose
        val id = purpose.substringAfter(' ')
        return when {
            purpose.startsWith("skipIf") -> if (id in done) 0 else 1
            purpose.startsWith("verify") -> if (id in done) 0 else 1
            else -> 0
        }
    }

    override suspend fun probeOutput(body: String, purpose: String) = "k=v\n"

    override suspend fun run(recipeId: String, step: PlannedStep, onLog: suspend (String) -> Unit): Int {
        ranSteps += step.spec.id
        onLog("running ${step.spec.id}\n")
        if (step.spec.id in failing) return 3
        done += step.spec.id
        return 0
    }

    override suspend fun runUndo(recipeId: String, step: PlannedStep, script: String, onLog: suspend (String) -> Unit): Int {
        undone += step.spec.id; done -= step.spec.id; return 0
    }

    override suspend fun readManifest() = manifest
    override suspend fun writeManifest(manifest: Manifest) { this.manifest = manifest }
}

class RecipeEngineTest {
    private val install = Recipe.bundled("ollama-install")

    private fun engine(ops: TargetOps) = RecipeEngine(ops) { "2026-10-09T00:00:00Z" }

    @Test fun `fresh install runs every step in order and records what it created`() = runBlocking {
        val ops = FakeOps()
        val e = engine(ops)
        val values = e.resolve(install, mapOf("model" to "qwen3:0.6b"))
        val plan = e.plan(install, values, null)
        assertEquals(listOf("prereqs", "download", "unpack", "user", "unit", "settings", "start", "pull"), plan.map { it.spec.id })
        val r = e.execute(install, plan, values)
        assertTrue(r.ok, r.message)
        assertEquals(plan.map { it.spec.id }, ops.ranSteps)
        val m = ops.manifest!!
        assertTrue(m.has("file", "/usr/local/bin/ollama"))
        assertTrue(m.has("user", "ollama"))
        assertTrue(m.has("model", "qwen3:0.6b"))
        assertEquals("ok", m.history.last().result)
    }

    @Test fun `half-finished machine - finished steps are skipped and the rest runs`() = runBlocking {
        val ops = FakeOps(done = mutableSetOf("prereqs", "download", "unpack"))
        val e = engine(ops)
        val values = e.resolve(install, emptyMap())
        val plan = e.plan(install, values, null)
        assertEquals(listOf(true, true, true, false, false, false, false), plan.map { it.alreadyDone })
        e.execute(install, plan, values)
        assertEquals(listOf("user", "unit", "settings", "start"), ops.ranSteps)
        // Steps that were skipped did not run here, so their files are not ours to remove later.
        assertFalse(ops.manifest!!.has("file", "/usr/local/bin/ollama"))
    }

    @Test fun `second run changes nothing and says so`() = runBlocking {
        val ops = FakeOps(done = install.steps.map { it.id }.toMutableSet())
        val e = engine(ops)
        val values = e.resolve(install, mapOf("model" to "qwen3:0.6b"))
        val r = e.execute(install, e.plan(install, values, null), values)
        assertTrue(r.ok)
        assertFalse(r.changed)
        assertTrue(r.message.startsWith("Nothing to do"))
        assertTrue(ops.ranSteps.isEmpty())
    }

    @Test fun `failure midway stops, and undo reverses the steps that ran in reverse order`() = runBlocking {
        val ops = FakeOps(failing = setOf("settings"))
        val e = engine(ops)
        val values = e.resolve(install, emptyMap())
        val r = e.execute(install, e.plan(install, values, null), values)
        assertFalse(r.ok)
        assertEquals("settings", r.failedStep!!.spec.id)
        assertEquals(3, r.exitCode)
        assertEquals(listOf("prereqs", "download", "unpack", "user", "unit", "settings"), ops.ranSteps)
        assertTrue(ops.manifest!!.history.last().result.startsWith("failed at settings"))

        assertTrue(e.undo(install, r.ran, values))
        // Only steps with an undo are reversed, newest first.
        assertEquals(listOf("unit", "user", "unpack"), ops.undone)
        assertFalse(ops.manifest!!.has("user", "ollama"))
        assertFalse(ops.manifest!!.has("file", "/usr/local/bin/ollama"))
    }

    @Test fun `the pull step only applies when a model was chosen`() = runBlocking {
        val e = engine(FakeOps())
        assertFalse(e.plan(install, e.resolve(install, emptyMap()), null).any { it.spec.id == "pull" })
    }

    @Test fun `uninstall touches only what the manifest says the app created`() = runBlocking {
        val uninstall = Recipe.bundled("ollama-uninstall")
        val e = engine(FakeOps())
        // Adopted machine: nothing created by us.
        val adopted = e.plan(uninstall, e.resolve(uninstall, emptyMap()), Manifest())
        assertEquals(listOf("cache"), adopted.map { it.spec.id })
        // Our own install.
        val ours = Manifest(created = listOf("unit:/etc/systemd/system/ollama.service", "dropin:/etc/systemd/system/ollama.service.d/10-llmwizard.conf",
            "file:/usr/local/bin/ollama", "user:ollama", "dir:/usr/share/ollama").map {
            val (k, n) = it.split(':', limit = 2); ManifestEntry(k, n, "ollama-install", "t")
        })
        val keep = e.plan(uninstall, e.resolve(uninstall, emptyMap()), ours).map { it.spec.id }
        assertEquals(listOf("stop", "dropin", "unit", "binary", "user", "cache"), keep)
        val delete = e.plan(uninstall, e.resolve(uninstall, mapOf("deleteModels" to "yes")), ours).map { it.spec.id }
        assertTrue("models" in delete)
    }

    @Test fun `uninstall drops what it removed from the manifest`() = runBlocking {
        val uninstall = Recipe.bundled("ollama-uninstall")
        val m = Manifest(created = listOf(ManifestEntry("file", "/usr/local/bin/ollama", "ollama-install", "t"),
            ManifestEntry("dir", "/usr/local/lib/ollama", "ollama-install", "t")))
        val ops = FakeOps(manifest = m, done = mutableSetOf("binary"))
        val e = engine(ops)
        val values = e.resolve(uninstall, emptyMap())
        e.execute(uninstall, e.plan(uninstall, values, m), values)
        assertTrue(ops.manifest!!.created.isEmpty(), ops.manifest!!.created.toString())
    }

    @Test fun `values enter scripts only as quoted assignments`() = runBlocking {
        val e = engine(FakeOps())
        val values = e.resolve(install, mapOf("model" to "qwen3:0.6b"))
        val step = e.plan(install, values, null).first { it.spec.id == "pull" }
        assertTrue(step.script.contains("P_model='qwen3:0.6b'\n"))
        assertTrue(step.script.startsWith(Shell.PREAMBLE))
    }

    @Test fun `injection attempts are rejected before any script is built`() {
        runBlocking {
            val e = engine(FakeOps())
            for (bad in listOf("qwen3; rm -rf /", "x'\$(id)'", "a b", "../../etc", "\$(reboot)", "qwen3:0.6b\nreboot")) {
                assertThrows<IllegalArgumentException> { runBlocking { e.resolve(install, mapOf("model" to bad)) } }
            }
            assertThrows<IllegalArgumentException> { runBlocking { e.resolve(install, mapOf("bind" to "0.0.0.0:11434; id")) } }
            assertThrows<IllegalArgumentException> { runBlocking { e.resolve(install, mapOf("nope" to "1")) } }
            val key = Recipe.bundled("authorized-key-add")
            assertThrows<IllegalArgumentException> { runBlocking { e.resolve(key, mapOf("publicKey" to "ssh-ed25519 AAAA x\nssh-rsa evil")) } }
        }
    }

    @Test fun `every script in every bundled recipe is valid POSIX sh`() = runBlocking {
        for (id in Recipe.BUNDLED) {
            val r = Recipe.bundled(id)
            val e = engine(FakeOps())
            val given = r.params.filter { it.value.required }.mapValues { (_, spec) ->
                when (spec.type) { ParamType.TAG -> "qwen3:0.6b"; ParamType.ENUM -> spec.values!!.first(); ParamType.CIDR -> "192.168.1.0/24"; else -> "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ test@x" }
            }
            val values = e.resolve(r, given)
            val block = e.paramBlock(values)
            val scripts = r.steps.flatMap { s -> listOfNotNull(s.run, s.skipIf, s.verify, s.undo, s.facts).map { "${s.id}: " to it } } +
                r.preflight.map { "preflight ${it.id}: " to it.run }
            for ((where, body) in scripts) {
                assertFalse("{{" in body, "$id $where uses a placeholder inside a script")
                val f = File.createTempFile("step", ".sh")
                f.writeText(Shell.script(block + body))
                val p = ProcessBuilder("/bin/sh", "-n", f.path).redirectErrorStream(true).start()
                val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
                assertEquals(0, p.waitFor(), "$id $where: $out")
                f.delete()
            }
        }
    }
}

class EngineOrderingTest {
    @Test fun `a step done at plan time is re-checked once an earlier step has run`() = runBlocking {
        val configure = Recipe.bundled("ollama-configure")
        // At plan time no restart is pending ("restart" counts as done); writing settings makes it pending.
        val ops = object : TargetOps by FakeOps() {
            var pending = false
            val ran = mutableListOf<String>()
            override suspend fun probe(body: String, purpose: String) = when (purpose) {
                "skipIf settings" -> 1
                "skipIf restart" -> if (pending) 1 else 0
                else -> 0
            }
            override suspend fun run(recipeId: String, step: PlannedStep, onLog: suspend (String) -> Unit): Int {
                ran += step.spec.id; if (step.spec.id == "settings") pending = true else pending = false; return 0
            }
        }
        val e = RecipeEngine(ops)
        val values = e.resolve(configure, mapOf("contextLength" to "16384"))
        val plan = e.plan(configure, values, null)
        assertEquals(listOf(false, true), plan.map { it.alreadyDone })
        e.execute(configure, plan, values)
        assertEquals(listOf("settings", "restart"), ops.ran)
    }
}

class FinishedElsewhereTest {
    @Test fun `a background step that finished while the app was gone is recorded as ours`() = runBlocking {
        val pull = Recipe.bundled("model-pull")
        val base = FakeOps(done = mutableSetOf("pull"))
        val ops = object : TargetOps by base {
            override suspend fun collectFinished(recipeId: String, step: PlannedStep) = if (step.spec.id == "pull") 0 else null
        }
        val e = RecipeEngine(ops)
        val values = e.resolve(pull, mapOf("model" to "qwen3:4b"))
        val r = e.execute(pull, e.plan(pull, values, null), values)
        assertTrue(r.changed)
        assertTrue(base.manifest!!.has("model", "qwen3:4b"))
    }
}
