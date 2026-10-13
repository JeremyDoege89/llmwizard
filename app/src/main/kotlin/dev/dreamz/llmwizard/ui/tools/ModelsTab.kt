package dev.dreamz.llmwizard.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dreamz.llmwizard.core.catalog.Candidate
import dev.dreamz.llmwizard.core.catalog.ModelEntry
import dev.dreamz.llmwizard.core.catalog.Recommender.Companion.gb
import dev.dreamz.llmwizard.core.config.ContextAdvisor
import dev.dreamz.llmwizard.core.config.ModelConfig
import dev.dreamz.llmwizard.ui.ModelChip
import dev.dreamz.llmwizard.ui.ModelRow
import dev.dreamz.llmwizard.ui.ModelSort
import dev.dreamz.llmwizard.ui.ModelsUi
import dev.dreamz.llmwizard.ui.ModelsViewModel
import dev.dreamz.llmwizard.ui.MonoBlock

/** BUILD_PLAN E1.4: the catalog with filters, estimates for this server, and per-model actions. */
@Composable
fun ModelsTab(ui: ModelsUi, rows: List<ModelRow>, vm: ModelsViewModel?, advisor: ContextAdvisor?) {
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(ui.query, { vm?.query(it) }, Modifier.fillMaxWidth(), placeholder = { Text("Search name or tag") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelChip.entries.forEach { c -> FilterChip(ui.chip == c, { vm?.chip(c) }, label = { Text(c.label) }) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sort:", style = MaterialTheme.typography.labelLarge)
                ModelSort.entries.forEach { s -> FilterChip(ui.sort == s, { vm?.sort(s) }, label = { Text(s.label) }) }
            }
        }
        if (ui.profile == null) item {
            Text("Scan this server on its setup screen to see speed estimates for each model.", style = MaterialTheme.typography.bodySmall)
        }
        if (ui.chip == ModelChip.UNCENSORED) item {
            Text("Uncensored models answer without refusing. They are no smarter or more accurate than the originals. The \"?\" button explains more.",
                style = MaterialTheme.typography.bodySmall)
        }
        items(rows, key = { it.first.id }) { (e, c) ->
            ModelCard(e, c, installed = e.ollamaTag in ui.installed, expanded = ui.expanded == e.id, compare = vm?.compareText(e),
                onToggle = { vm?.expand(e.id) }, onInstall = { vm?.install(e) }, onBench = { vm?.benchmark(e) }, onConfigure = { vm?.openConfig(e) })
        }
        if (rows.isEmpty()) item { Text("No model matches.") }
    }
    ui.configFor?.let { e -> ConfigureDialog(e, ui, advisor, onCreate = { vm?.createConfig(it) }, onClose = { vm?.openConfig(null) }) }
}

@Composable
fun Stars(n: Int) = Row { repeat(5) { i -> Icon(Icons.Default.Star, null, tint = if (i < n) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(0.dp)) } }

@Composable
fun ModelCard(e: ModelEntry, c: Candidate?, installed: Boolean, expanded: Boolean, compare: String?,
              onToggle: () -> Unit, onInstall: () -> Unit, onBench: () -> Unit, onConfigure: () -> Unit) {
    val uri = LocalUriHandler.current
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle),
        colors = if (installed) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (installed) Text("Installed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stars(e.quality)
                Text(gb(e.downloadBytes), style = MaterialTheme.typography.bodyMedium)
                if (e.isMoe) Text("MoE", style = MaterialTheme.typography.labelMedium)
            }
            Text(when {
                c?.estimate != null -> c.estimate!!.describe()
                c?.fitProblem != null -> "Doesn't fit: ${c.fitProblem}"
                else -> "Speed unknown until the server is scanned"
            }, style = MaterialTheme.typography.bodyMedium, color = if (c?.fits == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                e.tags.sorted().forEach { AssistChip({}, label = { Text(it) }) }
                if (e.supportsTools) AssistChip({}, label = { Text("tools") })
                if (e.nonCommercial) AssistChip({}, label = { Text("non-commercial") })
            }
            if (expanded) {
                Text(c?.explanation?.takeIf { it.isNotEmpty() } ?: e.notes, style = MaterialTheme.typography.bodyMedium)
                Text("${e.ollamaTag} · licence ${e.license ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                compare?.let { MonoBlock(it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!installed) Button(onInstall, enabled = c?.fits != false) { Text("Install") }
                    else {
                        Button(onBench) { Text("Benchmark") }
                        OutlinedButton(onConfigure) { Text("Configure…") }
                    }
                    TextButton({ uri.openUri(e.pageUrl) }) { Text("Model page") }
                }
            }
        }
    }
}

/** BUILD_PLAN E2.1–E2.3: a tuned copy of an installed model, with the Modelfile preview and the context advisor. */
@Composable
fun ConfigureDialog(e: ModelEntry, ui: ModelsUi, advisor: ContextAdvisor?, onCreate: (ModelConfig) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(e.ollamaTag.substringAfterLast('/').substringBefore(':') + "-custom") }
    var system by remember { mutableStateOf("") }
    var temp by remember { mutableFloatStateOf(0.7f) }
    var ctxIndex by remember { mutableFloatStateOf(1f) }
    var showFile by remember { mutableStateOf(false) }
    val lengths = ContextAdvisor.DEFAULT_LENGTHS
    val ctx = lengths[ctxIndex.toInt().coerceIn(lengths.indices)]
    val cfg = ModelConfig(e.ollamaTag, name.trim(), systemPrompt = system.ifBlank { null }, temperature = (temp * 10).toInt() / 10.0, contextLength = ctx)
    val problems = cfg.problems()
    AlertDialog(onDismissRequest = onClose, title = { Text("Configure ${e.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Makes a new model on the server with your settings. The original stays as it is.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it.lowercase() }, label = { Text("New name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(system, { system = it }, label = { Text("System prompt (optional)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Text("Temperature ${"%.1f".format(temp)}: lower is focused and repeatable, higher is creative and random.")
                Slider(temp, { temp = it }, valueRange = 0f..2f, steps = 19)
                Text("Context: ${ctx / 1024}K tokens", fontWeight = FontWeight.Medium)
                Slider(ctxIndex, { ctxIndex = it }, valueRange = 0f..(lengths.size - 1).toFloat(), steps = lengths.size - 2)
                advisor?.let { Text(it.describe(e, ui.profile?.memory?.availableBytes), style = MaterialTheme.typography.bodySmall) }
                problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton({ showFile = !showFile }) { Text(if (showFile) "Hide Modelfile" else "Show as Modelfile") }
                if (showFile && problems.isEmpty()) MonoBlock(cfg.toModelfile())
                ui.configMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton({ onCreate(cfg) }, enabled = problems.isEmpty()) { Text("Create") } },
        dismissButton = { TextButton(onClose) { Text("Close") } })
}
