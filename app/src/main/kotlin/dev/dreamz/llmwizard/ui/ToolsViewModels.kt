package dev.dreamz.llmwizard.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dreamz.llmwizard.conn.Servers
import dev.dreamz.llmwizard.core.agents.AgentKey
import dev.dreamz.llmwizard.core.agents.AgentProfile
import dev.dreamz.llmwizard.core.agents.AgentSession
import dev.dreamz.llmwizard.core.agents.AgentSessions
import dev.dreamz.llmwizard.core.agents.AgentType
import dev.dreamz.llmwizard.core.catalog.Catalog
import dev.dreamz.llmwizard.core.catalog.ModelEntry
import dev.dreamz.llmwizard.core.catalog.ModelFilter
import dev.dreamz.llmwizard.core.catalog.Recommendation
import dev.dreamz.llmwizard.core.catalog.Recommender
import dev.dreamz.llmwizard.core.config.ContextAdvisor
import dev.dreamz.llmwizard.core.config.ModelConfig
import dev.dreamz.llmwizard.core.knowledge.Article
import dev.dreamz.llmwizard.core.knowledge.ArticleCategory
import dev.dreamz.llmwizard.core.knowledge.KnowledgeBase
import dev.dreamz.llmwizard.core.ollama.Benchmark
import dev.dreamz.llmwizard.core.ollama.BenchmarkResult
import dev.dreamz.llmwizard.core.scan.HardwareProfile
import dev.dreamz.llmwizard.core.server.Server
import dev.dreamz.llmwizard.core.training.DatasetValidator
import dev.dreamz.llmwizard.core.training.JobState
import dev.dreamz.llmwizard.core.training.Training
import dev.dreamz.llmwizard.core.training.TrainingDataset
import dev.dreamz.llmwizard.core.training.TrainingEstimator
import dev.dreamz.llmwizard.core.training.TrainingJob
import dev.dreamz.llmwizard.core.training.TrainingStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

private val lenient = Json { ignoreUnknownKeys = true }
private fun profileOf(json: String?): HardwareProfile? = json?.let { runCatching { lenient.decodeFromString(HardwareProfile.serializer(), it) }.getOrNull() }
private fun ollamaApi(srv: Server, m: dev.dreamz.llmwizard.core.recipe.Manifest?) = srv.ollamaEndpoint(m).let { "http://${it.first}:${it.second}" }

/** Common base: one [ServerScope] per screen, its overlay actions delegated. */
abstract class ServerToolViewModel(handle: SavedStateHandle, servers: Servers) : ViewModel(), ScopeActions {
    protected val id: Long = checkNotNull(handle.get<Long>("id"))
    val scope = ServerScope(viewModelScope, servers, id)
    override fun submitPassword(pw: CharArray) = scope.submitPassword(pw)
    override fun cancelPassword() = scope.cancelPassword()
    override fun dismissProblem() = scope.dismissProblem()
    override fun closeAction() = scope.closeAction()
}

// ---------------------------------------------------------------- Models (BUILD_PLAN E1.4, E2)

enum class ModelChip(val label: String, val filter: ModelFilter) {
    ALL("All", ModelFilter.EVERYTHING),
    UNCENSORED("Uncensored", ModelFilter.UNCENSORED),
    CODING("Coding", ModelFilter(tags = setOf("coding"), uncensored = null)),
    REASONING("Reasoning", ModelFilter(tags = setOf("reasoning"), uncensored = null)),
    CHAT("Chat", ModelFilter(tags = setOf("chat"), uncensored = null)),
    RESEARCH("Research", ModelFilter(tags = setOf("research"), uncensored = null)),
}

enum class ModelSort(val label: String) { QUALITY("Quality"), SPEED("Speed"), SIZE("Size") }

data class ModelsUi(
    val profile: HardwareProfile? = null,
    val chip: ModelChip = ModelChip.ALL,
    val query: String = "",
    val sort: ModelSort = ModelSort.QUALITY,
    val recommendation: Recommendation? = null,
    val installed: Set<String> = emptySet(),
    val expanded: String? = null,
    val benchmarks: Map<String, BenchmarkResult> = emptyMap(),
    val configFor: ModelEntry? = null,
    val configMessage: String? = null,
)

typealias ModelRow = Pair<ModelEntry, dev.dreamz.llmwizard.core.catalog.Candidate?>

