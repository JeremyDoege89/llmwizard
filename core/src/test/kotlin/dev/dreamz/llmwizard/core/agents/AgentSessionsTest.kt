package dev.dreamz.llmwizard.core.agents

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AgentCommandTest {
    private val base = AgentProfile("mtk", "MTK research", AgentType.OPEN_INTERPRETER, "dolphin-mistral:7b-v2.8-q4_K_M",
        systemPrompt = "Analyse firmware.", workDir = "/home/jay/research/mtk", contextLength = 16384)

    @Test fun `open interpreter asks before each command unless the profile says auto-run`() {
        val ask = AgentCommand.argv(base, "/home/jay/.local/share/llmwizard", "http://127.0.0.1:11434", null)
        assertEquals("/home/jay/.local/share/llmwizard/agents/open-interpreter/bin/interpreter", ask.first())
        assertTrue(ask.containsAll(listOf("--offline", "--disable_telemetry", "ollama/dolphin-mistral:7b-v2.8-q4_K_M")))
        assertFalse("--auto_run" in ask)
        assertTrue("--auto_run" in AgentCommand.argv(base.copy(approval = ApprovalMode.AUTO_RUN), "/d", "http://127.0.0.1:11434", null))
    }

    @Test fun `aider gets privacy flags, yes-always only on auto-run, instructions as a read-only file`() {
        val p = base.copy(agent = AgentType.AIDER)
        val a = AgentCommand.argv(p, "/d", "http://127.0.0.1:11434", "/d/agents/sessions/x/instructions.md")
        assertTrue(a.containsAll(listOf("--analytics-disable", "--no-check-update", "ollama_chat/dolphin-mistral:7b-v2.8-q4_K_M", "--read", "/d/agents/sessions/x/instructions.md")))
        assertFalse("--yes-always" in a)
        assertTrue("--yes-always" in AgentCommand.argv(p.copy(approval = ApprovalMode.AUTO_RUN), "/d", "http://127.0.0.1:11434", null))
        assertEquals("http://127.0.0.1:11434", AgentCommand.env(p, "http://127.0.0.1:11434")["OLLAMA_API_BASE"])
    }

    @Test fun `launcher quotes everything and refuses bad profiles`() {
        val evil = base.copy(extraArgs = listOf("\$(touch /tmp/pwned)", "a'b"))
        val l = AgentCommand.launcher(evil, "/d", "http://127.0.0.1:11434", null)
        assertTrue("'\$(touch /tmp/pwned)'" in l, l)
        assertTrue("'a'\\''b'" in l, l)
        assertTrue("asks before each command" in l)
        assertTrue("AUTO-RUN" in AgentCommand.launcher(base.copy(approval = ApprovalMode.AUTO_RUN), "/d", "http://127.0.0.1:11434", null))
        assertThrows<IllegalArgumentException> { AgentCommand.launcher(base.copy(model = "x; rm -rf /"), "/d", "http://127.0.0.1:11434", null) }
        assertThrows<IllegalArgumentException> { AgentCommand.launcher(base.copy(workDir = "relative/path"), "/d", "http://127.0.0.1:11434", null) }
        assertTrue(base.copy(id = "a b").problems().isNotEmpty())
    }
}

/** Real tmux on this machine, driven through the embedded SSH server, with a fake agent. */
class AgentSessionsTest {
    private val ssh = TestSshServer()
    @TempDir lateinit var tmp: File
    private val started = mutableListOf<Pair<AgentSessions, AgentSession>>()

    @AfterEach fun stop() {
        runBlocking { started.forEach { (m, s) -> runCatching { m.stop(s) } } }
        ssh.close()
    }

    private fun <T> withSessions(block: suspend (AgentSessions) -> T): T = runBlocking {
        SshjTransport().connect(ssh.target(), Auth.Password(ssh.password.toCharArray()), HostKeyPolicy(MemoryHostKeyStore(), true)).use { s ->
            block(AgentSessions(Remote(s, File(tmp, "state").absolutePath), File(tmp, "data").absolutePath))
        }
    }

    /** A stand-in for aider: prints its arguments, then echoes lines and asks y/n like the real ones do. */
    private fun fakeAider() {
        val bin = File(tmp, "data/agents/aider/bin").apply { mkdirs() }
        File(bin, "aider").apply {
            writeText("#!/bin/sh\necho \"ARGS: \$*\"\necho \"BASE: \$OLLAMA_API_BASE\"\nwhile IFS= read -r line; do\n  echo \"got: \$line\"\n  printf 'Run it? (y/n) '\n  IFS= read -r a; echo \"answer: \$a\"\ndone\n")
            setExecutable(true)
        }
    }

    private suspend fun waitFor(m: AgentSessions, s: AgentSession, text: String): String {
        repeat(50) { val sc = m.screen(s); if (text in sc) return sc; delay(100) }
        return m.screen(s)
    }

    @Test fun `start, type, approve, read the screen and the log, stop`() {
        assumeTrue(File("/usr/bin/tmux").canExecute() || File("/bin/tmux").canExecute(), "tmux not installed")
        fakeAider()
        withSessions { m ->
            assertEquals(setOf(AgentType.AIDER), m.installed())
            val p = AgentProfile("fake", "Fake aider", AgentType.AIDER, "qwen3:0.6b", systemPrompt = "Be careful.", workDir = tmp.absolutePath)
            m.saveProfile(p)
            assertEquals(listOf(p), m.profiles())
            val s = m.start(p, "http://127.0.0.1:11434").also { started += m to it }
            var screen = waitFor(m, s, "BASE:")
            assertTrue("--analytics-disable" in screen && "--read" in screen && "instructions.md" in screen, screen)
            assertTrue("BASE: http://127.0.0.1:11434" in screen, screen)
            m.send(s, "make it 'safe' \$(whoami) ; done")
            screen = waitFor(m, s, "(y/n)")
            assertTrue("got: make it 'safe' \$(whoami) ; done" in screen, screen)
            m.key(s, AgentKey.YES)
            screen = waitFor(m, s, "answer: y")
            assertTrue("answer: y" in screen, screen)
            assertEquals(SessionStatus.RUNNING, m.list().single().status)
            assertTrue("got: make it" in m.log(s))
            assertThrows<IllegalArgumentException> { runBlocking { m.send(s, "two\nlines") } }
            m.stop(s)
            assertEquals(SessionStatus.STOPPED, m.list().single().status)
            m.forget(s)
            assertTrue(m.list().isEmpty())
            m.deleteProfile("fake")
            assertTrue(m.profiles().isEmpty())
        }
    }
}
