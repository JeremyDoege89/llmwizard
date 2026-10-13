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
import dev.dreamz.llmwizard.core.scan.CpuInfo
import dev.dreamz.llmwizard.core.scan.DiskInfo
import dev.dreamz.llmwizard.core.scan.Filesystem
import dev.dreamz.llmwizard.core.scan.HardwareProfile
import dev.dreamz.llmwizard.core.scan.MemoryInfo
import dev.dreamz.llmwizard.core.scan.NvidiaCard
import dev.dreamz.llmwizard.core.scan.NvidiaInfo
import dev.dreamz.llmwizard.core.scan.SmiState
import dev.dreamz.llmwizard.core.ssh.SshError
import dev.dreamz.llmwizard.data.AuthType
import dev.dreamz.llmwizard.data.ServerEntity
import dev.dreamz.llmwizard.data.Settings
import dev.dreamz.llmwizard.data.TranscriptRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(private val servers: Servers, private val settings: Settings, entitlements: dev.dreamz.llmwizard.billing.Entitlements, val kb: dev.dreamz.llmwizard.core.knowledge.KnowledgeBase) : ViewModel() {
    val pro: StateFlow<Boolean> = entitlements.pro.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val firstRunAccepted: StateFlow<Boolean?> = settings.firstRunAccepted.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val list: StateFlow<List<ServerEntity>> = servers.db.servers().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun acceptFirstRun() = viewModelScope.launch { settings.acceptFirstRun() }
    fun delete(id: Long) = viewModelScope.launch { servers.drop(id); servers.db.servers().delete(id) }
}

data class AddServerUi(
    val busy: Boolean = false,
    val problem: Problem? = null,
    val hostKeyPrompt: dev.dreamz.llmwizard.core.ssh.KnownHost? = null,
    val doneServerId: Long? = null,
    /** Connected with a password: ask whether to install the app's key while the password is still at hand. */
    val offerKey: Boolean = false,
)

/**
 * Adds a server: saves the row, connects once with the password (never stored), asks the user
 * to confirm the host key fingerprint, then hands over to the setup screen, which offers to
 * install the app's key.
 */
@HiltViewModel
class AddServerViewModel @Inject constructor(private val servers: Servers) : ViewModel() {
    private val _ui = MutableStateFlow(AddServerUi())
    val ui: StateFlow<AddServerUi> = _ui
    private var pending: Pair<ServerEntity, CharArray>? = null
    private var pendingError: SshError.HostKeyUnknown? = null

    fun connect(name: String, host: String, port: Int, user: String, password: CharArray) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, problem = null) }
            try {
                require(host.isNotBlank() && user.isNotBlank()) { "Enter the server's address and your user name on it." }
                require(port in 1..65535) { "The port is a number from 1 to 65535 (usually 22)." }
                val existing = withContext(Dispatchers.IO) { servers.db.servers().byHostSync(host.trim(), port) }
                val s = existing ?: ServerEntity(name = name.ifBlank { host.trim() }, host = host.trim(), port = port, user = user.trim(), authType = AuthType.PASSWORD)
                    .let { it.copy(id = servers.db.servers().insert(it)) }
                tryOpen(s, password)
            } catch (e: Exception) {
                password.fill('\u0000')
                _ui.update { it.copy(busy = false, problem = describe(e)) }
            }
        }
    }

    private suspend fun tryOpen(s: ServerEntity, password: CharArray) {
        try {
            servers.open(s, password)
            if (s.authType == AuthType.PASSWORD) {
                // Held only until the user answers the key question below.
                keyCandidate = s to password
                _ui.update { it.copy(busy = false, offerKey = true) }
            } else {
                password.fill('\u0000')
                _ui.update { it.copy(busy = false, doneServerId = s.id) }
            }
        } catch (e: SshError.HostKeyUnknown) {
            pending = s to password
            pendingError = e
            _ui.update { it.copy(busy = false, hostKeyPrompt = e.key) }
        } catch (e: Exception) {
            password.fill('\u0000')
            _ui.update { it.copy(busy = false, problem = describe(e)) }
        }
    }

    fun acceptHostKey() {
        val (s, pw) = pending ?: return
        val e = pendingError ?: return
        pending = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { servers.acceptHostKey(s, e) }
            _ui.update { it.copy(hostKeyPrompt = null, busy = true) }
            tryOpen(s, pw)
        }
    }

    fun rejectHostKey() {
        pending?.second?.fill('\u0000')
        pending = null
        _ui.update { it.copy(hostKeyPrompt = null) }
    }

    fun dismissProblem() = _ui.update { it.copy(problem = null) }

    private var keyCandidate: Pair<ServerEntity, CharArray>? = null

    /** Adds the app's key with the authorized-key-add recipe (password used one last time), then switches to key logins. */
    fun installKey(accept: Boolean) {
        val (s, pw) = keyCandidate ?: return
        keyCandidate = null
        _ui.update { it.copy(offerKey = false, busy = accept) }
        viewModelScope.launch {
            try {
                if (accept) {
                    val srv = servers.open(s, pw)
                    val pub = withContext(Dispatchers.IO) { servers.vault.ensureKey() }
                    val out = srv.runRecipe(dev.dreamz.llmwizard.core.recipe.Recipe.bundled("authorized-key-add"), mapOf("publicKey" to pub), null,
                        approve = { _, _ -> true }, events = {})
                    if (out.result?.ok == true) {
                        servers.db.servers().upsert(s.copy(authType = AuthType.KEY))
                        servers.drop(s.id)
                    }
                }
                _ui.update { it.copy(busy = false, doneServerId = s.id) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, problem = describe(e), doneServerId = s.id) }
            } finally {
                pw.fill('\u0000')
            }
        }
    }
}

