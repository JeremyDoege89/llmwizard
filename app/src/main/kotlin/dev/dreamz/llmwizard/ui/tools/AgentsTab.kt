package dev.dreamz.llmwizard.ui.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dreamz.llmwizard.core.agents.AgentKey
import dev.dreamz.llmwizard.core.agents.AgentProfile
import dev.dreamz.llmwizard.core.agents.AgentSession
import dev.dreamz.llmwizard.core.agents.AgentType
import dev.dreamz.llmwizard.core.agents.ApprovalMode
import dev.dreamz.llmwizard.core.agents.SessionStatus
import dev.dreamz.llmwizard.ui.AgentsUi
import dev.dreamz.llmwizard.ui.AgentsViewModel
import dev.dreamz.llmwizard.ui.MonoBlock
import dev.dreamz.llmwizard.ui.SectionCard
import dev.dreamz.llmwizard.ui.theme.Mono

/** BUILD_PLAN E4: Aider and Open Interpreter on the server, in tmux, driven from the phone. */
@Composable
fun AgentsTab(ui: AgentsUi, vm: AgentsViewModel?) {
    var confirmStart by remember { mutableStateOf<AgentProfile?>(null) }
    ui.open?.let { s -> AgentTerminal(s, ui.screen, vm); return }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Agent tools") {
                Text("Coding agents that run on the server and use your models there. They keep running when the phone disconnects.", style = MaterialTheme.typography.bodySmall)
                AgentType.entries.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.label, fontWeight = FontWeight.Medium)
                            Text(if (t == AgentType.AIDER) "Pair programmer: edits code in a git repository and commits." else "Writes and runs code on the server to do what you ask.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        when (ui.installed?.contains(t)) {
                            null -> {}
                            true -> TextButton({ vm?.remove(t) }) { Text("Remove") }
                            false -> Button({ vm?.install(t) }) { Text("Install") }
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Profiles", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button({ vm?.edit(AgentProfile("p" + System.currentTimeMillis().toString(36), "", ui.installed?.firstOrNull() ?: AgentType.AIDER, ui.models.firstOrNull() ?: "")) },
                    enabled = !ui.installed.isNullOrEmpty()) { Text("New profile") }
            }
        }
        if (ui.profiles.isEmpty()) item { Text("No profiles yet. A profile says which agent, which model, which folder, and whether it asks before acting.", style = MaterialTheme.typography.bodySmall) }
        items(ui.profiles, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                    Text("${p.agent.label} · ${p.model}" + (p.workDir?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    ApprovalLabel(p.approval, p.agent)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ confirmStart = p }, enabled = ui.installed?.contains(p.agent) == true) { Text("Start") }
                        OutlinedButton({ vm?.edit(p) }) { Text("Edit") }
                        TextButton({ vm?.deleteProfile(p) }) { Text("Delete") }
                    }
                }
            }
        }
        item { Text("Sessions", style = MaterialTheme.typography.titleMedium) }
        if (ui.sessions.isEmpty()) item { Text("None running.", style = MaterialTheme.typography.bodySmall) }
        items(ui.sessions, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${s.agent.label} on ${s.model}", style = MaterialTheme.typography.titleSmall)
                    Text("${if (s.status == SessionStatus.RUNNING) "Running" else "Ended"} · started ${s.startedAt.take(16).replace('T', ' ')}" + (s.workDir?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm?.open(s) }) { Text("Open") }
                        if (s.status == SessionStatus.RUNNING) OutlinedButton({ vm?.stop(s) }) { Text("Stop") }
                        TextButton({ vm?.forget(s) }) { Text("Remove") }
                    }
                }
            }
        }
    }
    ui.editing?.let { p -> ProfileDialog(p, ui, vm) }
    confirmStart?.let { p ->
        AlertDialog(onDismissRequest = { confirmStart = null }, title = { Text("Start ${p.name}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApprovalLabel(p.approval, p.agent)
                    Text("This is the exact launcher it runs in a tmux session on the server:", style = MaterialTheme.typography.bodySmall)
                    MonoBlock(runCatching { vm?.preview(p) ?: "" }.getOrElse { it.message ?: "" })
                }
            },
            confirmButton = { TextButton({ vm?.start(p); confirmStart = null }) { Text("Start") } },
            dismissButton = { TextButton({ confirmStart = null }) { Text("Cancel") } })
    }
}

