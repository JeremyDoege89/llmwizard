package dev.dreamz.llmwizard.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dreamz.llmwizard.conn.Servers
import dev.dreamz.llmwizard.core.catalog.Catalog
import dev.dreamz.llmwizard.core.catalog.Recommender
import dev.dreamz.llmwizard.core.ollama.LocalModel
import dev.dreamz.llmwizard.core.ollama.RunningModel
import dev.dreamz.llmwizard.core.recipe.EngineEvent
import dev.dreamz.llmwizard.core.recipe.Manifest
import dev.dreamz.llmwizard.core.recipe.Recipe
import dev.dreamz.llmwizard.core.remote.Privilege
import dev.dreamz.llmwizard.core.scan.HardwareProfile
import dev.dreamz.llmwizard.core.server.PrivilegeProblem
import dev.dreamz.llmwizard.core.server.Server
import dev.dreamz.llmwizard.core.server.ServerStatus
import dev.dreamz.llmwizard.core.shell.Shell
import dev.dreamz.llmwizard.data.AuthType
import dev.dreamz.llmwizard.data.ServerEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** A management action as it runs: which recipe, its steps' log, and how it ended. */
data class ActionUi(val title: String, val log: String = "", val running: Boolean = true, val ok: Boolean? = null, val message: String? = null)

data class ManageUi(
    val server: ServerEntity? = null,
    val busy: String? = null,
    val problem: Problem? = null,
    val status: ServerStatus? = null,
    val manifest: Manifest? = null,
    val models: List<LocalModel> = emptyList(),
    val loaded: List<RunningModel> = emptyList(),
    val recommendedTags: List<String> = emptyList(),
    val action: ActionUi? = null,
    val passwordPrompt: PasswordKind? = null,
    val exposure: dev.dreamz.llmwizard.core.exposure.ExposureState? = null,
)

/**
 * The server screen (PLAN.md Phase 3): status, logs, start/stop/restart, settings, update,
 * uninstall and models. Every change is a recipe shown in the transcript; reads go through the
 * status probe or Ollama's API.
 */
