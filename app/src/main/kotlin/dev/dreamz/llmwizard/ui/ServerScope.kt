package dev.dreamz.llmwizard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dreamz.llmwizard.conn.Servers
import dev.dreamz.llmwizard.core.recipe.EngineEvent
import dev.dreamz.llmwizard.core.recipe.Recipe
import dev.dreamz.llmwizard.core.remote.Privilege
import dev.dreamz.llmwizard.core.server.PrivilegeProblem
import dev.dreamz.llmwizard.core.server.Server
import dev.dreamz.llmwizard.data.AuthType
import dev.dreamz.llmwizard.data.ServerEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The connection-related part of a screen's state, shared by the Models, Train and Agents tabs. */
data class ScopeUi(
    val server: ServerEntity? = null,
    val busy: String? = null,
    val problem: Problem? = null,
    val passwordPrompt: PasswordKind? = null,
    val action: ActionUi? = null,
)

/**
 * Connects to one server for a ViewModel: reuses a live session, asks for the login password
 * when needed, runs recipes with the sudo prompt and a live log. The same flow as the server
 * screen, in one place for the newer screens.
 */
class ServerScope(private val scope: CoroutineScope, private val servers: Servers, private val serverId: Long) {
    private val _ui = MutableStateFlow(ScopeUi())
    val ui: StateFlow<ScopeUi> = _ui
    private var pending: (suspend (CharArray) -> Unit)? = null
    private var job: Job? = null

    suspend fun load(): ServerEntity? = servers.db.servers().get(serverId)?.also { s -> _ui.update { it.copy(server = s) } }

    private fun entity() = checkNotNull(_ui.value.server) { "server not loaded" }

    /** Runs [block] with a connected server. One at a time; [quiet] skips the busy banner (polling). */
    fun launch(label: String, quiet: Boolean = false, block: suspend (Server) -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (_ui.value.server == null) load()
            if (!quiet) _ui.update { it.copy(busy = label, problem = null) }
            try {
                val srv = try {
                    servers.open(entity())
                } catch (e: dev.dreamz.llmwizard.core.ssh.SshError.AuthFailed) {
                    if (entity().authType != AuthType.PASSWORD) throw e
                    pending = { pw -> try { block(servers.open(entity(), pw)) } catch (e: Exception) { fail(e) } finally { _ui.update { it.copy(busy = null) } } }
                    _ui.update { it.copy(passwordPrompt = PasswordKind.LOGIN) }
                    return@launch
                }
                block(srv)
            } catch (e: Exception) {
                if (!quiet) fail(e)
            } finally {
                if (_ui.value.passwordPrompt == null) _ui.update { it.copy(busy = null) }
            }
        }
    }

    fun fail(e: Throwable) = _ui.update { it.copy(problem = describe(e)) }

    /** Runs a bundled recipe, asking for the sudo password when it needs root and the server needs one. */
    fun runRecipe(recipeId: String, params: Map<String, String> = emptyMap(), after: (suspend (Server) -> Unit)? = null) =
        launch("Starting…") { srv -> runWith(srv, recipeId, params, null, after) }

    /** Runs a recipe on a server already connected inside [launch]. */
    suspend fun runRecipeFrom(srv: Server, recipeId: String, params: Map<String, String>) = runWith(srv, recipeId, params, null, null)

    private suspend fun runWith(srv: Server, recipeId: String, params: Map<String, String>, sudo: CharArray?, after: (suspend (Server) -> Unit)?) {
        val recipe = Recipe.bundled(recipeId)
        val root = recipe.steps.any { it.sudo }
        val privilege = if (!root) null else try {
            srv.privilege { sudo }
        } catch (e: PrivilegeProblem.PasswordNeeded) {
            pending = { pw -> try { runWith(srv, recipeId, params, pw, after) } catch (e: Exception) { fail(e) } }
            _ui.update { it.copy(passwordPrompt = PasswordKind.SUDO) }
            return
        }
        try {
            _ui.update { it.copy(action = ActionUi(recipe.title)) }
            val out = srv.runRecipe(recipe, params, privilege, approve = { failures, _ -> failures.isEmpty() }) { ev ->
                val line = when (ev) {
                    is EngineEvent.StepStarted -> "\n▶ ${ev.step.spec.title}\n"
                    is EngineEvent.StepSkipped -> "✓ ${ev.step.spec.title} (already done)\n"
                    is EngineEvent.Log -> SetupViewModel.cleanLog(ev.text)
                    is EngineEvent.StepFinished -> if (ev.exitCode == 0) "" else "exit ${ev.exitCode}\n"
                    is EngineEvent.VerifyFailed -> "The check after this step failed.\n"
                    else -> ""
                }
                _ui.update { u -> u.copy(action = u.action?.copy(log = (u.action.log + line).takeLast(32_000))) }
            }
            val r = out.result
            val message = r?.message ?: out.engine.preflight(recipe, out.values).filterNot { it.ok }.joinToString(" ") { it.check.failMessage }.ifEmpty { "A check before running failed." }
            _ui.update { u -> u.copy(action = u.action?.copy(running = false, ok = r?.ok ?: false, message = message)) }
            if (r?.ok == true) after?.invoke(srv)
        } finally {
            (privilege as? Privilege.SudoPassword)?.wipe()
        }
    }

    fun submitPassword(pw: CharArray) {
        val next = pending ?: return
        pending = null
        _ui.update { it.copy(passwordPrompt = null) }
        job = scope.launch { try { next(pw) } finally { pw.fill('\u0000'); _ui.update { it.copy(busy = null) } } }
    }

    fun cancelPassword() { pending = null; _ui.update { it.copy(passwordPrompt = null, busy = null) } }
    fun dismissProblem() = _ui.update { it.copy(problem = null) }
    fun closeAction() = _ui.update { it.copy(action = null) }
}

/** Callbacks a screen hands to [ScopeOverlays]; null in screenshot tests. */
interface ScopeActions {
    fun submitPassword(pw: CharArray)
    fun cancelPassword()
    fun dismissProblem()
    fun closeAction()
}

/** The password prompts and the recipe log dialog, drawn the same way on every tab. */
@Composable
fun ScopeOverlays(ui: ScopeUi, actions: ScopeActions?) {
    ui.action?.let { a ->
        AlertDialog(onDismissRequest = { if (!a.running) actions?.closeAction() }, title = { Text(a.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (a.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    a.message?.let { Text(it, color = if (a.ok == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                    MonoBlock(a.log.takeLast(4000).ifEmpty { "(waiting for output)" })
                }
            },
            confirmButton = { TextButton({ actions?.closeAction() }, enabled = !a.running) { Text("Close") } })
    }
    when (ui.passwordPrompt) {
        PasswordKind.LOGIN -> PasswordDialog("Log in to ${ui.server?.host}", "The password for ${ui.server?.user}. Never stored.", { actions?.submitPassword(it) }, { actions?.cancelPassword() })
        PasswordKind.SUDO -> PasswordDialog("sudo password", "Installing needs administrator rights. The password goes to sudo for this action only.", { actions?.submitPassword(it) }, { actions?.cancelPassword() })
        null -> {}
    }
}