@Composable
fun ApprovalLabel(a: ApprovalMode, agent: AgentType) = when (a) {
    ApprovalMode.APPROVE_EACH -> Text(if (agent == AgentType.AIDER) "Asks before running commands; edits are git commits you can /undo" else "Asks before running each piece of code", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    ApprovalMode.AUTO_RUN -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
        Text("Auto-run: acts on the server without asking", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun ProfileDialog(p0: AgentProfile, ui: AgentsUi, vm: AgentsViewModel?) {
    var p by remember { mutableStateOf(p0) }
    val problems = p.problems()
    AlertDialog(onDismissRequest = { vm?.edit(null) }, title = { Text(if (p0.name.isEmpty()) "New agent profile" else "Edit ${p0.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(p.name, { p = p.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Agent", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AgentType.entries.forEach { t -> FilterChip(p.agent == t, { p = p.copy(agent = t) }, label = { Text(t.label) }, enabled = ui.installed?.contains(t) == true) }
                }
                Text("Model", fontWeight = FontWeight.Medium)
                if (ui.models.isEmpty()) Text("No models installed on this server.", color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ui.models.forEach { m -> FilterChip(p.model == m, { p = p.copy(model = m) }, label = { Text(m) }) } }
                OutlinedTextField(p.workDir ?: "", { p = p.copy(workDir = it.ifBlank { null }) }, label = { Text("Working folder on the server (absolute)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (p.agent == AgentType.AIDER) Text("Aider works inside a git repository; point it at one.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(p.systemPrompt ?: "", { p = p.copy(systemPrompt = it.ifBlank { null }) }, label = { Text("Instructions (optional)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Text("Who approves actions", fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(p.approval == ApprovalMode.APPROVE_EACH, { p = p.copy(approval = ApprovalMode.APPROVE_EACH) })
                    Column {
                        Text("I approve each command")
                        Text(if (p.agent == AgentType.AIDER) "Aider asks before running commands or adding files; you answer from the phone. Its code edits are applied as git commits, which /undo reverts."
                            else "Open Interpreter shows each piece of code and waits for your Yes before running it.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(p.approval == ApprovalMode.AUTO_RUN, { p = p.copy(approval = ApprovalMode.AUTO_RUN) })
                    Column { Text("Auto-run, unattended"); Text("It runs code and edits files on the server without asking. Use a folder and a user account you can afford to lose.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton({ vm?.save(p) }, enabled = problems.isEmpty()) { Text("Save") } },
        dismissButton = { TextButton({ vm?.edit(null) }) { Text("Cancel") } })
}

/** The agent's terminal, re-read every 2 s, with the answers agents ask for one tap away. */
@Composable
fun AgentTerminal(s: AgentSession, screen: String, vm: AgentsViewModel?) {
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(screen) { scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm?.open(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("${s.agent.label} · ${s.model}", style = MaterialTheme.typography.titleSmall)
                ApprovalLabel(s.approval, s.agent)
            }
        }
        Card(Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            SelectionContainer {
                Text(screen.ifEmpty { "(connecting…)" }, style = Mono.copy(fontSize = 11.sp, lineHeight = 13.sp),
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll).horizontalScroll(rememberScrollState()).padding(8.dp))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({ vm?.key(AgentKey.YES) }) { Text("Yes") }
            OutlinedButton({ vm?.key(AgentKey.NO) }) { Text("No") }
            OutlinedButton({ vm?.key(AgentKey.ENTER) }) { Text("Enter") }
            OutlinedButton({ vm?.key(AgentKey.CTRL_C) }) { Text("Ctrl-C") }
            OutlinedButton({ vm?.key(AgentKey.ESCAPE) }) { Text("Esc") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it.replace("\n", " ") }, Modifier.weight(1f).heightIn(max = 120.dp), placeholder = { Text("Tell the agent…") })
            IconButton({ if (input.isNotBlank()) { vm?.send(input); input = "" } }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}