@HiltViewModel
class ManageViewModel @Inject constructor(handle: SavedStateHandle, private val servers: Servers, private val catalog: Catalog) : ViewModel() {
    private val id: Long = checkNotNull(handle.get<Long>("id"))
    private val _ui = MutableStateFlow(ManageUi())
    val ui: StateFlow<ManageUi> = _ui
    private var pending: (suspend (CharArray) -> Unit)? = null
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val s = servers.db.servers().get(id) ?: return@launch
            val profile = s.profileJson?.let { runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(HardwareProfile.serializer(), it) }.getOrNull() }
            val rec = profile?.let { Recommender(catalog).recommend(it) }
            _ui.update { it.copy(server = s, recommendedTags = rec?.candidates?.filter { c -> c.fits }?.sortedByDescending { c -> c.entry.quality }?.map { c -> c.entry.ollamaTag }.orEmpty()) }
            refresh()
        }
    }

    private fun server() = checkNotNull(_ui.value.server)

    /** Connects (asking for the login password when the server has no key yet) and runs [block]. */
    private fun withServer(label: String, block: suspend (Server) -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _ui.update { it.copy(busy = label, problem = null) }
            try {
                // A live session is reused; a password login is asked for only when there is none.
                val srv = try {
                    servers.open(server())
                } catch (e: dev.dreamz.llmwizard.core.ssh.SshError.AuthFailed) {
                    if (server().authType != AuthType.PASSWORD) throw e
                    pending = { pw -> try { block(servers.open(server(), pw)) } catch (e: Exception) { _ui.update { it.copy(problem = describe(e)) } } finally { _ui.update { it.copy(busy = null) } } }
                    _ui.update { it.copy(passwordPrompt = PasswordKind.LOGIN) }
                    return@launch
                }
                block(srv)
            } catch (e: Exception) {
                _ui.update { it.copy(problem = describe(e)) }
            } finally {
                if (_ui.value.passwordPrompt == null) _ui.update { it.copy(busy = null) }
            }
        }
    }

    fun submitPassword(pw: CharArray) {
        val next = pending ?: return
        pending = null
        _ui.update { it.copy(passwordPrompt = null) }
        job = viewModelScope.launch { try { next(pw) } finally { pw.fill('\u0000') } }
    }

    fun cancelPassword() { pending = null; _ui.update { it.copy(passwordPrompt = null, busy = null) } }
    fun dismissProblem() = _ui.update { it.copy(problem = null) }
    fun closeAction() = _ui.update { it.copy(action = null) }

    fun refresh() = withServer("Reading the server's state…") { srv ->
        val status = srv.status()
        val manifest = srv.manifest()
        _ui.update { it.copy(status = status, manifest = manifest) }
        if (status.active == "active") {
            val c = srv.ollama(srv.ollamaEndpoint(manifest))
            try { _ui.update { it.copy(models = c.tags(), loaded = c.ps()) } } finally { c.close() }
        }
    }

    fun unload(model: String) = withServer("Unloading $model…") { srv ->
        val c = srv.ollama(srv.ollamaEndpoint(srv.manifest()))
        try { c.unload(model); _ui.update { it.copy(loaded = c.ps()) } } finally { c.close() }
    }

    /** Deleting goes through Ollama's API; the model is also dropped from the manifest. */
    fun delete(model: String) = withServer("Deleting $model…") { srv ->
        val manifest = srv.manifest()
        val c = srv.ollama(srv.ollamaEndpoint(manifest))
        try {
            c.delete(model)
            manifest?.let { m -> srv.remote.writeFile(srv.remote.stateDir() + "/installed.json", m.minus("model", model).encode().toByteArray()) }
            _ui.update { it.copy(models = c.tags(), loaded = c.ps()) }
        } finally { c.close() }
    }

    fun pull(tag: String) {
        val t = tag.trim()
        try { Shell.modelTag(t) } catch (e: IllegalArgumentException) { _ui.update { it.copy(problem = describe(e)) }; return }
        run("model-pull", mapOf("model" to t) + bindParam(), root = false)
    }

    fun service(action: String) {
        val noPw = _ui.value.manifest?.has("file", "/etc/sudoers.d/llmwizard-ollama") == true
        if (noPw) run("service-control-nopw", mapOf("action" to action), root = false) else run("service-control", mapOf("action" to action), root = true)
    }

    fun configure(contextLength: Int, keepAlive: String) =
        run("ollama-configure", mapOf("contextLength" to contextLength.toString(), "keepAlive" to keepAlive) + bindParam(), root = true)

    /** Reads interfaces and firewall state (a read-only scan) for the sharing section. */
    fun loadExposure() = withServer("Checking networks and firewall…") { srv ->
        val (_, profile) = srv.scan()
        val m = srv.manifest()
        _ui.update { it.copy(manifest = m, exposure = dev.dreamz.llmwizard.core.exposure.Exposure.state(profile, m?.settings?.get("settings.bind"))) }
    }

    private fun keepSettings() = mapOf(
        "contextLength" to (_ui.value.manifest?.settings?.get("settings.contextLength") ?: "8192"),
        "keepAlive" to (_ui.value.manifest?.settings?.get("settings.keepAlive") ?: "30m"),
    )

    fun expose(option: dev.dreamz.llmwizard.core.exposure.BindOption) {
        if (!option.allowed) return
        val cidr = option.firewallCidr
        val fw: Map<String, String> = if (_ui.value.exposure?.ufwActive == true && cidr != null) mapOf("firewallCidr" to cidr) else emptyMap()
        run("expose-lan", keepSettings() + mapOf("bind" to option.bind) + fw, root = true, after = { loadExposureNow(it) })
    }

    fun goPrivate() {
        val fw = _ui.value.manifest?.names("ufw")?.firstOrNull()?.let { mapOf("firewallCidr" to it) } ?: emptyMap()
        run("expose-private", keepSettings() + fw, root = true, after = { loadExposureNow(it) })
    }

    private suspend fun loadExposureNow(srv: Server) {
        val (_, profile) = srv.scan()
        _ui.update { it.copy(exposure = dev.dreamz.llmwizard.core.exposure.Exposure.state(profile, it.manifest?.settings?.get("settings.bind"))) }
    }

    /** The support report: hostnames, addresses and user names redacted unless [redact] is off. */
    suspend fun diagnostics(redact: Boolean): String {
        val s = server()
        val rows = servers.db.transcript().forServer(servers.target(s).name).first()
        val profile = s.profileJson?.let { runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(HardwareProfile.serializer(), it) }.getOrNull() }
        val entries = rows.reversed().map { dev.dreamz.llmwizard.core.recipe.TranscriptEntry(it.at, it.serverName, it.kind, it.script, it.exitCode, it.outputTail) }
        return dev.dreamz.llmwizard.core.support.Diagnostics.report(
            dev.dreamz.llmwizard.core.support.Diagnostics.Input(dev.dreamz.llmwizard.BuildConfig.VERSION_NAME, android.os.Build.VERSION.RELEASE, profile, _ui.value.manifest, entries, s.host, s.user),
            redact)
    }

    fun update() = run("ollama-update", mapOf("prefix" to (_ui.value.manifest?.settings?.get("ollama.prefix") ?: "/usr/local")), root = true)
    fun addSudoRule() = run("sudoers-dropin", emptyMap(), root = true)
    fun uninstall(deleteModels: Boolean) = run("ollama-uninstall", mapOf("deleteModels" to if (deleteModels) "yes" else "no"), root = true)

    private fun bindParam(): Map<String, String> = _ui.value.manifest?.settings?.get("settings.bind")?.let { mapOf("bind" to it) } ?: emptyMap()

    /** Runs a recipe, asking for the sudo password when it needs root and the server needs one. */
    private fun run(recipeId: String, params: Map<String, String>, root: Boolean, after: (suspend (Server) -> Unit)? = null) =
        withServer("Starting…") { srv -> runWith(srv, recipeId, params, root, null, after) }

    private suspend fun runWith(srv: Server, recipeId: String, params: Map<String, String>, root: Boolean, sudo: CharArray?, after: (suspend (Server) -> Unit)? = null) {
        val privilege = if (!root) null else try {
            srv.privilege { sudo }
        } catch (e: PrivilegeProblem.PasswordNeeded) {
            pending = { pw -> try { runWith(srv, recipeId, params, root, pw, after) } catch (e: Exception) { _ui.update { it.copy(problem = describe(e)) } } }
            _ui.update { it.copy(passwordPrompt = PasswordKind.SUDO) }
            return
        }
        val recipe = Recipe.bundled(recipeId)
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
            _ui.update { u -> u.copy(action = u.action?.copy(running = false, ok = r?.ok ?: false, message = r?.message ?: "A check before running failed.")) }
            if (recipeId == "ollama-uninstall" && r?.ok == true) servers.db.servers().upsert(server().copy(installState = "none"))
            _ui.update { it.copy(status = srv.status(), manifest = srv.manifest()) }
            if (_ui.value.status?.active == "active") {
                val c = srv.ollama(srv.ollamaEndpoint(_ui.value.manifest))
                try { _ui.update { it.copy(models = c.tags(), loaded = c.ps()) } } finally { c.close() }
            } else _ui.update { it.copy(models = emptyList(), loaded = emptyList()) }
            if (r?.ok == true) after?.invoke(srv)
        } finally {
            (privilege as? Privilege.SudoPassword)?.wipe()
        }
    }
}
