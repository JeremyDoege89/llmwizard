package dev.dreamz.llmwizard.core.remote

import dev.dreamz.llmwizard.core.shell.Shell
import dev.dreamz.llmwizard.core.ssh.Auth
import dev.dreamz.llmwizard.core.ssh.HostKeyPolicy
import dev.dreamz.llmwizard.core.ssh.MemoryHostKeyStore
import dev.dreamz.llmwizard.core.ssh.SshjTransport
import dev.dreamz.llmwizard.core.ssh.TestSshServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

class JobsTest {
    private val server = TestSshServer()
    private val store = MemoryHostKeyStore()
    private val state = Files.createTempDirectory("llmwizard-state").toFile()

    @AfterEach fun tearDown() { server.close(); state.deleteRecursively() }

    private suspend fun connect() = SshjTransport().connect(
        server.target(), Auth.Password(server.password.toCharArray()), HostKeyPolicy(store, true),
    )

    private suspend fun jobs(s: dev.dreamz.llmwizard.core.ssh.SshSession) = Jobs(Remote(s, state.absolutePath))

    @Test fun `attached step records log and exit code`() = runBlocking {
        connect().use { s ->
            val jobs = jobs(s)
            val id = jobs.create("test")
            jobs.writeStep(id, 1, Shell.script("echo out; echo err >&2; exit 7"))
            val o = jobs.runAttached(id, 1, null, 30.seconds)
            assertEquals(7, o.exitCode)
            assertTrue("out" in o.log && "err" in o.log, o.log)
            assertEquals(StepStatus(StepState.FINISHED, 7), jobs.status(id, 1))
            assertTrue(File(state, "jobs/$id/step-01.sh").readText().startsWith("set -eu"))
        }
    }

    @Test fun `set -eu stops a step at the first failing command`() = runBlocking {
        connect().use { s ->
            val jobs = jobs(s)
            val id = jobs.create("test")
            jobs.writeStep(id, 1, Shell.script("false\necho should-not-print"))
            val o = jobs.runAttached(id, 1, null, 30.seconds)
            assertEquals(1, o.exitCode)
            assertTrue("should-not-print" !in o.log)
        }
    }

    @Test fun `detached step survives the connection dropping and output stays continuous`() = runBlocking {
        var id: String
        var offset = 0L
        val seen = StringBuilder()
        connect().use { s ->
            val jobs = jobs(s)
            id = jobs.create("pull")
            jobs.writeStep(id, 1, Shell.script("i=1\nwhile [ \$i -le 6 ]; do echo line\$i; i=\$((i+1)); sleep 0.5; done"))
            jobs.startDetached(id, 1, null)
            delay(1200)
            assertEquals(StepState.RUNNING, jobs.status(id, 1).state)
            val c = jobs.tail(id, 1, offset); seen.append(c.text); offset = c.nextOffset
        } // connection closed mid-job
        delay(500)
        connect().use { s ->
            val jobs = jobs(s)
            var st = jobs.status(id, 1)
            while (st.state == StepState.RUNNING) {
                val c = jobs.tail(id, 1, offset); seen.append(c.text); offset = c.nextOffset
                delay(300); st = jobs.status(id, 1)
            }
            val c = jobs.tail(id, 1, offset); seen.append(c.text)
            assertEquals(StepStatus(StepState.FINISHED, 0), st)
        }
        assertEquals((1..6).joinToString("") { "line$it\n" }, seen.toString())
    }

    @Test fun `cancel stops a detached step and its children`() = runBlocking {
        connect().use { s ->
            val jobs = jobs(s)
            val id = jobs.create("long")
            jobs.writeStep(id, 1, Shell.script("sleep 300 &\nwait"))
            jobs.startDetached(id, 1, null)
            delay(800)
            assertEquals(StepState.RUNNING, jobs.status(id, 1).state)
            jobs.cancel(id, 1, null)
            val st = jobs.status(id, 1)
            assertEquals(StepState.FINISHED, st.state)
            assertTrue(st.exitCode != 0)
        }
    }

    @Test fun `writeFile keeps content off the command line and stays private`() = runBlocking {
        connect().use { s ->
            val r = Remote(s, state.absolutePath)
            val path = state.absolutePath + "/sub dir/x.txt".replace(" ", "_")
            r.writeFile(path, "it's \$(dangerous) `stuff`\n".toByteArray())
            assertEquals("it's \$(dangerous) `stuff`\n", File(path).readText())
            assertEquals("it's \$(dangerous) `stuff`\n", r.readFile(path))
            assertEquals(null, r.readFile("$path.missing"))
        }
    }
}
