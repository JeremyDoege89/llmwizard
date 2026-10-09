package dev.dreamz.llmwizard.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dreamz.llmwizard.core.catalog.Candidate
import dev.dreamz.llmwizard.core.catalog.Intent
import dev.dreamz.llmwizard.core.catalog.Pick
import dev.dreamz.llmwizard.core.catalog.Recommendation
import dev.dreamz.llmwizard.core.recipe.PlannedStep
import dev.dreamz.llmwizard.core.scan.HardwareProfile
import dev.dreamz.llmwizard.core.scan.ScanParser
import dev.dreamz.llmwizard.core.scan.Severity
import dev.dreamz.llmwizard.ui.HostKeyDialog
import dev.dreamz.llmwizard.ui.MonoBlock
import dev.dreamz.llmwizard.ui.PasswordDialog
import dev.dreamz.llmwizard.ui.PasswordKind
import dev.dreamz.llmwizard.ui.ProblemCard
import dev.dreamz.llmwizard.ui.RunUi
import dev.dreamz.llmwizard.ui.SectionCard
import dev.dreamz.llmwizard.ui.SetupUi
import dev.dreamz.llmwizard.ui.SetupViewModel
import dev.dreamz.llmwizard.ui.StepStatus
import dev.dreamz.llmwizard.ui.VerdictRow

@Composable
fun SetupRoute(vm: SetupViewModel, onBack: () -> Unit, onTranscript: (Long) -> Unit) {
    val ui by vm.ui.collectAsState()
    SetupScreen(ui, vm, onBack, onTranscript)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(ui: SetupUi, vm: SetupViewModel?, onBack: () -> Unit, onTranscript: (Long) -> Unit) {
    val s = ui.server
    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text(s?.name ?: "Server"); s?.let { Text("${it.user}@${it.host}", style = MaterialTheme.typography.bodySmall) } } },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { s?.let { IconButton({ onTranscript(it.id) }) { Icon(Icons.AutoMirrored.Filled.List, "Transcript") } } },
        )
    }) { pad ->
        when {
            ui.run != null -> RunView(ui.run, ui, vm, Modifier.padding(pad))
            ui.plan != null -> PlanView(ui.plan, ui.preflightFailures, vm, Modifier.padding(pad))
            else -> Overview(ui, vm, Modifier.padding(pad))
        }
    }
    ui.hostKeyPrompt?.let { k -> HostKeyDialog(s?.host ?: "", k, { vm?.acceptHostKey() }, { vm?.rejectHostKey() }) }
    when (ui.passwordPrompt) {
        PasswordKind.LOGIN -> PasswordDialog("Log in to ${s?.host}", "The password for ${s?.user}. It is used for this connection only and never stored.", { vm?.submitPassword(it) }, { vm?.cancelPassword() })
        PasswordKind.SUDO -> PasswordDialog("sudo password", "Installing needs administrator rights on ${s?.host}. The password is sent to sudo for this install only, then forgotten.", { vm?.submitPassword(it) }, { vm?.cancelPassword() })
        null -> {}
    }
    if (ui.offerKeyInstall) {
        AlertDialog(
            onDismissRequest = { vm?.declineKeyInstall() },
            title = { Text("Log in with this phone's key?") },
            text = { Text("The app can add its own SSH key to ~/.ssh/authorized_keys on ${s?.host}. After that it logs in without your password. You will see the exact command first in the transcript.") },
            confirmButton = { TextButton({ vm?.installKey() }) { Text("Add the key") } },
            dismissButton = { TextButton({ vm?.declineKeyInstall() }) { Text("Not now") } },
        )
    }
}

