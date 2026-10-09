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
import dev.dreamz.llmwizard.ui.PasswordKind
import dev.dreamz.llmwizard.ui.SetupUi
import dev.dreamz.llmwizard.ui.SetupViewModel
import dev.dreamz.llmwizard.ui.StepStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * The app's setup flow, driven through the real SetupViewModel, Room and SSH against a real
 * target: scan, choose a model, install, benchmark. No pixels, but every line of app logic the
 * install screen runs. Opt-in: ./gradlew :app:testDebugUnitTest -Ptarget=wiz-debian
 * (the target must be clean: `lxc restore wiz-debian clean`).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], application = Application::class)
class InstallFlowTest {

    private fun until(timeoutMs: Long, what: String, ui: () -> SetupUi, cond: (SetupUi) -> Boolean): SetupUi {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            val u = ui()
            u.problem?.let { throw AssertionError("$what: ${it.title}: ${it.message}") }
            if (cond(u)) return u
            Thread.sleep(200)
        }
        throw AssertionError("timed out waiting for: $what (busy=${ui().busy})")
    }

    @Test fun `install from the app on a clean target`() {
        val name = System.getProperty("llmwizard.target")
        assumeTrue("set -Ptarget=<name> to run", !name.isNullOrBlank())
        val props = Properties().apply { File("../targets.local.properties").inputStream().use { load(it) } }
        val host = props.getProperty("$name.host"); val user = props.getProperty("$name.user")
        val password = props.getProperty("$name.password") ?: "test-password"

        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
        val servers = Servers(db, KeyVault(ctx))
        val id = runBlocking { db.servers().insert(ServerEntity(name = name, host = host, port = 22, user = user, authType = AuthType.PASSWORD)) }
        val vm = SetupViewModel(SavedStateHandle(mapOf("id" to id)), servers, Catalog.bundled())
        val ui = { vm.ui.value }
        until(10_000, "server row loaded", ui) { it.server != null }

        // Scan: password login, then the host key question (trust on first use).
        vm.scan()
        until(10_000, "login prompt", ui) { it.passwordPrompt == PasswordKind.LOGIN }
        vm.submitPassword(password.toCharArray())
        until(60_000, "host key prompt", ui) { it.hostKeyPrompt != null }
        vm.acceptHostKey()
        until(10_000, "login prompt again", ui) { it.passwordPrompt == PasswordKind.LOGIN }
        vm.submitPassword(password.toCharArray())
        val scanned = until(120_000, "scan", ui) { it.profile != null && it.busy == null }
        assertEquals("debian", scanned.profile!!.os?.id)
        assertNotNull(scanned.recommendation?.pick)
        assertTrue(scanned.offerKeyInstall)
        vm.declineKeyInstall()

        vm.choose("qwen3:0.6b")
        vm.install()
        until(10_000, "login for install", ui) { it.passwordPrompt == PasswordKind.LOGIN }
        vm.submitPassword(password.toCharArray())
        val done = until(1_800_000, "install result", ui) { it.run?.result != null }
        assertTrue(done.run!!.result!!.message, done.run!!.result!!.ok)
        assertTrue(done.run!!.status.values.all { it == StepStatus.OK || it == StepStatus.DONE_ALREADY })
        assertTrue("log shows the download", "Downloading Ollama" in done.run!!.log)
        val measured = until(600_000, "benchmark", ui) { it.benchmark != null && it.busy == null }
        println("Measured %.1f tok/s (load %.1f s); estimate: %s".format(measured.benchmark!!.tokensPerSecond, measured.benchmark!!.loadSeconds, measured.estimateText))
        assertEquals("installed", measured.server!!.installState)
        assertNull(measured.problem)
        // The transcript holds every script, and never the password.
        val rows = runBlocking { db.transcript().forServer("server-$id").first() }
        assertTrue(rows.size > 10)
        assertTrue(rows.none { password in it.script || password in it.outputTail })
    }
}