/** The cards to show, filtered and sorted. Works without a scan (no estimates then). */
fun visibleModels(catalog: Catalog, u: ModelsUi): List<ModelRow> {
    val f = u.chip.filter.copy(query = u.query.ifBlank { null })
    val cands = u.recommendation?.allCandidates?.associateBy { it.entry.id }.orEmpty()
    val rows = catalog.models.filter(f::matches).map { it to cands[it.id] }
    return when (u.sort) {
        ModelSort.QUALITY -> rows.sortedWith(compareByDescending<ModelRow> { it.first.quality }.thenByDescending { it.second?.estimate?.midTps ?: 0.0 })
        ModelSort.SPEED -> rows.sortedByDescending { it.second?.estimate?.midTps ?: 0.0 }
        ModelSort.SIZE -> rows.sortedBy { it.first.downloadBytes }
    }
}

@HiltViewModel
class ModelsViewModel @Inject constructor(handle: SavedStateHandle, servers: Servers, private val catalog: Catalog) : ServerToolViewModel(handle, servers) {
    private val _ui = MutableStateFlow(ModelsUi())
    val ui: StateFlow<ModelsUi> = _ui
    private val recommender = Recommender(catalog)
    val advisor = ContextAdvisor(catalog)

    init {
        viewModelScope.launch {
            val s = scope.load() ?: return@launch
            _ui.update { it.copy(profile = profileOf(s.profileJson)) }
            recompute()
            refreshInstalled()
        }
    }

    private fun recompute() = _ui.update { u ->
        u.copy(recommendation = u.profile?.let { recommender.recommend(it, filter = u.chip.filter.copy(query = u.query.ifBlank { null })) })
    }

    fun visible(u: ModelsUi) = visibleModels(catalog, u)

    fun chip(c: ModelChip) { _ui.update { it.copy(chip = c) }; recompute() }
    fun query(q: String) { _ui.update { it.copy(query = q) }; recompute() }
    fun sort(s: ModelSort) = _ui.update { it.copy(sort = s) }
    fun expand(id: String?) = _ui.update { it.copy(expanded = if (it.expanded == id) null else id) }

    fun refreshInstalled() = scope.launch("Reading installed models…", quiet = _ui.value.installed.isNotEmpty()) { srv ->
        val c = srv.ollama(srv.ollamaEndpoint(srv.manifest()))
        try { _ui.update { it.copy(installed = c.tags().map { m -> m.name }.toSet()) } } finally { c.close() }
    }

    fun install(e: ModelEntry) = scope.launch("Checking the server…") { srv ->
        val bind = srv.manifest()?.settings?.get("settings.bind")
        scope.runRecipeFrom(srv, "model-pull", mapOf("model" to e.ollamaTag) + (bind?.let { mapOf("bind" to it) } ?: emptyMap()))
        val c = srv.ollama(srv.ollamaEndpoint(srv.manifest()))
        try { _ui.update { it.copy(installed = c.tags().map { m -> m.name }.toSet()) } } finally { c.close() }
    }

    fun benchmark(e: ModelEntry) = scope.launch("Measuring ${e.name} (loads the model first; can take minutes on a processor)…") { srv ->
        val c = srv.ollama(srv.ollamaEndpoint(srv.manifest()))
        try {
            val r = Benchmark.run(c, e.ollamaTag)
            _ui.update { it.copy(benchmarks = it.benchmarks + (e.id to r)) }
        } finally { c.close() }
    }

    fun compareText(e: ModelEntry): String? = _ui.value.benchmarks[e.id]?.let { r ->
        Benchmark.compare(r, _ui.value.recommendation?.allCandidates?.firstOrNull { it.entry.id == e.id }?.estimate)
    }

    fun openConfig(e: ModelEntry?) = _ui.update { it.copy(configFor = e, configMessage = null) }

    fun createConfig(cfg: ModelConfig) = scope.launch("Creating ${cfg.customTag}…") { srv ->
        val c = srv.ollama(srv.ollamaEndpoint(srv.manifest()))
        try {
            c.create(cfg)
            _ui.update { it.copy(configMessage = "Created ${cfg.customTag}. Pick it in the chat's model list; delete it on the server screen to undo.", installed = c.tags().map { m -> m.name }.toSet()) }
        } finally { c.close() }
    }
}

// ---------------------------------------------------------------- Training (BUILD_PLAN E3)