@Composable
private fun Overview(ui: SetupUi, vm: SetupViewModel?, modifier: Modifier) {
    var showScript by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ui.busy?.let { Busy(it) }
        ui.problem?.let { ProblemCard(it) { vm?.dismissProblem() } }
        if (ui.resumable) {
            SectionCard("An install was in progress") {
                Text("Something was still running on the server when the app last closed. Steps already done are skipped, and a download still running is picked up where it is.")
                Button({ vm?.install() }) { Text("Pick it up") }
            }
        }
        val p = ui.profile
        if (p == null) {
            SectionCard("Step 1: read the hardware") {
                Text("A read-only check of the processor, memory, graphics card and disk. It installs nothing and changes nothing.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm?.scan() }, enabled = ui.busy == null) { Text("Scan") }
                    TextButton({ showScript = true }) { Text("View the scan script") }
                }
            }
        } else {
            HardwareCard(p, onRescan = { vm?.scan() }, onViewScript = { showScript = true })
            if (ui.verdicts.isNotEmpty()) SectionCard("What this means") { ui.verdicts.forEach { VerdictRow(it) } }
            ui.recommendation?.let { RecommendationCard(it, ui.intent, ui.chosenTag, vm) }
            ui.benchmark?.let { b ->
                SectionCard("Measured speed") {
                    Text("%.1f tokens per second (measured)".format(b.tokensPerSecond), style = MaterialTheme.typography.headlineSmall)
                    Text("Model loaded in %.1f s.".format(b.loadSeconds))
                    ui.estimateText?.let { Text("Before installing it was estimated as: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            val blocked = ui.verdicts.any { it.severity == Severity.BLOCK }
            SectionCard(if (ui.server?.installState == "installed") "Installed" else "Step 3: install") {
                Text(if (blocked) "This machine can't be set up by the app (see above). The recommendation still applies if you install Ollama yourself."
                else "Next you see every step and the exact script before anything runs.")
                Button({ vm?.preparePlan() }, enabled = !blocked && ui.busy == null && ui.chosenTag != null) { Text("Show the install plan") }
            }
        }
    }
    if (showScript) {
        AlertDialog(onDismissRequest = { showScript = false }, title = { Text("The scan script") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { MonoBlock(ScanParser.script) } },
            confirmButton = { TextButton({ showScript = false }) { Text("Close") } })
    }
}

@Composable
private fun Busy(label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

private fun gb(b: Long?) = b?.let { "%.1f GB".format(it / 1e9) } ?: "unknown"

@Composable
fun HardwareCard(p: HardwareProfile, onRescan: (() -> Unit)?, onViewScript: (() -> Unit)?) {
    SectionCard(if (p.manual) "The machine you described" else "Hardware") {
        Line("System", listOfNotNull(p.os?.prettyName, p.os?.arch).joinToString(", ").ifEmpty { "unknown" })
        Line("Processor", (p.cpu?.model ?: "unknown") + (p.cpu?.physicalCores?.let { ", $it cores" } ?: ""))
        Line("Memory", "${gb(p.memory?.totalBytes)} (${gb(p.memory?.availableBytes)} free)")
        Line("Graphics", p.nvidia?.cards?.takeIf { it.isNotEmpty() }?.joinToString { "${it.name}, ${it.vramMiB?.let { v -> "%.0f GB".format(v / 1024.0) } ?: "?"}" }
            ?: if (p.gpus.isEmpty()) "none found" else p.gpus.joinToString { it.vendor.name.lowercase() })
        Line("Free disk", gb(p.disk?.models?.firstOrNull()?.second?.freeBytes ?: p.disk?.root?.freeBytes))
        p.ollama?.takeIf { it.installed }?.let { Line("Ollama", "${it.version ?: "?"}, ${it.serviceActive ?: "?"}") }
        if (onRescan != null || onViewScript != null) Row {
            onRescan?.let { TextButton(it) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Scan again") } }
            onViewScript?.let { TextButton(it) { Text("View the scan script") } }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row { Text(label, Modifier.width(96.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value) }
}

@Composable
fun RecommendationCard(r: Recommendation, intent: Intent, chosen: String?, vm: SetupViewModel?, onIntent: ((Intent) -> Unit)? = null, onChoose: ((String) -> Unit)? = null) {
    SectionCard("Step 2: choose a model") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Intent.entries.forEach { i ->
                FilterChip(i == intent, { (onIntent ?: { x -> vm?.setIntent(x) })(i) }, label = { Text(when (i) { Intent.CHAT -> "Chat"; Intent.CODE -> "Code"; Intent.ASSISTANT -> "Assistant" }) })
            }
        }
        r.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
        listOfNotNull(r.pick?.let { "Recommended" to it }, r.faster?.let { "Faster" to it }, r.smarter?.let { "Smarter" to it }).forEach { (label, pick) ->
            PickRow(label, pick, pick.candidate.entry.ollamaTag == chosen) { (onChoose ?: { t -> vm?.choose(t) })(pick.candidate.entry.ollamaTag) }
        }
        if (r.pick == null) Text("Nothing in the catalog fits this machine.")
        var all by remember { mutableStateOf(false) }
        val shown = listOfNotNull(r.pick, r.faster, r.smarter).map { it.candidate.entry.id }.toSet()
        val others = r.candidates.filter { it.fits && it.estimate != null && it.entry.id !in shown }
            .sortedWith(compareByDescending<Candidate> { it.entry.quality }.thenByDescending { it.estimate!!.midTps })
        if (others.isNotEmpty()) TextButton({ all = !all }) { Text(if (all) "Hide the other models" else "See every model that fits (${others.size} more)") }
        if (all) others.forEach { c ->
            PickRow("Also fits", Pick(c, ""), c.entry.ollamaTag == chosen) { (onChoose ?: { t -> vm?.choose(t) })(c.entry.ollamaTag) }
        }
        r.bandwidthLabel?.let { Text("Speed is estimated from the processor type ($it). After installing, the app measures it.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PickRow(label: String, pick: Pick, selected: Boolean, onSelect: () -> Unit) {
    val c: Candidate = pick.candidate
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        RadioButton(selected, onSelect)
        Column(Modifier.weight(1f)) {
            Text("$label: ${c.entry.name}", fontWeight = FontWeight.SemiBold)
            Text("${c.estimate?.describe() ?: ""} · download ${gb(c.entry.downloadBytes)}", style = MaterialTheme.typography.bodySmall)
            Text(c.entry.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlanView(plan: List<PlannedStep>, failures: List<String>, vm: SetupViewModel?, modifier: Modifier) {
    var open by remember { mutableStateOf(setOf<Int>()) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Install plan", style = MaterialTheme.typography.titleLarge)
            Text("These are the exact scripts that will run on the server. Tap a step to read it. Steps marked done are already in place and will be skipped.")
        }
        if (failures.isNotEmpty()) item {
            SectionCard("Can't install yet") { failures.forEach { Text("• $it") } }
        }
        items(plan, key = { it.index }) { st ->
            SectionCard("${st.index}. ${st.spec.title}", Modifier.clickable { open = if (st.index in open) open - st.index else open + st.index }) {
                Text(st.spec.why, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (st.alreadyDone) Text("already done", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    if (st.spec.sudo) Text("runs as root", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
                    if (st.spec.detached) Text("runs in the background", style = MaterialTheme.typography.labelMedium)
                }
                if (st.index in open) MonoBlock(st.script)
            }
        }
        item {
            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm?.closePlan() }) { Text("Back") }
                Button({ vm?.install() }, enabled = failures.isEmpty()) { Text(if (plan.all { it.alreadyDone }) "Check again" else "Run these steps") }
            }
        }
    }
}

@Composable
private fun RunView(run: RunUi, ui: SetupUi, vm: SetupViewModel?, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(run.title, style = MaterialTheme.typography.titleLarge)
        ui.busy?.let { Busy(it) }
        ui.problem?.let { ProblemCard(it) { vm?.dismissProblem() } }
        run.steps.forEach { st ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (run.status[st.index]) {
                    StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    StepStatus.OK, StepStatus.DONE_ALREADY -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    StepStatus.FAILED -> Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    StepStatus.UNDONE -> Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                    else -> Spacer(Modifier.size(18.dp))
                }
                Text(st.spec.title + if (run.status[st.index] == StepStatus.DONE_ALREADY) " (already done)" else "")
            }
        }
        run.result?.let { r ->
            Text(r.message, fontWeight = FontWeight.SemiBold, color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!r.ok) {
                    Button({ vm?.install() }) { Text("Retry") }
                    if (r.ran.isNotEmpty()) OutlinedButton({ vm?.undo() }, enabled = !run.undoing) { Text("Undo") }
                }
                TextButton({ vm?.closeRun() }) { Text("Done") }
            }
        }
        Text("Log", style = MaterialTheme.typography.labelLarge)
        MonoBlock(run.log.takeLast(6000).ifEmpty { "(waiting for output)" }, Modifier.weight(1f, fill = false))
    }
}
