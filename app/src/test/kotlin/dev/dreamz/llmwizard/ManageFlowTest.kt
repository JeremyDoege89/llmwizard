package dev.dreamz.llmwizard

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.dreamz.llmwizard.conn.Servers
import dev.dreamz.llmwizard.core.catalog.Catalog
import dev.dreamz.llmwizard.data.AppDb
import dev.dreamz.llmwizard.data.AuthType
import dev.dreamz.llmwizard.data.KeyVault
import dev.dreamz.llmwizard.data.ServerEntity
import dev.dreamz.llmwizard.ui.ChatViewModel
import dev.dreamz.llmwizard.ui.ManageViewModel
import dev.dreamz.llmwizard.ui.PasswordKind
import dev.dreamz.llmwizard.ui.ReplyPhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.Properties

/**
 * Chat and server management through the real ViewModels against a target where Ollama is
 * installed (run InstallFlowTest first). Opt-in: -Ptarget=wiz-debian. Ends by uninstalling.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], application = Application::class)
class ManageFlowTest {
    private fun <T> until(timeoutMs: Long, what: String, get: () -> T, cond: (T) -> Boolean): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            val v = get()
            if (cond(v)) return v
            Thread.sleep(200)
        }
        throw AssertionError("timed out waiting for: $what (last: ${get()})")
    }

    @Test fun `chat, status, restart, settings, sudo rule, uninstall`() {
        val name = System.getProperty("llmwizard.target")
        assumeTrue(!name.isNullOrBlank())
        val props = Properties().apply { File("../targets.local.properties").inputStream().use { load(it) } }
        val pw = props.getProperty("$name.password") ?: "test-password"
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
        val servers = Servers(db, KeyVault(ctx))
        val host = props.getProperty("$name.host")
        val id = runBlocking { db.servers().insert(ServerEntity(name = name, host = host, port = 22, user = props.getProperty("$name.user"), authType = AuthType.PASSWORD, installState = "installed", model = "qwen3:0.6b")) }
        // Trust the host key the way the add-server flow would have.
        runBlocking {
            try { servers.open(db.servers().get(id)!!, pw.toCharArray()) } catch (e: dev.dreamz.llmwizard.core.ssh.SshError.HostKeyUnknown) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { servers.acceptHostKey(db.servers().get(id)!!, e) }
            }
            servers.open(db.servers().get(id)!!, pw.toCharArray())
        }

        // Chat: one exchange, stored with Ollama's measured speed.
        val chat = ChatViewModel(SavedStateHandle(mapOf("id" to id)), servers)
        until(60_000, "models listed", { chat.ui.value }) { it.models.isNotEmpty() || it.problem != null }
        assertTrue(chat.ui.value.problem?.message ?: "", chat.ui.value.problem == null)
        chat.send("Reply with one word: hello")
        val msgs = until(300_000, "reply stored", { chat.messages.value }) { m -> m.size >= 2 && chat.ui.value.phase == ReplyPhase.IDLE }
        assertEquals("assistant", msgs.last().role)
        assertTrue(msgs.last().tokensPerSecond!! > 0)
        println("Chat reply: ${msgs.last().content.take(80)} (%.1f tok/s)".format(msgs.last().tokensPerSecond))

        // Manage.
        val m = ManageViewModel(SavedStateHandle(mapOf("id" to id)), servers, Catalog.bundled())
        until(60_000, "status", { m.ui.value }) { (it.status != null && it.busy == null) || it.problem != null }
        assertEquals("active", m.ui.value.status!!.active)
        assertTrue(m.ui.value.models.any { it.name == "qwen3:0.6b" })
        println("Status: ${m.ui.value.status}")

        fun action(label: String, start: () -> Unit) {
            m.closeAction()
            start()
            val a = until(600_000, label, { m.ui.value }) { (it.action?.running == false && it.busy == null) || it.problem != null || it.passwordPrompt == PasswordKind.SUDO }
            assertTrue("$label: ${a.problem}", a.problem == null)
            assertTrue("$label: ${a.action?.message}\n${a.action?.log}", a.action?.ok == true)
            println("$label: ${a.action?.message}")
        }
        action("restart") { m.service("restart") }
        action("configure") { m.configure(16384, "10m") }
        assertEquals("16384", m.ui.value.manifest!!.settings["settings.contextLength"])
        action("sudo rule") { m.addSudoRule() }
        assertTrue(m.ui.value.manifest!!.has("file", "/etc/sudoers.d/llmwizard-ollama"))
        action("restart without password") { m.service("restart") }
        action("uninstall") { m.uninstall(deleteModels = true) }
        assertTrue(m.ui.value.manifest!!.created.isEmpty())
        val rows = runBlocking { db.transcript().forServer("server-$id").first() }
        assertTrue(rows.none { pw in it.script || pw in it.outputTail })
    }
}
