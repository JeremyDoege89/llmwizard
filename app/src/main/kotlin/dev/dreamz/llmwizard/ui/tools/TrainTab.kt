package dev.dreamz.llmwizard.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dreamz.llmwizard.core.training.JobState
import dev.dreamz.llmwizard.core.training.TrainingStatus
import dev.dreamz.llmwizard.ui.MonoBlock
import dev.dreamz.llmwizard.ui.SectionCard
import dev.dreamz.llmwizard.ui.TrainingUi
import dev.dreamz.llmwizard.ui.TrainingViewModel

/** BUILD_PLAN E3: datasets, jobs and their progress, and a new-job form with an honest time estimate. */
@Composable
fun TrainTab(ui: TrainingUi, vm: TrainingViewModel?) {
    val context = LocalContext.current
    var newJob by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "dataset"
        val text = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrElse { "" }
        vm?.check(name, text)
    }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Fine-tuning on this server") {
                Text("Teach a model your own material with LoRA: a small add-on trained on top of a model, on this server's processor. It is slow (hours for a 7B model), and the estimate tells you before you start.")
                when (ui.stackInstalled) {
                    null -> Text("Checking whether the tools are installed…", style = MaterialTheme.typography.bodySmall)
                    false -> {
                        Text("The training tools are not installed yet (about 2 GB in your home folder on the server).")
                        Button({ vm?.installStack() }) { Text("Install the fine-tuning tools") }
                    }
                    true -> Text("Tools installed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            SectionCard("Datasets") {
                Text("JSONL with chat messages, JSONL with instruction/input/output, or plain text. Checked on the phone before upload.", style = MaterialTheme.typography.bodySmall)
                if (ui.datasets.isEmpty()) Text("None yet.")
                ui.datasets.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontWeight = FontWeight.Medium)
                            Text("${d.format.label} · ${d.sampleCount} samples · ~${"%,d".format(d.approxTokens)} tokens", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton({ vm?.deleteDataset(d.id) }) { Text("Delete") }
                    }
                }
                OutlinedButton({ picker.launch(arrayOf("application/json", "application/x-ndjson", "text/*", "application/octet-stream")) }) { Text("Add a dataset…") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Jobs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button({ newJob = true }, enabled = ui.stackInstalled == true && ui.datasets.isNotEmpty()) { Text("New job") }
            }
        }
        if (ui.jobs.isEmpty()) item { Text("No jobs yet.", style = MaterialTheme.typography.bodySmall) }
        items(ui.jobs, key = { it.job.id }) { j -> JobCard(j) { vm?.open(j.job.id) } }
    }
    ui.checked?.let { (name, r) ->
        AlertDialog(onDismissRequest = { vm?.discardChecked() }, title = { Text("Dataset: $name") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${r.format?.label ?: "Unknown format"} · ${r.samples} samples · ~${"%,d".format(r.approxTokens)} tokens")
                    r.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                    r.preview.forEach { MonoBlock(it) }
                }
            },
            confirmButton = { TextButton({ vm?.upload() }, enabled = r.ok) { Text("Upload") } },
            dismissButton = { TextButton({ vm?.discardChecked() }) { Text("Cancel") } })
    }
    ui.openJob?.let { id -> ui.jobs.firstOrNull { it.job.id == id }?.let { JobDialog(it, ui.openLog, vm) } }
    if (newJob && vm != null) NewJobDialog(ui, vm) { newJob = false }
}

