package dev.dreamz.llmwizard

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.dreamz.llmwizard.core.agents.AgentProfile
import dev.dreamz.llmwizard.core.agents.AgentSession
import dev.dreamz.llmwizard.core.agents.AgentType
import dev.dreamz.llmwizard.core.agents.ApprovalMode
import dev.dreamz.llmwizard.core.agents.SessionStatus
import dev.dreamz.llmwizard.core.catalog.Catalog
import dev.dreamz.llmwizard.core.catalog.Recommender
import dev.dreamz.llmwizard.core.knowledge.KnowledgeBase
import dev.dreamz.llmwizard.core.scan.ScanParser
import dev.dreamz.llmwizard.core.training.DatasetFormat
import dev.dreamz.llmwizard.core.training.JobState
import dev.dreamz.llmwizard.core.training.TrainingDataset
import dev.dreamz.llmwizard.core.training.TrainingJob
import dev.dreamz.llmwizard.core.training.TrainingProgress
import dev.dreamz.llmwizard.core.training.TrainingStage
import dev.dreamz.llmwizard.ui.AgentsUi
import dev.dreamz.llmwizard.ui.ModelChip
import dev.dreamz.llmwizard.ui.ModelsUi
import dev.dreamz.llmwizard.ui.TrainingUi
import dev.dreamz.llmwizard.ui.theme.LlmWizardTheme
import dev.dreamz.llmwizard.ui.tools.AgentTerminal
import dev.dreamz.llmwizard.ui.tools.AgentsTab
import dev.dreamz.llmwizard.ui.tools.ArticleScreen
import dev.dreamz.llmwizard.ui.tools.LearnTab
import dev.dreamz.llmwizard.ui.tools.ModelsTab
import dev.dreamz.llmwizard.ui.tools.TrainTab
import dev.dreamz.llmwizard.ui.visibleModels
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The BUILD_PLAN screens (Models, Train, Agents, Learn), rendered to PNG like [ScreenshotTest]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class ToolsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { LlmWizardTheme(dark = dark) { Surface { content() } } }
        compose.mainClock.advanceTimeBy(1000)
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    private val catalog = Catalog.bundled()
    private val kb = KnowledgeBase.bundled()
    private val dl380p = ScanParser.parse(File("../fixtures/dl380p-hyperv-ubuntu2404.txt").readText())

    private fun models(chip: ModelChip, expanded: String? = null): ModelsUi {
        val u = ModelsUi(profile = dl380p, chip = chip, installed = setOf("qwen3.6:35b-a3b"), expanded = expanded)
        return u.copy(recommendation = Recommender(catalog).recommend(dl380p, filter = chip.filter))
    }

    @Test fun modelsAll() = shot("20-models-all") { val u = models(ModelChip.ALL, "qwen3.6-35b-a3b"); ModelsTab(u, visibleModels(catalog, u), null, null) }
    @Test fun modelsUncensored() = shot("21-models-uncensored") { val u = models(ModelChip.UNCENSORED, "qwen3.5-abliterated-35b-a3b"); ModelsTab(u, visibleModels(catalog, u), null, null) }
    @Test fun modelsDark() = shot("22-models-dark", dark = true) { val u = models(ModelChip.CODING); ModelsTab(u, visibleModels(catalog, u), null, null) }

    @Test fun train() = shot("23-train") {
        val job = TrainingJob("job-1", "qwen3:0.6b", "Qwen/Qwen3-0.6B", "ds-1", outputName = "qwen3-bigiron:q4", createdAt = "2026-10-13T19:40:05Z")
        TrainTab(TrainingUi(
            stackInstalled = true,
            datasets = listOf(TrainingDataset("ds-1", "bigiron-fact", DatasetFormat.INSTRUCT_JSONL, "/x", 16, 4200, 560, "2026-10-13", true)),
            jobs = listOf(
                JobState(job.copy(id = "job-2", baseModel = "dolphin3:8b-llama3.1-q4_K_M", outputName = "dolphin-mine:latest"),
                    TrainingProgress(TrainingStage.TRAINING, currentStep = 312, totalSteps = 750, loss = 1.184, estimatedRemainingSeconds = 41_000, tokensPerSecond = 3.1), alive = true),
                JobState(job, TrainingProgress(TrainingStage.DONE, message = "Ready in Ollama as qwen3-bigiron:q4", ollamaModel = "qwen3-bigiron:q4"), alive = false),
            )), null)
    }
    @Test fun trainNotInstalled() = shot("24-train-not-installed") { TrainTab(TrainingUi(stackInstalled = false), null) }

    private val profiles = listOf(
        AgentProfile("demo", "Fix bugs in demo", AgentType.AIDER, "qwen3-coder:30b", workDir = "/home/wiz/demo"),
        AgentProfile("mtk", "MediaTek bootloader research", AgentType.OPEN_INTERPRETER, "huihui_ai/qwen3.5-abliterated:35b-a3b-q4_K", workDir = "/home/jay/research/mtk", approval = ApprovalMode.AUTO_RUN),
    )
    private val session = AgentSession("20261013-195702-b3tu", "demo", AgentType.AIDER, "qwen3-coder:30b", "/home/wiz/demo", ApprovalMode.APPROVE_EACH, "llmw-aider-x", "2026-10-13T19:57:02Z", SessionStatus.RUNNING)

    @Test fun agents() = shot("25-agents") {
        AgentsTab(AgentsUi(installed = setOf(AgentType.AIDER, AgentType.OPEN_INTERPRETER), profiles = profiles, sessions = listOf(session)), null)
    }
    @Test fun agentTerminal() = shot("26-agent-terminal") {
        AgentTerminal(session, """
            Aider v0.86.2
            Model: ollama_chat/qwen3-coder:30b with diff edit format
            Git repo: .git with 1 files
            Added calc.py to the chat.

            calc.py
            ```
            def add(a, b):
                return a + b
            ```
            Applied edit to calc.py
            Commit 6669764 fix: add function subtracts. Make it add.
            Run the tests now? python -m pytest (Y)es/(N)o [Yes]:
        """.trimIndent(), null)
    }
    @Test fun learn() = shot("27-learn") { LearnTab(kb) {} }
    @Test fun article() = shot("28-article") { ArticleScreen(kb, "dense-vs-moe", {}) {} }
}