/** "What could my machine run?" with no server: a typed description goes through the same recommender. */
@HiltViewModel
class ManualViewModel @Inject constructor(catalog: Catalog) : ViewModel() {
    private val recommender = Recommender(catalog)
    val cpuKinds: List<String> = catalog.constants.cpuBandwidth.map { it.label }
    private val examples = mapOf(
        "older Xeon E5 server" to "Intel(R) Xeon(R) CPU E5-2680 v2", "Xeon Scalable" to "Intel(R) Xeon(R) Gold 6130",
        "AMD EPYC" to "AMD EPYC 7302", "Threadripper" to "AMD Ryzen Threadripper 3960X", "low-power" to "Intel(R) Celeron(R) N5105",
        "recent desktop" to "13th Gen Intel(R) Core(TM) i7-13700", "desktop or laptop" to "Intel(R) Core(TM) i5-10400",
    )
    private val _rec = MutableStateFlow<Recommendation?>(null)
    val rec: StateFlow<Recommendation?> = _rec

    fun compute(ramGb: Double, cores: Int, cpuKind: String, avx2: Boolean, vramGb: Double, gpuName: String, freeDiskGb: Double, intent: Intent) {
        val model = examples.entries.firstOrNull { cpuKind.startsWith(it.key) }?.value ?: "unknown"
        val p = HardwareProfile(
            manual = true,
            systemd = true,
            memory = MemoryInfo((ramGb * 1e9).toLong(), (ramGb * 0.9e9).toLong()),
            cpu = CpuInfo(model = model, sockets = 1, coresPerSocket = cores, threadsPerCore = 2, logicalCpus = cores * 2,
                flags = if (avx2) setOf("avx", "avx2", "fma", "f16c") else setOf("avx")),
            nvidia = if (vramGb > 0) NvidiaInfo(SmiState.WORKING, listOf(NvidiaCard(gpuName.ifBlank { "NVIDIA GPU" }, (vramGb * 1024).toLong(), null))) else null,
            disk = DiskInfo(Filesystem("manual", null, null, (freeDiskGb * 1e9).toLong(), "/")),
        )
        _rec.value = recommender.recommend(p, intent)
    }
}

@HiltViewModel
class TranscriptViewModel @Inject constructor(handle: SavedStateHandle, servers: Servers) : ViewModel() {
    private val id: Long = checkNotNull(handle.get<Long>("id"))
    val rows: Flow<List<TranscriptRow>> = servers.db.transcript().forServer("server-$id")
}

@HiltViewModel
class KeyViewModel @Inject constructor(private val servers: Servers) : ViewModel() {
    private val _key = MutableStateFlow<Pair<String, String>?>(null)
    val key: StateFlow<Pair<String, String>?> = _key
    init {
        viewModelScope.launch(Dispatchers.IO) {
            val pub = servers.vault.ensureKey()
            _key.value = pub to (servers.vault.fingerprint() ?: "")
        }
    }
}

@HiltViewModel
class UpgradeViewModel @Inject constructor(val billing: dev.dreamz.llmwizard.billing.PlayBilling, private val entitlements: dev.dreamz.llmwizard.billing.Entitlements) : ViewModel() {
    val pro: StateFlow<Boolean> = entitlements.pro.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    init { billing.loadPrice(); billing.refresh() }
    fun setDebugOverride(on: Boolean) = viewModelScope.launch { entitlements.setDebugOverride(on) }
}
