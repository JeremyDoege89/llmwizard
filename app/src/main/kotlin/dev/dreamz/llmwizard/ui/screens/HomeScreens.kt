package dev.dreamz.llmwizard.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.dreamz.llmwizard.core.catalog.Intent
import dev.dreamz.llmwizard.data.ServerEntity
import dev.dreamz.llmwizard.data.TranscriptRow
import dev.dreamz.llmwizard.ui.AddServerUi
import dev.dreamz.llmwizard.ui.AddServerViewModel
import dev.dreamz.llmwizard.ui.HostKeyDialog
import dev.dreamz.llmwizard.ui.KeyViewModel
import dev.dreamz.llmwizard.ui.ManualViewModel
import dev.dreamz.llmwizard.ui.MonoBlock
import dev.dreamz.llmwizard.ui.ProblemCard
import dev.dreamz.llmwizard.ui.SectionCard
import dev.dreamz.llmwizard.ui.TranscriptViewModel

@Composable
fun FirstRunScreen(onAccept: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Your own AI, on your own machine", style = MaterialTheme.typography.headlineMedium)
        Text("This app sets up a local AI model on a Linux computer you own, over SSH, and then lets you chat with it from your phone. Nothing goes through anyone else's cloud.")
        SectionCard("Before you start") {
            Text("• It runs commands as root on the machines you point it at. That is how it installs things.")
            Text("• Every command is shown to you first, and you can read the exact script before anything runs.")
            Text("• It keeps a record on the machine of everything it changed, so it can undo it.")
            Text("• Only use it on machines you own or administer.")
            Text("• Your model stays private: by default it only listens on the machine itself, and the app reaches it through the SSH connection.")
        }
        Button(onAccept, Modifier.fillMaxWidth()) { Text("I understand") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(servers: List<ServerEntity>, onOpen: (Long) -> Unit, onAdd: () -> Unit, onManual: () -> Unit, onKey: () -> Unit, onDelete: (Long) -> Unit) {
    var confirmDelete by remember { mutableStateOf<ServerEntity?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Your servers") }, actions = { IconButton(onKey) { Icon(Icons.Default.Lock, "This phone's key") } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add a server") }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (servers.isEmpty()) item {
                SectionCard("No servers yet") {
                    Text("Add a Linux machine you can log in to with SSH: a spare PC, a home server or a virtual machine. You need its address, your user name and password.")
                }
            }
            items(servers, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(s.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            Text("${s.user}@${s.host}${if (s.port != 22) ":${s.port}" else ""}", style = MaterialTheme.typography.bodySmall)
                            Text(when (s.installState) {
                                "installed" -> "Ready" + (s.model?.let { ": $it" } ?: "")
                                "installing" -> "Install in progress"
                                "failed" -> "Install stopped; open to retry"
                                else -> "Not set up yet"
                            }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton({ confirmDelete = s }) { Icon(Icons.Default.Delete, "Remove from the app") }
                    }
                }
            }
            item {
                TextButton(onManual) { Icon(Icons.Default.Search, null); Text("  No server yet? See what a machine could run") }
                Spacer(Modifier.height(80.dp))
            }
        }
    }
    confirmDelete?.let { s ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("Remove ${s.name} from the app?") },
            text = { Text("This only forgets the server here. Nothing on the machine changes. To remove Ollama from it, use Uninstall on the server's screen first.") },
            confirmButton = { TextButton({ onDelete(s.id); confirmDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerRoute(vm: AddServerViewModel, onBack: () -> Unit, onDone: (Long) -> Unit) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(ui.doneServerId) { ui.doneServerId?.let(onDone) }
    AddServerScreen(ui, onBack, { n, h, p, u, pw -> vm.connect(n, h, p, u, pw) }, vm::dismissProblem)
    ui.hostKeyPrompt?.let { HostKeyDialog("the server", it, vm::acceptHostKey, vm::rejectHostKey) }
    if (ui.offerKey) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.installKey(false) },
            title = { Text("Log in with this phone's key?") },
            text = { Text("The app can add its own SSH key to ~/.ssh/authorized_keys on the server, using the password you just typed one last time. After that it never needs your password to log in. The command is recorded in this server's transcript.") },
            confirmButton = { TextButton({ vm.installKey(true) }) { Text("Add the key") } },
            dismissButton = { TextButton({ vm.installKey(false) }) { Text("Not now") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(ui: AddServerUi, onBack: () -> Unit, onConnect: (String, String, Int, String, CharArray) -> Unit, onDismissProblem: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Scaffold(topBar = { TopAppBar(title = { Text("Add a server") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ui.problem?.let { ProblemCard(it, onDismissProblem) }
            OutlinedTextField(host, { host = it.trim() }, label = { Text("Address (IP or name)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("On the machine, hostname -I shows it.") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it.trim() }, label = { Text("User name") }, singleLine = true, modifier = Modifier.weight(2f))
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("Used to log in this once. It is never stored; afterwards the app can use its own key.") })
            OutlinedTextField(name, { name = it }, label = { Text("Name for this server (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button({ val pw = password.toCharArray(); password = ""; onConnect(name, host, port.toIntOrNull() ?: 22, user, pw) },
                enabled = !ui.busy && host.isNotBlank() && user.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Connect") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualRoute(vm: ManualViewModel, onBack: () -> Unit) {
    val rec by vm.rec.collectAsState()
    var ram by remember { mutableStateOf("16") }
    var cores by remember { mutableStateOf("4") }
    var kind by remember { mutableStateOf(vm.cpuKinds.getOrElse(6) { vm.cpuKinds.first() }) }
    var avx2 by remember { mutableStateOf(true) }
    var vram by remember { mutableStateOf("0") }
    var gpu by remember { mutableStateOf("") }
    var disk by remember { mutableStateOf("100") }
    var intent by remember { mutableStateOf(Intent.CHAT) }
    var expanded by remember { mutableStateOf(false) }
    fun run() = vm.compute(ram.toDoubleOrNull() ?: 0.0, cores.toIntOrNull() ?: 1, kind, avx2, vram.toDoubleOrNull() ?: 0.0, gpu, disk.toDoubleOrNull() ?: 0.0, intent)
    Scaffold(topBar = { TopAppBar(title = { Text("What could a machine run?") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Describe the computer. No server or connection is needed; the estimate uses the same rules as a real scan.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ram, { ram = it }, label = { Text("Memory (GB)") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(cores, { cores = it.filter(Char::isDigit) }, label = { Text("CPU cores") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            ExposedDropdownMenuBox(expanded, { expanded = it }) {
                OutlinedTextField(kind, {}, readOnly = true, label = { Text("Processor type") }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                ExposedDropdownMenu(expanded, { expanded = false }) {
                    vm.cpuKinds.forEach { k -> DropdownMenuItem({ Text(k) }, { kind = k; expanded = false }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(avx2, { avx2 = it }); Text("Made after about 2014 (has AVX2)") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vram, { vram = it }, label = { Text("NVIDIA memory (GB, 0 = none)") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(gpu, { gpu = it }, label = { Text("Card (e.g. RTX 3060)") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(disk, { disk = it }, label = { Text("Free disk (GB)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Button(::run, Modifier.fillMaxWidth()) { Text("Show what fits") }
            rec?.let { r -> RecommendationCard(r, intent, r.pick?.candidate?.entry?.ollamaTag, null, onIntent = { intent = it; run() }, onChoose = {}) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptRoute(vm: TranscriptViewModel, onBack: () -> Unit) {
    val rows by vm.rows.collectAsState(emptyList())
    var open by remember { mutableStateOf<Long?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("Transcript") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Every script the app sent to this server, newest first, with its exit code. Passwords are never part of it.", style = MaterialTheme.typography.bodySmall) }
            if (rows.isEmpty()) item { Text("Nothing yet.") }
            items(rows, key = { it.id }) { r -> TranscriptItem(r, open == r.id) { open = if (open == r.id) null else r.id } }
        }
    }
}

@Composable
private fun TranscriptItem(r: TranscriptRow, expanded: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row { Text(r.kind, Modifier.weight(1f), fontWeight = FontWeight.Medium); Text("exit ${r.exitCode ?: "?"}", color = if (r.exitCode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            Text(r.at.replace('T', ' ').take(19), style = MaterialTheme.typography.bodySmall)
            if (expanded) { MonoBlock(r.script); if (r.outputTail.isNotBlank()) MonoBlock(r.outputTail) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyRoute(vm: KeyViewModel, onBack: () -> Unit) {
    val key by vm.key.collectAsState()
    val ctx = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("This phone's key") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("The app logs in with this key once it is on a server. The private half is encrypted with a key held by Android's keystore and never leaves the phone.")
            key?.let { (pub, fp) ->
                Text("Fingerprint", style = MaterialTheme.typography.labelLarge); MonoBlock(fp)
                Text("Public key (safe to share)", style = MaterialTheme.typography.labelLarge); MonoBlock(pub)
                Button({ (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("public key", pub)) }) { Text("Copy public key") }
                Text("To add it yourself: append that line to ~/.ssh/authorized_keys on the server.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
