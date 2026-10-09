package dev.dreamz.llmwizard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dreamz.llmwizard.ui.ManageUi
import dev.dreamz.llmwizard.ui.ManageViewModel
import dev.dreamz.llmwizard.ui.MonoBlock
import dev.dreamz.llmwizard.ui.PasswordDialog
import dev.dreamz.llmwizard.ui.PasswordKind
import dev.dreamz.llmwizard.ui.ProblemCard
import dev.dreamz.llmwizard.ui.SectionCard

private fun gb(b: Long?) = b?.let { "%.1f GB".format(it / 1e9) } ?: "unknown"

@Composable
fun ManageRoute(vm: ManageViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    ManageScreen(ui, vm, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageScreen(ui: ManageUi, vm: ManageViewModel?, onBack: () -> Unit) {
    var pullTag by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    val st = ui.status
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text("Server"); ui.server?.let { Text(it.name, style = MaterialTheme.typography.bodySmall) } } },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton({ vm?.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ui.busy?.let { Column { Text(it); LinearProgressIndicator(Modifier.fillMaxWidth()) } }
            ui.problem?.let { ProblemCard(it) { vm?.dismissProblem() } }

            SectionCard("Ollama service") {
                Text(when (st?.active) { "active" -> "Running"; null -> "Unknown"; else -> "Not running (${st.active})" },
                    fontWeight = FontWeight.SemiBold, color = if (st?.active == "active") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                st?.ollamaVersion?.let { Text("Version $it") }
                st?.bind?.let { Text("Listening on $it" + if (it.startsWith("127.")) " (this machine only)" else "") }
                st?.load1?.let { Text("Load %.2f on %s CPUs".format(it, st.cpus ?: "?")) }
                if (st != null) Text("Memory ${gb(st.memAvailableBytes)} free of ${gb(st.memTotalBytes)} · Disk ${gb(st.diskFreeBytes)} free")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm?.service("start") }, enabled = st?.active != "active") { Text("Start") }
                    OutlinedButton({ vm?.service("restart") }) { Text("Restart") }
                    OutlinedButton({ vm?.service("stop") }, enabled = st?.active == "active") { Text("Stop") }
                }
                TextButton({ showLog = !showLog }) { Text(if (showLog) "Hide recent log" else "Show recent log") }
                if (showLog) {
                    if (st?.logReadable == false) Text("This user can't read the system log. The optional sudo rule below allows it.", style = MaterialTheme.typography.bodySmall)
                    MonoBlock(st?.log?.joinToString("\n")?.ifEmpty { "(empty)" } ?: "(not read yet)")
                }
            }

            SectionCard("Models") {
                if (ui.models.isEmpty()) Text("No models listed (is the service running?).")
                ui.models.forEach { m ->
                    val loaded = ui.loaded.any { it.name == m.name }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.Medium)
                            Text("${gb(m.size)} · ${m.details?.parameterSize ?: "?"} · ${m.details?.quantizationLevel ?: "?"}" + if (loaded) " · loaded" else "", style = MaterialTheme.typography.bodySmall)
                        }
                        if (loaded) TextButton({ vm?.unload(m.name) }) { Text("Unload") }
                        TextButton({ confirmDelete = m.name }) { Text("Delete") }
                    }
                }
                OutlinedTextField(pullTag, { pullTag = it.trim() }, label = { Text("Download a model (e.g. qwen3:4b)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                if (ui.recommendedTags.isNotEmpty()) Text("Fits this machine: " + ui.recommendedTags.take(6).joinToString(", "), style = MaterialTheme.typography.bodySmall)
                Button({ vm?.pull(pullTag); pullTag = "" }, enabled = pullTag.isNotBlank()) { Text("Download") }
            }

            ConnectSection(ui.exposure, ui.models.firstOrNull()?.name, { vm?.loadExposure() }, { vm?.expose(it) }, { vm?.goPrivate() })

            var ctx by remember(ui.manifest) { mutableStateOf(ui.manifest?.settings?.get("settings.contextLength") ?: "8192") }
            var keep by remember(ui.manifest) { mutableStateOf(ui.manifest?.settings?.get("settings.keepAlive") ?: "30m") }
            SectionCard("Settings") {
                Text("Changing these restarts Ollama, which unloads any loaded model.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(ctx, { ctx = it.filter(Char::isDigit) }, label = { Text("Context (tokens)") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(keep, { keep = it.trim() }, label = { Text("Keep loaded") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Button({ vm?.configure(ctx.toIntOrNull() ?: 8192, keep) }) { Text("Apply") }
            }

            SectionCard("Maintenance") {
                Button({ vm?.update() }) { Text("Update Ollama") }
                if (ui.manifest?.has("file", "/etc/sudoers.d/llmwizard-ollama") != true) {
                    Text("Optional: let the app start, stop and restart Ollama and read its log without your sudo password. It adds one narrow sudo rule, checked with visudo first; uninstall removes it.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ vm?.addSudoRule() }) { Text("Add the sudo rule") }
                } else Text("The no-password sudo rule is installed.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ confirmUninstall = true }) { Text("Uninstall…") }
                OutlinedButton({ showDiag = true }) { Text("Diagnostic report…") }
            }
        }
    }
    ui.action?.let { a ->
        AlertDialog(onDismissRequest = { if (!a.running) vm?.closeAction() }, title = { Text(a.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (a.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    a.message?.let { Text(it, color = if (a.ok == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                    MonoBlock(a.log.takeLast(4000).ifEmpty { "(waiting for output)" })
                }
            },
            confirmButton = { TextButton({ vm?.closeAction() }, enabled = !a.running) { Text("Close") } })
    }
    if (showDiag && vm != null) DiagnosticDialog(vm) { showDiag = false }
    confirmDelete?.let { m ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete $m?") }, text = { Text("It is removed from the server. You can download it again later.") },
            confirmButton = { TextButton({ vm?.delete(m); confirmDelete = null }) { Text("Delete") } }, dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } })
    }
    if (confirmUninstall) {
        var withModels by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { confirmUninstall = false }, title = { Text("Remove Ollama?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Only what llmwizard installed is removed, as recorded on the server. An Ollama you installed yourself is left alone.")
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(withModels, { withModels = it }); Text("Also delete the models") }
                    if (withModels) {
                        Text("These will be deleted:", fontWeight = FontWeight.Medium)
                        if (ui.models.isEmpty()) Text("(could not list them; the whole models folder goes)")
                        ui.models.forEach { Text("• ${it.name}, ${gb(it.size)}") }
                    }
                }
            },
            confirmButton = { TextButton({ vm?.uninstall(withModels); confirmUninstall = false }) { Text("Remove") } },
            dismissButton = { TextButton({ confirmUninstall = false }) { Text("Cancel") } })
    }
    when (ui.passwordPrompt) {
        PasswordKind.LOGIN -> PasswordDialog("Log in to ${ui.server?.host}", "The password for ${ui.server?.user}. Never stored.", { vm?.submitPassword(it) }, { vm?.cancelPassword() })
        PasswordKind.SUDO -> PasswordDialog("sudo password", "This change needs administrator rights. The password goes to sudo for this action only.", { vm?.submitPassword(it) }, { vm?.cancelPassword() })
        null -> {}
    }
}

@Composable
private fun DiagnosticDialog(vm: ManageViewModel, onClose: () -> Unit) {
    var redact by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(redact) { text = vm.diagnostics(redact) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = onClose, title = { Text("Diagnostic report") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This is everything that would be shared. Nothing is sent anywhere by the app; copy it and send it yourself if you want help.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(redact, { redact = it }); Text("Hide addresses, host and user names") }
                MonoBlock(text.ifEmpty { "…" })
            }
        },
        confirmButton = { TextButton({
            (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("llmwizard report", text)); onClose()
        }) { Text("Copy") } },
        dismissButton = { TextButton(onClose) { Text("Close") } })
}
