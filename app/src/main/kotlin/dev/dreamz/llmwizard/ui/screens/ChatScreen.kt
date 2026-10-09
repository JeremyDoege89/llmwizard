package dev.dreamz.llmwizard.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.dreamz.llmwizard.data.ConversationEntity
import dev.dreamz.llmwizard.data.MessageEntity
import dev.dreamz.llmwizard.ui.ChatUi
import dev.dreamz.llmwizard.ui.ChatViewModel
import dev.dreamz.llmwizard.ui.MonoBlock
import dev.dreamz.llmwizard.ui.PasswordDialog
import dev.dreamz.llmwizard.ui.ProblemCard
import dev.dreamz.llmwizard.ui.ReplyPhase

@Composable
fun ChatRoute(vm: ChatViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val messages by vm.messages.collectAsState()
    val conversations by vm.conversations.collectAsState(emptyList())
    ChatScreen(ui, messages, conversations, onBack, vm::send, vm::stop, vm::chooseModel, vm::open, vm::deleteConversation, vm::dismissProblem)
    if (ui.needsPassword) PasswordDialog("Log in to ${ui.server?.host}", "The password for ${ui.server?.user}, for this session only.", vm::submitPassword, vm::cancelPassword)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    ui: ChatUi, messages: List<MessageEntity>, conversations: List<ConversationEntity>,
    onBack: () -> Unit, onSend: (String) -> Unit, onStop: () -> Unit, onModel: (String) -> Unit,
    onOpen: (Long?) -> Unit, onDelete: (Long) -> Unit, onDismissProblem: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, ui.partial.length / 200) { if (messages.isNotEmpty() || ui.partial.isNotEmpty()) list.animateScrollToItem(maxOf(0, messages.size + (if (ui.partial.isNotEmpty()) 1 else 0) - 1)) }
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column(Modifier.combinedClickable(onClick = { modelMenu = true })) {
                    Text("Chat")
                    Text((ui.model ?: "no model") + " ▾", style = MaterialTheme.typography.bodySmall)
                }
                DropdownMenu(modelMenu, { modelMenu = false }) {
                    ui.models.forEach { m -> DropdownMenuItem({ Text(m.name) }, { onModel(m.name); modelMenu = false }) }
                }
            },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton({ onOpen(null) }) { Icon(Icons.Default.Add, "New conversation") }
                IconButton({ history = true }) { Icon(Icons.AutoMirrored.Filled.List, "Conversations") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            ui.problem?.let { Box(Modifier.padding(12.dp)) { ProblemCard(it, onDismissProblem) } }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (messages.isEmpty() && ui.partial.isEmpty()) item {
                    Text("Ask anything. Replies come from ${ui.model ?: "your model"} on ${ui.server?.name ?: "your server"}, through the SSH connection.",
                        Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(messages, key = { it.id }) { m -> Bubble(m.role, m.content, m.tokensPerSecond) }
                if (ui.partial.isNotEmpty()) item { Bubble("assistant", ui.partial, null) }
            }
            if (ui.phase != ReplyPhase.IDLE) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Text(when (ui.phase) {
                        ReplyPhase.CONNECTING -> "Connecting…"
                        ReplyPhase.LOADING_MODEL -> "Loading the model into memory (can take a minute on a CPU)…"
                        ReplyPhase.THINKING -> "Reading your message…"
                        else -> "Writing…"
                    }, style = MaterialTheme.typography.labelMedium)
                    if (ui.phase != ReplyPhase.GENERATING) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 5)
                if (ui.phase == ReplyPhase.IDLE) IconButton({ onSend(input); input = "" }, enabled = input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                else IconButton(onStop) { Icon(Icons.Default.Close, "Stop") }
            }
        }
    }
    if (history) {
        ModalBottomSheet({ history = false }) {
            Text("Conversations", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            if (conversations.isEmpty()) Text("None yet.", Modifier.padding(16.dp))
            conversations.forEach { c ->
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(c.id); history = false }).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(c.title, maxLines = 1); Text(c.model, style = MaterialTheme.typography.bodySmall) }
                    IconButton({ onDelete(c.id) }) { Icon(Icons.Default.Delete, "Delete conversation") }
                }
            }
            TextButton({ history = false }, Modifier.padding(16.dp)) { Text("Close") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(role: String, text: String, tps: Double?) {
    val mine = role == "user"
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Card(
            Modifier.widthIn(max = 340.dp).combinedClickable(onClick = {}, onLongClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("message", text))
            }),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { MarkdownLite(text) }
        }
        tps?.let { Text("%.1f tokens/s (measured)".format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Just enough markdown for chat: fenced code blocks, `inline code` and **bold**. Long-press copies. */
@Composable
fun MarkdownLite(text: String) {
    val parts = text.split("```")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) MonoBlock(part.substringAfter('\n', part).trimEnd())
        else if (part.isNotBlank()) Text(inline(part.trim('\n')))
    }
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val pattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
    for (m in pattern.findAll(s)) {
        append(s.substring(i, m.range.first))
        if (m.groupValues[1].isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(m.groupValues[2]) }
        i = m.range.last + 1
    }
    append(s.substring(i))
}
