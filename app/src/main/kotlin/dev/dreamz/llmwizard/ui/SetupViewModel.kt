package dev.dreamz.llmwizard.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dreamz.llmwizard.conn.Servers
import dev.dreamz.llmwizard.core.catalog.Catalog
import dev.dreamz.llmwizard.core.catalog.Intent
import dev.dreamz.llmwizard.core.catalog.Recommendation
import dev.dreamz.llmwizard.core.catalog.Recommender
import dev.dreamz.llmwizard.core.ollama.Benchmark
import dev.dreamz.llmwizard.core.ollama.BenchmarkResult
import dev.dreamz.llmwizard.core.recipe.EngineEvent
import dev.dreamz.llmwizard.core.recipe.PlannedStep
import dev.dreamz.llmwizard.core.recipe.Recipe
import dev.dreamz.llmwizard.core.recipe.RunResult
import dev.dreamz.llmwizard.core.remote.Privilege
import dev.dreamz.llmwizard.core.scan.HardwareProfile
import dev.dreamz.llmwizard.core.scan.Verdict
import dev.dreamz.llmwizard.core.scan.Verdicts
import dev.dreamz.llmwizard.core.server.PrivilegeProblem
import dev.dreamz.llmwizard.core.server.Server
import dev.dreamz.llmwizard.core.ssh.KnownHost
import dev.dreamz.llmwizard.core.ssh.SshError
import dev.dreamz.llmwizard.data.AuthType
import dev.dreamz.llmwizard.data.BenchmarkRow
import dev.dreamz.llmwizard.data.ServerEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

enum class StepStatus { PENDING, DONE_ALREADY, RUNNING, OK, FAILED, UNDONE }

enum class PasswordKind { LOGIN, SUDO }

data class RunUi(
    val title: String,
    val steps: List<PlannedStep>,
    val status: Map<Int, StepStatus>,
    val log: String,
    val result: RunResult? = null,
    val undoing: Boolean = false,
)

data class SetupUi(
    val server: ServerEntity? = null,
    val busy: String? = null,
    val problem: Problem? = null,
    val hostKeyPrompt: KnownHost? = null,
    val passwordPrompt: PasswordKind? = null,
    val offerKeyInstall: Boolean = false,
    val profile: HardwareProfile? = null,
    val verdicts: List<Verdict> = emptyList(),
    val intent: Intent = Intent.CHAT,
    val recommendation: Recommendation? = null,
    val chosenTag: String? = null,
    val plan: List<PlannedStep>? = null,
    val preflightFailures: List<String> = emptyList(),
    val run: RunUi? = null,
    val benchmark: BenchmarkResult? = null,
    val estimateText: String? = null,
    val resumable: Boolean = false,
)