data class TrainingUi(
    val profile: HardwareProfile? = null,
    val stackInstalled: Boolean? = null,
    val datasets: List<TrainingDataset> = emptyList(),
    val jobs: List<JobState> = emptyList(),
    /** A dataset checked on the phone, waiting for the user to upload it. */
    val checked: Pair<String, DatasetValidator.Result>? = null,
    val openJob: String? = null,
    val openLog: String = "",
)

@HiltViewModel
class TrainingViewModel @Inject constructor(handle: SavedStateHandle, servers: Servers, val catalog: Catalog) : ServerToolViewModel(handle, servers) {
    private val _ui = MutableStateFlow(TrainingUi())
    val ui: StateFlow<TrainingUi> = _ui
    val estimator = TrainingEstimator()
    private var poller: Job? = null

    init {
        viewModelScope.launch {
            val s = scope.load() ?: return@launch
            _ui.update { it.copy(profile = profileOf(s.profileJson)) }
            refresh()
        }
    }

    val trainable: List<ModelEntry> get() = catalog.models.filter { it.trainable }

    fun refresh(quiet: Boolean = false) = scope.launch("Reading training jobs…", quiet) { srv ->
        val t = Training(srv.remote)
        _ui.update { it.copy(stackInstalled = t.stackInstalled(), datasets = t.datasets(), jobs = t.jobs()) }
        _ui.value.openJob?.let { id -> _ui.update { it.copy(openLog = t.log(id, 12_000)) } }
    }

    /** Re-reads job progress every 15 s while a job runs and the screen is open. */
    fun startPolling() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                if (_ui.value.jobs.any { it.status == TrainingStatus.RUNNING }) refresh(quiet = true)
            }
        }
    }
    fun stopPolling() { poller?.cancel() }

    fun installStack() = scope.runRecipe("training-stack-install") { refresh() }

    fun check(name: String, text: String) = _ui.update { it.copy(checked = name to DatasetValidator.validate(text)) }
    fun discardChecked() = _ui.update { it.copy(checked = null) }

    fun upload() {
        val (name, r) = _ui.value.checked ?: return
        scope.launch("Uploading $name…") { srv ->
            Training(srv.remote).uploadDataset(name, r)
            _ui.update { it.copy(checked = null) }
            refreshNow(srv)
        }
    }

    fun deleteDataset(id: String) = scope.launch("Deleting…") { srv -> Training(srv.remote).deleteDataset(id); refreshNow(srv) }

    fun estimate(job: TrainingJob): TrainingEstimator.Estimate? {
        val p = _ui.value.profile ?: return null
        val e = catalog.byTag(job.baseModel) ?: return null
        val ds = _ui.value.datasets.firstOrNull { it.id == job.dataset } ?: return null
        return estimator.estimate(p, e, ds, job)
    }

    fun newJob(base: ModelEntry, dataset: String, outputName: String, epochs: Int, quantize: String, maxLen: Int, rank: Int, lr: Double): TrainingJob =
        TrainingJob(Training.newId("job"), base.ollamaTag, base.hfRepo ?: "", dataset, outputName = outputName.trim(), epochs = epochs,
            quantize = quantize, maxSeqLength = maxLen, loraRank = rank, loraAlpha = rank * 2, learningRate = lr)

    fun start(job: TrainingJob) = scope.launch("Starting the job…") { srv ->
        val t = Training(srv.remote)
        t.start(t.prepare(job.copy(ollamaApi = ollamaApi(srv, srv.manifest()))).id)
        _ui.update { it.copy(openJob = job.id) }
        refreshNow(srv)
        startPolling()
    }

    fun open(id: String?) { _ui.update { it.copy(openJob = id, openLog = "") }; if (id != null) refresh(quiet = true) }
    fun cancel(id: String) = scope.launch("Asking the job to stop…") { srv -> Training(srv.remote).cancel(id); refreshNow(srv) }
    fun resume(id: String) = scope.launch("Resuming…") { srv -> Training(srv.remote).start(id); refreshNow(srv); startPolling() }
    fun delete(id: String) = scope.launch("Deleting the job…") { srv -> Training(srv.remote).delete(id); _ui.update { it.copy(openJob = null) }; refreshNow(srv) }

    private suspend fun refreshNow(srv: Server) {
        val t = Training(srv.remote)
        _ui.update { it.copy(datasets = t.datasets(), jobs = t.jobs()) }
        _ui.value.openJob?.let { id -> _ui.update { it.copy(openLog = t.log(id, 12_000)) } }
    }

    /** The exact script and config a job will run, for the preview (SPEC.md rule 1). */
    fun preview(job: TrainingJob): String = Training.previewText(job)
}

