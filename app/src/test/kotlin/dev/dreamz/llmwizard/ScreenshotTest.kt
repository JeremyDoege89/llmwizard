package dev.dreamz.llmwizard

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.dreamz.llmwizard.core.catalog.Catalog
import dev.dreamz.llmwizard.core.catalog.Intent
import dev.dreamz.llmwizard.core.catalog.Recommender
import dev.dreamz.llmwizard.core.ollama.BenchmarkResult
import dev.dreamz.llmwizard.core.recipe.PlannedStep
import dev.dreamz.llmwizard.core.recipe.Recipe
import dev.dreamz.llmwizard.core.recipe.RunResult
import dev.dreamz.llmwizard.core.scan.ScanParser
import dev.dreamz.llmwizard.core.scan.Verdicts
import dev.dreamz.llmwizard.core.shell.Shell
import dev.dreamz.llmwizard.core.ssh.KnownHost
import dev.dreamz.llmwizard.data.AuthType
import dev.dreamz.llmwizard.data.ServerEntity
import dev.dreamz.llmwizard.ui.AddServerUi
import dev.dreamz.llmwizard.ui.PasswordKind
import dev.dreamz.llmwizard.ui.Problem
import dev.dreamz.llmwizard.ui.RunUi
import dev.dreamz.llmwizard.ui.SetupUi
import dev.dreamz.llmwizard.ui.StepStatus
import dev.dreamz.llmwizard.ui.screens.AddServerScreen
import dev.dreamz.llmwizard.ui.screens.FirstRunScreen
import dev.dreamz.llmwizard.ui.screens.HomeScreen
import dev.dreamz.llmwizard.ui.screens.SetupScreen
import dev.dreamz.llmwizard.ui.theme.LlmWizardTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every main screen in its main states, rendered to PNG under app/build/outputs/roborazzi
 * (`./gradlew :app:recordRoborazziDebug`). Open them and look (SPEC.md, UI checks).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        // Spinners animate forever; a paused clock lets the screen settle for the picture.
        compose.mainClock.autoAdvance = false
        compose.setContent { LlmWizardTheme(dark = dark) { Surface { content() } } }
        compose.mainClock.advanceTimeBy(1000)
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    private val catalog = Catalog.bundled()
    private fun profile(name: String) = ScanParser.parse(File("../fixtures/$name.txt").readText())
    private val server = ServerEntity(1, "wiz-ubuntu", "192.168.1.40", 22, "jay", AuthType.KEY, "ssh-ed25519", "SHA256:6oZGNu+MeNygy+xyhSe7p2vlvXWHRpIX+512UZ98154")

    private fun setupUi(fixture: String): SetupUi {
        val p = profile(fixture)
        val r = Recommender(catalog).recommend(p, Intent.CHAT)
        return SetupUi(server = server, profile = p, verdicts = Verdicts.of(p), recommendation = r, chosenTag = r.pick?.candidate?.entry?.ollamaTag)
    }

    private fun plan(done: Int): List<PlannedStep> {
        val recipe = Recipe.bundled("ollama-install")
        return recipe.steps.mapIndexed { i, s -> PlannedStep(i + 1, s, Shell.script("P_model='qwen3.6:35b-a3b'\n" + s.run), i < done, emptyList()) }
    }

    @Test fun firstRun() = shot("01-first-run") { FirstRunScreen {} }
    @Test fun homeEmpty() = shot("02-home-empty") { HomeScreen(emptyList(), {}, {}, {}, {}, {}) }
    @Test fun homeServers() = shot("03-home-servers") {
        HomeScreen(listOf(server.copy(installState = "installed", model = "qwen3.6:35b-a3b"), server.copy(id = 2, name = "old-laptop", host = "192.168.1.77", installState = "failed")), {}, {}, {}, {}, {})
    }
    @Test fun addServer() = shot("04-add-server") { AddServerScreen(AddServerUi(), {}, { _, _, _, _, _ -> }, {}) }
    @Test fun addServerError() = shot("05-add-server-error") {
        AddServerScreen(AddServerUi(problem = Problem("Can't reach 192.168.1.40", "Nothing answered on that address and port.", "Check that the machine is on and that SSH is installed (sudo apt install openssh-server).")), {}, { _, _, _, _, _ -> }, {})
    }
    @Test fun hostKey() = shot("06-host-key") {
        SetupScreen(SetupUi(server = server, hostKeyPrompt = KnownHost("ssh-ed25519", "SHA256:6oZGNu+MeNygy+xyhSe7p2vlvXWHRpIX+512UZ98154")), null, {}, {})
    }
    @Test fun scanned() = shot("07-scanned-dl380p") { SetupScreen(setupUi("dl380p-hyperv-ubuntu2404"), null, {}, {}) }
    @Test fun scannedDark() = shot("08-scanned-dl380p-dark", dark = true) { SetupScreen(setupUi("dl380p-hyperv-ubuntu2404"), null, {}, {}) }
    @Test fun unsupported() = shot("09-unsupported-mx") { SetupScreen(setupUi("synthetic-mx-sysvinit"), null, {}, {}) }
    @Test fun nvidiaNoDriver() = shot("10-nvidia-no-driver") { SetupScreen(setupUi("synthetic-nvidia-no-driver"), null, {}, {}) }
    @Test fun planPreview() = shot("11-plan") { SetupScreen(SetupUi(server = server, plan = plan(1)), null, {}, {}) }
    @Test fun installing() = shot("12-installing") {
        val p = plan(1)
        SetupScreen(SetupUi(server = server, run = RunUi("Install Ollama", p, p.associate { it.index to when (it.index) { 1 -> StepStatus.DONE_ALREADY; 2 -> StepStatus.RUNNING; else -> StepStatus.PENDING } },
            "▶ Download Ollama\nDownloading Ollama 0.40.2\n######################                     51.2%\n")), null, {}, {})
    }
    @Test fun failed() = shot("13-install-failed") {
        val p = plan(1)
        SetupScreen(SetupUi(server = server, run = RunUi("Install Ollama", p, p.associate { it.index to if (it.index <= 4) StepStatus.OK else if (it.index == 5) StepStatus.FAILED else StepStatus.PENDING },
            "▶ Create the Ollama service\ncat: /etc/systemd/system/ollama.service: Read-only file system\n",
            RunResult(false, p[4], 1, p.subList(1, 4), "\"Create the Ollama service\" failed (exit code 1)."))), null, {}, {})
    }
    @Test fun measured() = shot("14-measured") {
        SetupScreen(setupUi("dl380p-hyperv-ubuntu2404").copy(server = server.copy(installState = "installed"),
            benchmark = BenchmarkResult("qwen3.6:35b-a3b", 8.5, 23.6, 41.0, 128, "2026-10-10T08:00:00Z"), estimateText = "comfortable, about 8.6–11 tokens/s (estimate)"), null, {}, {})
    }
}
