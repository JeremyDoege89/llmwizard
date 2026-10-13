package dev.dreamz.llmwizard.ui.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.dreamz.llmwizard.core.knowledge.KnowledgeBase
import dev.dreamz.llmwizard.ui.AgentsViewModel
import dev.dreamz.llmwizard.ui.ModelsViewModel
import dev.dreamz.llmwizard.ui.ProblemCard
import dev.dreamz.llmwizard.ui.ScopeOverlays
import dev.dreamz.llmwizard.ui.ScopeUi
import dev.dreamz.llmwizard.ui.ServerToolViewModel
import dev.dreamz.llmwizard.ui.TrainingViewModel

enum class ToolTab(val label: String, val help: String) { MODELS("Models", "model_browser"), TRAIN("Train", "training_setup"), AGENTS("Agents", "agent_hub"), LEARN("Learn", "") }

/**
 * The plan's tabbed layout (BUILD_PLAN section 7), per server: Models, Train, Agents and Learn
 * in a bottom bar. Chat and the server console stay where they were.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsRoute(kb: KnowledgeBase, onArticle: (String) -> Unit, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(ToolTab.MODELS) }
    val models: ModelsViewModel = hiltViewModel()
    val training: TrainingViewModel = hiltViewModel()
    val agents: AgentsViewModel = hiltViewModel()
    val active: ServerToolViewModel? = when (tab) { ToolTab.MODELS -> models; ToolTab.TRAIN -> training; ToolTab.AGENTS -> agents; ToolTab.LEARN -> null }
    val scopeUi = active?.scope?.ui?.collectAsState()?.value ?: ScopeUi()
    DisposableEffect(tab) {
        if (tab == ToolTab.TRAIN) training.startPolling()
        onDispose { training.stopPolling() }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(tab.label + (scopeUi.server?.let { " · ${it.name.ifBlank { it.host }}" } ?: "")) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    when (tab) {
                        ToolTab.MODELS -> IconButton({ models.refreshInstalled() }) { Icon(Icons.Default.Refresh, "Refresh") }
                        ToolTab.TRAIN -> IconButton({ training.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
                        ToolTab.AGENTS -> IconButton({ agents.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
                        ToolTab.LEARN -> {}
                    }
                })
        },
        bottomBar = {
            NavigationBar {
                ToolTab.entries.forEach { t ->
                    NavigationBarItem(tab == t, { tab = t }, icon = {
                        Icon(when (t) { ToolTab.MODELS -> Icons.Default.Star; ToolTab.TRAIN -> Icons.Default.Build; ToolTab.AGENTS -> Icons.Default.PlayArrow; ToolTab.LEARN -> Icons.Default.Info }, null)
                    }, label = { Text(t.label) })
                }
            }
        },
        floatingActionButton = { if (tab != ToolTab.LEARN) HelpFab(tab.help, kb, onArticle) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                ToolTab.MODELS -> { val u by models.ui.collectAsState(); ModelsTab(u, models.visible(u), models, models.advisor) }
                ToolTab.TRAIN -> { val u by training.ui.collectAsState(); TrainTab(u, training) }
                ToolTab.AGENTS -> { val u by agents.ui.collectAsState(); AgentsTab(u, agents) }
                ToolTab.LEARN -> LearnTab(kb, onArticle)
            }
            scopeUi.busy?.let { b ->
                androidx.compose.foundation.layout.Column(Modifier.align(Alignment.TopCenter).padding(8.dp)) {
                    LinearProgressIndicator(); Text(b, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
            }
            scopeUi.problem?.let { p -> Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { ProblemCard(p) { active?.dismissProblem() } } }
        }
    }
    ScopeOverlays(scopeUi, active)
}
