package dev.dreamz.llmwizard.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dreamz.llmwizard.conn.Servers
import dev.dreamz.llmwizard.core.ollama.ChatMessage
import dev.dreamz.llmwizard.core.ollama.ChatRequest
import dev.dreamz.llmwizard.core.ollama.LocalModel
import dev.dreamz.llmwizard.core.ollama.OllamaClient
import dev.dreamz.llmwizard.data.AuthType
import dev.dreamz.llmwizard.data.ConversationEntity
import dev.dreamz.llmwizard.data.MessageEntity
import dev.dreamz.llmwizard.data.ServerEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the reply is doing right now. Loading a model is its own state (PLAN.md 5.8). */
enum class ReplyPhase { IDLE, CONNECTING, LOADING_MODEL, THINKING, GENERATING }

data class ChatUi(
    val server: ServerEntity? = null,
    val models: List<LocalModel> = emptyList(),
    val model: String? = null,
    val conversationId: Long? = null,
    val phase: ReplyPhase = ReplyPhase.IDLE,
    /** The reply being streamed, before it is saved. */
    val partial: String = "",
    val problem: Problem? = null,
    val needsPassword: Boolean = false,
)

/**
 * Chat with the server's model through the SSH tunnel: streaming, stop, model picker, history
 * in Room, and the measured tokens per second of each reply.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(handle: SavedStateHandle, private val servers: Servers) : ViewModel() {
    private val serverId: Long = checkNotNull(handle.get<Long>("id"))
    private val dao = servers.db.chat()
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui
    private val conversation = MutableStateFlow<Long?>(null)
    val messages: StateFlow<List<MessageEntity>> = conversation.flatMapLatest { id -> if (id == null) emptyFlow() else dao.messages(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val conversations: Flow<List<ConversationEntity>> = dao.conversations(serverId)
    private var client: OllamaClient? = null
    private var replying: Job? = null
    private var pendingSend: String? = null

    init {
        viewModelScope.launch {
            val s = servers.db.servers().get(serverId) ?: return@launch
            _ui.update { it.copy(server = s, model = s.model) }
            refreshModels()
        }
    }

    private suspend fun client(password: CharArray? = null): OllamaClient = client ?: run {
        val srv = servers.open(checkNotNull(_ui.value.server), password)
        srv.ollama(srv.ollamaEndpoint(srv.manifest())).also { client = it }
    }

    fun refreshModels() = viewModelScope.launch {
        try {
            val models = client().tags()
            _ui.update { u -> u.copy(models = models, model = u.model?.takeIf { m -> models.any { it.name == m } } ?: models.firstOrNull()?.name) }
        } catch (e: Exception) {
            if (_ui.value.server?.authType == AuthType.PASSWORD) _ui.update { it.copy(needsPassword = true) } else _ui.update { it.copy(problem = describe(e)) }
        }
    }

    fun submitPassword(pw: CharArray) = viewModelScope.launch {
        _ui.update { it.copy(needsPassword = false) }
        try { client(pw); refreshModels(); pendingSend?.let { pendingSend = null; send(it) } } catch (e: Exception) { _ui.update { it.copy(problem = describe(e)) } } finally { pw.fill('\u0000') }
    }

    fun cancelPassword() = _ui.update { it.copy(needsPassword = false) }
    fun dismissProblem() = _ui.update { it.copy(problem = null) }
    fun chooseModel(m: String) = _ui.update { it.copy(model = m) }

    fun open(conversationId: Long?) {
        conversation.value = conversationId
        _ui.update { it.copy(conversationId = conversationId, partial = "") }
        conversationId?.let { id -> viewModelScope.launch { dao.conversation(id)?.let { c -> _ui.update { it.copy(model = c.model) } } } }
    }

    fun deleteConversation(id: Long) = viewModelScope.launch {
        dao.deleteMessages(id); dao.deleteConversation(id)
        if (conversation.value == id) open(null)
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || replying?.isActive == true) return
        val model = _ui.value.model ?: run { _ui.update { it.copy(problem = Problem("No model", "There is no model on this server yet.", "Download one from the Models screen.")) }; return }
        replying = viewModelScope.launch {
            _ui.update { it.copy(phase = ReplyPhase.CONNECTING, partial = "", problem = null) }
            val c = try { client() } catch (e: Exception) {
                if (_ui.value.server?.authType == AuthType.PASSWORD) { pendingSend = trimmed; _ui.update { it.copy(needsPassword = true, phase = ReplyPhase.IDLE) } }
                else _ui.update { it.copy(problem = describe(e), phase = ReplyPhase.IDLE) }
                return@launch
            }
            val convId = conversation.value ?: dao.insert(ConversationEntity(serverId = serverId, model = model, title = trimmed.take(60))).also { open(it) }
            dao.insert(MessageEntity(conversationId = convId, role = "user", content = trimmed))
            val history = dao.messagesOnce(convId).map { ChatMessage(it.role, it.content) }
            val loaded = runCatching { c.ps().any { it.name == model } }.getOrDefault(true)
            _ui.update { it.copy(phase = if (loaded) ReplyPhase.THINKING else ReplyPhase.LOADING_MODEL) }
            val sb = StringBuilder()
            var tps: Double? = null
            try {
                c.chat(ChatRequest(model, history, think = false)).collect { ch ->
                    ch.message?.content?.takeIf { it.isNotEmpty() }?.let { sb.append(it); _ui.update { u -> u.copy(phase = ReplyPhase.GENERATING, partial = sb.toString()) } }
                    if (ch.done) tps = ch.tokensPerSecond
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stopped by the user: keep what arrived.
            } catch (e: Exception) {
                client = null
                _ui.update { it.copy(problem = describe(e)) }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    if (sb.isNotEmpty()) dao.insert(MessageEntity(conversationId = convId, role = "assistant", content = sb.toString(), tokensPerSecond = tps))
                    dao.conversation(convId)?.let { dao.upsert(it.copy(updatedAt = System.currentTimeMillis(), model = model)) }
                    _ui.update { it.copy(phase = ReplyPhase.IDLE, partial = "") }
                }
            }
        }
    }

    fun stop() { replying?.cancel() }

    override fun onCleared() { client?.close() }
}
