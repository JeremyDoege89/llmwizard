package dev.dreamz.llmwizard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.dreamz.llmwizard.core.scan.Severity
import dev.dreamz.llmwizard.core.scan.Verdict
import dev.dreamz.llmwizard.core.ssh.KnownHost
import dev.dreamz.llmwizard.ui.theme.Mono

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun ProblemCard(p: Problem, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(p.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Text(p.message, color = MaterialTheme.colorScheme.onErrorContainer)
            Text("What to do: " + p.nextStep, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Medium)
            TextButton(onDismiss, Modifier.align(Alignment.End)) { Text("OK") }
        }
    }
}

@Composable
fun VerdictRow(v: Verdict) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val (icon, tint) = when (v.severity) {
            Severity.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.primary
            Severity.WARN -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary
            Severity.BLOCK -> Icons.Default.Warning to MaterialTheme.colorScheme.error
        }
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(v.message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun HostKeyDialog(host: String, key: KnownHost, onAccept: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text("Trust this server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("First connection to $host. Its ${key.keyType} key fingerprint is:")
                SelectionContainer { Text(key.fingerprint, style = Mono) }
                Text("To check, run this on the server and look for the same line:")
                SelectionContainer { Text("for f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf \"\$f\"; done", style = Mono) }
                Text("If it ever changes, the app will refuse to connect.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onAccept) { Text("It matches, trust it") } },
        dismissButton = { TextButton(onReject) { Text("Cancel") } },
    )
}

/** Asks for a password. The text field's buffer is the only copy outside the CharArray handed back. */
@Composable
fun PasswordDialog(title: String, explanation: String, onSubmit: (CharArray) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(explanation)
                OutlinedTextField(text, { text = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton({ val pw = text.toCharArray(); text = ""; onSubmit(pw) }, enabled = text.isNotEmpty()) { Text("Continue") } },
        dismissButton = { TextButton({ text = ""; onCancel() }) { Text("Cancel") } },
    )
}

@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        SelectionContainer { Text(text, style = Mono, modifier = Modifier.padding(12.dp)) }
    }
}