// ---------------------------------------------------------------- Agents (BUILD_PLAN E4)

data class AgentsUi(
    val installed: Set<AgentType>? = null,
    val profiles: List<AgentProfile> = emptyList(),
    val sessions: List<AgentSession> = emptyList(),
    val models: List<String> = emptyList(),
    val open: AgentSession? = null,
    val screen: String = "",
    val editing: AgentProfile? = null,
)

@HiltViewModel
class AgentsViewModel @Inject constructor(handle: SavedStateHandle, servers: Servers) : ServerToolViewModel(handle, servers) {
    private val _ui = MutableStateFlow(AgentsUi())
    val ui: StateFlow<AgentsUi> = _ui
    private var poller: Job? = null

    init { refresh() }

    fun refresh() = scope.launch("Reading agents…") { srv -> refreshNow(srv) }

    private suspend fun refreshNow(srv: Server) {
        val a = AgentSessions(srv.remote)
        val models = runCatching { val c = srv.ollama(srv.ollamaEndpoint(srv.manifest())); try { c.tags().map { it.name } } finally { c.close() } }.getOrDefault(emptyList())
        _ui.update { it.copy(installed = a.installed(), profiles = a.profiles(), sessions = a.list(), models = models) }
    }

    fun install(t: AgentType) = scope.runRecipe(if (t == AgentType.AIDER) "aider-install" else "open-interpreter-install") { refreshNow(it) }
    fun remove(t: AgentType) = scope.runRecipe("agent-tool-remove", mapOf("tool" to t.dir)) { refreshNow(it) }

    fun edit(p: AgentProfile?) = _ui.update { it.copy(editing = p) }
    fun save(p: AgentProfile) = scope.launch("Saving ${p.name}…") { srv -> AgentSessions(srv.remote).saveProfile(p); _ui.update { it.copy(editing = null) }; refreshNow(srv) }
    fun deleteProfile(p: AgentProfile) = scope.launch("Deleting…") { srv -> AgentSessions(srv.remote).deleteProfile(p.id); refreshNow(srv) }

    fun start(p: AgentProfile) = scope.launch("Starting ${p.agent.label}…") { srv ->
        val s = AgentSessions(srv.remote).start(p, ollamaApi(srv, srv.manifest()))
        refreshNow(srv)
        open(s)
    }

    fun preview(p: AgentProfile): String = dev.dreamz.llmwizard.core.agents.AgentCommand.launcher(p, "~/.local/share/llmwizard", "http://127.0.0.1:11434",
        if (p.systemPrompt.isNullOrBlank() || p.agent != AgentType.AIDER) null else "<instructions file>")

    /** Opens the terminal view: the screen is re-read every 2 s while it is open. */
    fun open(s: AgentSession?) {
        poller?.cancel()
        _ui.update { it.copy(open = s, screen = "") }
        if (s == null) return
        poller = viewModelScope.launch {
            while (isActive && _ui.value.open?.id == s.id) {
                scope.launch("", quiet = true) { srv -> _ui.update { it.copy(screen = runCatching { AgentSessions(srv.remote).screen(s, 300) }.getOrElse { e -> it.screen.ifEmpty { "(${e.message})" } }) } }
                delay(2000)
            }
        }
    }

    fun send(text: String) { val s = _ui.value.open ?: return; scope.launch("Sending…", quiet = true) { srv -> AgentSessions(srv.remote).send(s, text) } }
    fun key(k: AgentKey) { val s = _ui.value.open ?: return; scope.launch("Sending…", quiet = true) { srv -> AgentSessions(srv.remote).key(s, k) } }
    fun stop(s: AgentSession) = scope.launch("Stopping…") { srv -> AgentSessions(srv.remote).stop(s); refreshNow(srv) }
    fun forget(s: AgentSession) = scope.launch("Removing…") { srv -> AgentSessions(srv.remote).forget(s); if (_ui.value.open?.id == s.id) open(null); refreshNow(srv) }

    override fun onCleared() { poller?.cancel() }
}

// ---------------------------------------------------------------- Learn (BUILD_PLAN E5)

@HiltViewModel
class LearnViewModel @Inject constructor(val kb: KnowledgeBase) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    fun query(q: String) { _query.value = q }
    fun results(q: String): List<Article> = kb.search(q)
    fun byCategory(): Map<ArticleCategory, List<Article>> = ArticleCategory.entries.associateWith { kb.byCategory(it) }.filterValues { it.isNotEmpty() }
}