/**
 * The setup flow for one server: connect, scan, recommend, preview, install, benchmark.
 * State is rebuilt from Room (last scan, running jobs) and from the server itself, so a killed
 * app picks up where it was (rule 4). Passwords are held only for the call that needs them.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val servers: Servers,
    catalog: Catalog,
) : ViewModel() {
    private val id: Long = checkNotNull(handle.get<Long>("id"))
    private val recommender = Recommender(catalog)
    private val json = Json { ignoreUnknownKeys = true }
    private val _ui = MutableStateFlow(SetupUi())
    val ui: StateFlow<SetupUi> = _ui
    private var pendingAfterPassword: (suspend (CharArray) -> Unit)? = null
    private var running: Job? = null

    init {
        viewModelScope.launch {
            val s = servers.db.servers().get(id) ?: return@launch
            val profile = s.profileJson?.let { runCatching { json.decodeFromString(HardwareProfile.serializer(), it) }.getOrNull() }
            val jobs = servers.db.jobs().forServer(servers.target(s).name).first()
            _ui.update { it.copy(server = s, profile = profile, verdicts = profile?.let(Verdicts::of).orEmpty(), resumable = jobs.isNotEmpty() || s.installState == "installing") }
            profile?.let { recommend(it, _ui.value.intent) }
        }
    }

    private fun server() = checkNotNull(_ui.value.server)

    fun dismissProblem() = _ui.update { it.copy(problem = null) }

    /** Runs [block] with a connected server, asking for the login password or host-key approval when needed. */
    private fun withServer(label: String, block: suspend (Server) -> Unit) {
        if (running?.isActive == true) return
        running = viewModelScope.launch {
            _ui.update { it.copy(busy = label, problem = null) }
            try {
                val s = server()
                if (s.authType == AuthType.PASSWORD) {
                    // Password logins ask every time; the app offers to install its key instead.
                    pendingAfterPassword = { pw -> connectAndRun(label, pw, block) }
                    _ui.update { it.copy(passwordPrompt = PasswordKind.LOGIN, busy = null) }
                    return@launch
                }
                block(servers.open(s))
            } catch (e: SshError.HostKeyUnknown) {
                hostKeyRetry = { withServer(label, block) }
                _ui.update { it.copy(hostKeyPrompt = e.key) }
                lastHostKeyError = e
            } catch (e: Exception) {
                _ui.update { it.copy(problem = describe(e)) }
            } finally {
                _ui.update { it.copy(busy = null) }
            }
        }
    }

    private suspend fun connectAndRun(label: String, pw: CharArray, block: suspend (Server) -> Unit) {
        _ui.update { it.copy(busy = label) }
        try {
            val srv = servers.open(server(), pw)
            if (server().authType == AuthType.PASSWORD) _ui.update { it.copy(offerKeyInstall = true) }
            block(srv)
        } catch (e: SshError.HostKeyUnknown) {
            lastHostKeyError = e
            hostKeyRetry = { pendingAfterPassword = { p -> connectAndRun(label, p, block) }; _ui.update { it.copy(passwordPrompt = PasswordKind.LOGIN) } }
            _ui.update { it.copy(hostKeyPrompt = e.key) }
        } catch (e: Exception) {
            _ui.update { it.copy(problem = describe(e)) }
        } finally {
            pw.fill('\u0000')
            _ui.update { it.copy(busy = null) }
        }
    }

    private var hostKeyRetry: (() -> Unit)? = null
    private var lastHostKeyError: SshError.HostKeyUnknown? = null

    fun acceptHostKey() {
        val e = lastHostKeyError ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            servers.acceptHostKey(server(), e)
            val refreshed = servers.db.servers().get(id)
            _ui.update { it.copy(hostKeyPrompt = null, server = refreshed) }
            hostKeyRetry?.invoke()
        }
    }

    fun rejectHostKey() = _ui.update { it.copy(hostKeyPrompt = null, problem = Problem("Not connected", "You did not trust the server's key.", "Compare the fingerprint on the server, then try again.")) }

    fun submitPassword(pw: CharArray) {
        val next = pendingAfterPassword ?: return
        pendingAfterPassword = null
        _ui.update { it.copy(passwordPrompt = null) }
        running = viewModelScope.launch { next(pw) }
    }

    fun cancelPassword() {
        pendingAfterPassword = null
        _ui.update { it.copy(passwordPrompt = null, busy = null) }
    }

    fun scan() = withServer("Reading the hardware…") { srv ->
        val (_, profile) = srv.scan()
        val s = server().copy(profileJson = json.encodeToString(HardwareProfile.serializer(), profile))
        servers.db.servers().upsert(s)
        _ui.update { it.copy(server = s, profile = profile, verdicts = Verdicts.of(profile)) }
        recommend(profile, _ui.value.intent)
    }

    fun setIntent(i: Intent) {
        _ui.update { it.copy(intent = i) }
        _ui.value.profile?.let { recommend(it, i) }
    }

    private fun recommend(p: HardwareProfile, i: Intent) {
        val r = recommender.recommend(p, i)
        _ui.update { it.copy(recommendation = r, chosenTag = (it.chosenTag ?: it.server?.model)?.takeIf { t -> r.allCandidates.any { c -> c.entry.ollamaTag == t && c.fits } } ?: r.pick?.candidate?.entry?.ollamaTag) }
    }

    fun choose(tag: String) {
        _ui.update { it.copy(chosenTag = tag) }
        // Remembered so a restarted app offers the same model.
        _ui.value.server?.let { s -> viewModelScope.launch { servers.db.servers().upsert(s.copy(model = tag).also { n -> _ui.update { it.copy(server = n) } }) } }
    }

    /** Preflight and plan: shows every step and its script; runs nothing that changes the machine. */
    fun preparePlan() = withServer("Checking the server…") { srv ->
        val params = buildMap { _ui.value.chosenTag?.let { put("model", it) } }
        val out = srv.runRecipe(Recipe.bundled("ollama-install"), params, null, approve = { failures, plan ->
            _ui.update { it.copy(plan = plan, preflightFailures = failures) }
            false
        }, events = {})
        out.plan.let { p -> _ui.update { it.copy(plan = p) } }
    }

    fun closePlan() = _ui.update { it.copy(plan = null, preflightFailures = emptyList()) }

    /** Runs the install. Asks for the sudo password first when the server needs one. */
    fun install() = withServer("Getting ready…") { srv -> runInstall(srv, null) }

    private suspend fun runInstall(srv: Server, sudo: CharArray?) {
        val privilege = try {
            srv.privilege { sudo }
        } catch (e: PrivilegeProblem.PasswordNeeded) {
            pendingAfterPassword = { pw -> _ui.update { it.copy(busy = "Checking the password…") }; try { runInstall(srv, pw) } finally { pw.fill('\u0000'); _ui.update { it.copy(busy = null) } } }
            _ui.update { it.copy(passwordPrompt = PasswordKind.SUDO) }
            return
        }
        val recipe = Recipe.bundled("ollama-install")
        val model = _ui.value.chosenTag
        val params = buildMap { model?.let { put("model", it) } }
        try {
            servers.db.servers().upsert(server().copy(installState = "installing", model = model).also { s -> _ui.update { it.copy(server = s) } })
            val out = srv.runRecipe(recipe, params, privilege,
                approve = { failures, plan ->
                    _ui.update { it.copy(plan = null, run = RunUi(recipe.title, plan, plan.associate { s -> s.index to (if (s.alreadyDone) StepStatus.DONE_ALREADY else StepStatus.PENDING) }, "")) }
                    failures.isEmpty()
                },
                events = ::onEvent)
            val result = out.result
            if (result == null) {
                _ui.update { it.copy(run = null, preflightFailures = emptyList(), problem = Problem("Can't install yet", "A check before installing failed.", "See the plan for details.")) }
                return
            }
            _ui.update { it.copy(run = it.run?.copy(result = result), resumable = false) }
            lastRun = out
            servers.db.servers().upsert(server().copy(installState = if (result.ok) "installed" else "failed").also { s -> _ui.update { it.copy(server = s) } })
            if (result.ok && model != null) benchmark(srv, model)
        } finally {
            (privilege as? Privilege.SudoPassword)?.wipe()
        }
    }

    private var lastRun: dev.dreamz.llmwizard.core.server.RecipeOutcome? = null

    private fun onEvent(ev: EngineEvent) {
        _ui.update { u ->
            val run = u.run ?: return@update u
            fun set(step: PlannedStep, st: StepStatus) = run.copy(status = run.status + (step.index to st))
            val next = when (ev) {
                is EngineEvent.StepSkipped -> set(ev.step, StepStatus.DONE_ALREADY)
                is EngineEvent.StepStarted -> set(ev.step, StepStatus.RUNNING).copy(log = run.log + "\n▶ ${ev.step.spec.title}\n")
                is EngineEvent.Log -> run.copy(log = (run.log + cleanLog(ev.text)).takeLast(64_000))
                is EngineEvent.StepFinished -> set(ev.step, if (ev.exitCode == 0) StepStatus.OK else StepStatus.FAILED)
                is EngineEvent.VerifyFailed -> set(ev.step, StepStatus.FAILED).copy(log = run.log + "The check after this step failed.\n")
                is EngineEvent.UndoStarted -> run.copy(log = run.log + "\n↶ Undo: ${ev.step.spec.title}\n")
                is EngineEvent.UndoFinished -> set(ev.step, if (ev.exitCode == 0) StepStatus.UNDONE else StepStatus.FAILED)
            }
            u.copy(run = next)
        }
    }

    /** Reverses the steps the failed run completed, newest first. */
    fun undo() = withServer("Undoing…") { srv -> runUndo(srv, null) }

    private suspend fun runUndo(srv: Server, sudo: CharArray?) {
        val out = lastRun ?: return
        val privilege = try {
            srv.privilege { sudo }
        } catch (e: PrivilegeProblem.PasswordNeeded) {
            pendingAfterPassword = { pw -> try { runUndo(srv, pw) } finally { pw.fill('\u0000') } }
            _ui.update { it.copy(passwordPrompt = PasswordKind.SUDO) }
            return
        }
        try {
            _ui.update { it.copy(run = it.run?.copy(undoing = true)) }
            val ok = srv.undo(Recipe.bundled("ollama-install"), out.result?.ran.orEmpty(), out.values, privilege, ::onEvent)
            _ui.update { it.copy(run = it.run?.copy(undoing = false, log = it.run.log + if (ok) "\nUndone.\n" else "\nUndo did not complete.\n")) }
            if (ok) servers.db.servers().upsert(server().copy(installState = "none").also { s -> _ui.update { it.copy(server = s) } })
        } finally {
            (privilege as? Privilege.SudoPassword)?.wipe()
        }
    }

    fun closeRun() = _ui.update { it.copy(run = null) }

    private suspend fun benchmark(srv: Server, model: String) {
        _ui.update { it.copy(busy = "Measuring speed (loading the model can take a minute)…") }
        val client = srv.ollama(srv.ollamaEndpoint(srv.manifest()))
        try {
            val b = Benchmark.run(client, model)
            val est = _ui.value.recommendation?.allCandidates?.firstOrNull { it.entry.ollamaTag == model }?.estimate?.describe()
            servers.db.benchmarks().insert(BenchmarkRow(0, id, model, b.tokensPerSecond, b.loadSeconds, est, b.at))
            _ui.update { it.copy(benchmark = b, estimateText = est) }
        } catch (e: Exception) {
            _ui.update { it.copy(problem = describe(e)) }
        } finally {
            client.close()
        }
    }

    /** Puts the app's key on the server, then logs in with it from now on. */
    fun installKey() {
        _ui.update { it.copy(offerKeyInstall = false) }
        val s = server()
        pendingAfterPassword = { pw ->
            _ui.update { it.copy(busy = "Adding this phone's key…") }
            try {
                val srv = servers.open(s, pw)
                val pub = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { servers.vault.ensureKey() }
                val out = srv.runRecipe(Recipe.bundled("authorized-key-add"), mapOf("publicKey" to pub), null, approve = { _, _ -> true }, events = {})
                if (out.result?.ok == true) {
                    val updated = s.copy(authType = AuthType.KEY)
                    servers.db.servers().upsert(updated)
                    servers.drop(s.id)
                    _ui.update { it.copy(server = updated) }
                } else {
                    _ui.update { it.copy(problem = Problem("Key not added", out.result?.message ?: "", "Check the transcript.")) }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(problem = describe(e)) }
            } finally {
                pw.fill('\u0000')
                _ui.update { it.copy(busy = null) }
            }
        }
        _ui.update { it.copy(passwordPrompt = PasswordKind.LOGIN) }
    }

    fun declineKeyInstall() = _ui.update { it.copy(offerKeyInstall = false) }

    companion object {
        fun cleanLog(s: String): String = s.replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "")
            .lines().joinToString("\n") { it.substringAfterLast('\r') }
    }
}