@Composable
fun JobCard(j: JobState, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${j.job.baseModel} → ${j.job.outputName}", style = MaterialTheme.typography.titleSmall)
            if (j.status == TrainingStatus.RUNNING) LinearProgressIndicator({ j.progress?.fraction?.toFloat() ?: 0f }, Modifier.fillMaxWidth())
            Text(j.summary, style = MaterialTheme.typography.bodyMedium,
                color = if (j.status == TrainingStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun JobDialog(j: JobState, log: String, vm: TrainingViewModel?) {
    AlertDialog(onDismissRequest = { vm?.open(null) }, title = { Text(j.job.outputName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(j.summary)
                j.progress?.let { p ->
                    Text("Stage: ${p.stage.name.lowercase()} · ${p.elapsedSeconds / 60} min so far" + (p.tokensPerSecond?.let { " · %.1f tokens/s (measured)".format(it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Base ${j.job.baseModel} (${j.job.hfRepo}), ${j.job.epochs} epochs, LoRA rank ${j.job.loraRank}, merged and quantized to ${j.job.quantize}",
                    style = MaterialTheme.typography.bodySmall)
                MonoBlock(log.lines().filterNot { "it/s]" in it }.takeLast(40).joinToString("\n").ifEmpty { "(no log yet)" })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (j.status) {
                        TrainingStatus.RUNNING -> OutlinedButton({ vm?.cancel(j.job.id) }) { Text("Stop (saves a checkpoint)") }
                        TrainingStatus.COMPLETED -> {}
                        else -> Button({ vm?.resume(j.job.id) }) { Text(if (j.status == TrainingStatus.PENDING) "Start" else "Resume") }
                    }
                    if (j.status != TrainingStatus.RUNNING) TextButton({ vm?.delete(j.job.id) }) { Text("Delete job") }
                }
            }
        },
        confirmButton = { TextButton({ vm?.open(null) }) { Text("Close") } })
}

@Composable
fun NewJobDialog(ui: TrainingUi, vm: TrainingViewModel, onClose: () -> Unit) {
    val bases = vm.trainable
    var base by remember { mutableStateOf(bases.minByOrNull { it.paramsB }) }
    var dataset by remember { mutableStateOf(ui.datasets.firstOrNull()?.id) }
    var name by remember { mutableStateOf("") }
    var epochs by remember { mutableStateOf("3") }
    var maxLen by remember { mutableStateOf("1024") }
    var rank by remember { mutableStateOf(16) }
    var lr by remember { mutableStateOf("0.0002") }
    var quant by remember { mutableStateOf("q4_K_M") }
    var showScript by remember { mutableStateOf(false) }
    val b = base
    val job = if (b != null && dataset != null) vm.newJob(b, dataset!!, name.ifBlank { b.ollamaTag.substringBefore(':') + "-mine:latest" },
        epochs.toIntOrNull() ?: 0, quant, maxLen.toIntOrNull() ?: 0, rank, lr.toDoubleOrNull() ?: 0.0) else null
    val problems = job?.problems().orEmpty()
    val est = job?.takeIf { problems.isEmpty() }?.let { vm.estimate(it) }
    AlertDialog(onDismissRequest = onClose, title = { Text("New fine-tuning job") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Base model", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { bases.forEach { e -> FilterChip(base == e, { base = e }, label = { Text(e.name) }) } }
                Text("The base must also be installed in Ollama with the same name.", style = MaterialTheme.typography.bodySmall)
                Text("Dataset", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ui.datasets.forEach { d -> FilterChip(dataset == d.id, { dataset = d.id }, label = { Text(d.name) }) } }
                OutlinedTextField(name, { name = it.lowercase() }, label = { Text("New model name, e.g. mybot:latest") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(epochs, { epochs = it }, label = { Text("Epochs") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(maxLen, { maxLen = it }, label = { Text("Max tokens") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                OutlinedTextField(lr, { lr = it }, label = { Text("Learning rate") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Text("LoRA rank (bigger learns more, needs more data)", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(8, 16, 32, 64).forEach { r -> FilterChip(rank == r, { rank = r }, label = { Text("$r") }) } }
                Text("Size of the result", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("q4_K_M" to "Q4 (small)", "q6_K" to "Q6", "q8_0" to "Q8", "f16" to "Full").forEach { (q, l) -> FilterChip(quant == q, { quant = q }, label = { Text(l) }) }
                }
                problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                est?.let { Text(it.explanation); Text(it.gpuComparison, style = MaterialTheme.typography.bodySmall) }
                if (job != null && problems.isEmpty()) {
                    TextButton({ showScript = !showScript }) { Text(if (showScript) "Hide what will run" else "Show what will run") }
                    if (showScript) MonoBlock(vm.preview(job).take(20_000))
                }
            }
        },
        confirmButton = { TextButton({ job?.let { vm.start(it) }; onClose() }, enabled = job != null && problems.isEmpty()) { Text("Start") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } })
}
