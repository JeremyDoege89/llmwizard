package dev.dreamz.llmwizard

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.dreamz.llmwizard.core.recipe.MemoryResumeStore
import dev.dreamz.llmwizard.core.recipe.Recipe
import dev.dreamz.llmwizard.core.server.Server
import dev.dreamz.llmwizard.core.ssh.Auth
import dev.dreamz.llmwizard.core.ssh.GeneratedKey
import dev.dreamz.llmwizard.core.ssh.HostKeyPolicy
import dev.dreamz.llmwizard.core.ssh.Keys
import dev.dreamz.llmwizard.core.ssh.MemoryHostKeyStore
import dev.dreamz.llmwizard.core.ssh.SshjTransport
import dev.dreamz.llmwizard.core.ssh.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Spike B (PLAN.md Phase 0): generate an Ed25519 key, use a password once to install it on
 * the server, then log in with the key and run a command. Throwaway; Phase 2 replaces it.
 *
 * For unattended testing the fields can be prefilled with intent extras host, port, user,
 * password, and `auto=true` runs the whole sequence; results also go to logcat (tag Spike).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val x = intent.extras
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    SpikeScreen(
                        initialHost = x?.getString("host") ?: "",
                        initialPort = x?.getString("port") ?: "22",
                        initialUser = x?.getString("user") ?: "",
                        initialPassword = x?.getString("password") ?: "",
                        auto = x?.getString("auto") == "true",
                    )
                }
            }
        }
    }
}

private val hostKeys = MemoryHostKeyStore()

@Composable
private fun SpikeScreen(initialHost: String, initialPort: String, initialUser: String, initialPassword: String, auto: Boolean) {
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort) }
    var user by remember { mutableStateOf(initialUser) }
    var password by remember { mutableStateOf(initialPassword) }
    var key by remember { mutableStateOf<GeneratedKey?>(null) }
    var output by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun log(s: String) {
        Log.i("Spike", s)
        output += s + "\n"
    }

    fun target() = Target(host.trim(), port.trim().toIntOrNull() ?: 22, user.trim())

    suspend fun generate() {
        val k = Keys.generateEd25519("llmwizard-spike")
        key = k
        log("Generated key ${k.fingerprint}\n${k.publicKeyLine}")
    }

    suspend fun addKey() {
        val k = key ?: return log("Generate a key first")
        val pw = Auth.Password(password.toCharArray())
        try {
            SshjTransport().connect(target(), pw, HostKeyPolicy(hostKeys, acceptNew = true)).use { s ->
                log("Connected with password. Host key ${s.hostKey.keyType} ${s.hostKey.fingerprint}")
                val server = Server(s, MemoryResumeStore())
                val out = server.runRecipe(Recipe.bundled("authorized-key-add"), mapOf("publicKey" to k.publicKeyLine), null,
                    approve = { _, _ -> true }, events = {})
                log("authorized-key-add: ${out.result?.message}")
            }
        } catch (e: Exception) {
            log("FAILED (password): ${e::class.simpleName}: ${e.message}")
        } finally {
            pw.wipe()
        }
    }

    suspend fun runWithKey() {
        val k = key ?: return log("Generate a key first")
        try {
            SshjTransport().connect(target(), Auth.PrivateKey(k.privateKeyOpenSsh), HostKeyPolicy(hostKeys, acceptNew = true)).use { s ->
                log("Connected with the app's key. Host key ${s.hostKey.fingerprint}")
                val r = Server(s, MemoryResumeStore()).remote.probe("uname -a", 20.seconds)
                log("uname -a (exit ${r.exitCode}):\n${r.out}")
                log("SPIKE OK")
            }
        } catch (e: Exception) {
            log("FAILED (key): ${e::class.simpleName}: ${e.message}")
        }
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) { scope.launch(block = block) }

    androidx.compose.runtime.LaunchedEffect(auto) {
        if (auto) { generate(); addKey(); runWithKey() }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("llmwizard: SSH spike", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(host, { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(port, { port = it }, label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(user, { user = it }, label = { Text("User") }, modifier = Modifier.weight(2f), singleLine = true)
        }
        OutlinedTextField(password, { password = it }, label = { Text("Password (used once)") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ launch { generate() } }) { Text("1. Key") }
            Button({ launch { addKey() } }) { Text("2. Add key") }
            Button({ launch { runWithKey() } }) { Text("3. Run") }
        }
        Text(output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}
