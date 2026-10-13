package dev.dreamz.llmwizard.core.agents

import dev.dreamz.llmwizard.core.remote.Remote
import dev.dreamz.llmwizard.core.shell.Shell
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

@Serializable
enum class SessionStatus { RUNNING, STOPPED }

@Serializable
data class AgentSession(
    val id: String,
    val profileId: String,
    val agent: AgentType,
    val model: String,
    val workDir: String?,
    val approval: ApprovalMode,
    val tmuxSession: String,
    val startedAt: String,
    val status: SessionStatus = SessionStatus.RUNNING,
)

/** Keys the phone may send besides text: the answers agents ask for, and an interrupt. */
enum class AgentKey(val tmux: String) { ENTER("Enter"), CTRL_C("C-c"), YES("y"), NO("n"), ESCAPE("Escape") }

/**
 * Agent sessions in tmux on the server (BUILD_PLAN E4.3), so they survive the phone going away.
 * Each session's terminal output is also appended to a log file, so a reconnecting phone can
 * catch up. Every script here is fixed text plus [Shell.quote]d values.
 */
class AgentSessions(
    private val remote: Remote,
    /** Overrides `~/.local/share/llmwizard` (tests). */
    private val dataDirOverride: String? = null,
    private val now: () -> String = { java.time.Instant.now().toString() },
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private suspend fun data() = dataDirOverride ?: AgentCommand.dataDir(remote.home())
    private suspend fun dir() = data() + "/agents"
    private suspend fun sessionDir(id: String) = dir() + "/sessions/" + Shell.identifier(id)

    // ---- profiles ----

    suspend fun saveProfile(p: AgentProfile) {
        val problems = p.problems()
        require(problems.isEmpty()) { problems.joinToString(" ") }
        remote.writeFile(dir() + "/profiles/${p.id}.json", json.encodeToString(AgentProfile.serializer(), p).toByteArray())
    }

    suspend fun profiles(): List<AgentProfile> {
        val r = remote.probe("for f in ${Shell.quote(dir() + "/profiles")}/*.json; do [ -f \"\$f\" ] && { cat \"\$f\"; printf '\\n\\036\\n'; }; done; true", 30.seconds)
        return r.out.split("\u001e").map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { runCatching { json.decodeFromString(AgentProfile.serializer(), it) }.getOrNull() }
    }

    suspend fun deleteProfile(id: String) {
        remote.probe("rm -f ${Shell.quote(dir() + "/profiles/" + Shell.identifier(id) + ".json")}", 30.seconds)
    }

    /** Which agent tools are installed (their launchers exist). */
    suspend fun installed(): Set<AgentType> {
        val d = dir()
        val r = remote.probe(AgentType.entries.joinToString("\n") { "[ -x ${Shell.quote("$d/${it.dir}/bin/" + if (it == AgentType.AIDER) "aider" else "interpreter")} ] && echo ${it.name}" } + "\ntrue", 30.seconds)
        return r.out.lines().mapNotNull { l -> AgentType.entries.firstOrNull { it.name == l.trim() } }.toSet()
    }

    // ---- sessions ----

    /** The launcher a new session would run, for the preview. */
    suspend fun preview(p: AgentProfile, ollamaApi: String): String =
        AgentCommand.launcher(p, data(), ollamaApi, if (p.systemPrompt.isNullOrBlank()) null else "<instructions file>")

    suspend fun start(p: AgentProfile, ollamaApi: String, sessionId: String = newId()): AgentSession {
        val sd = sessionDir(sessionId)
        // Aider reads instructions from a file; Open Interpreter takes them as an argument.
        val instructions = p.systemPrompt?.takeIf { it.isNotBlank() && p.agent == AgentType.AIDER }?.let { text ->
            "$sd/instructions.md".also { remote.writeFile(it, text.toByteArray()) }
        }
        remote.writeFile("$sd/run.sh", AgentCommand.launcher(p, data(), ollamaApi, instructions).toByteArray())
        val s = AgentSession(sessionId, p.id, p.agent, p.model, p.workDir, p.approval, tmuxName(p.agent, sessionId), now())
        remote.writeFile("$sd/session.json", json.encodeToString(AgentSession.serializer(), s).toByteArray())
        val r = remote.probe(
            """
            P_session=${Shell.quote(s.tmuxSession)}
            P_dir=${Shell.quote(sd)}
            command -v tmux >/dev/null || { echo "tmux is not installed"; exit 3; }
            chmod 700 "${'$'}P_dir/run.sh"
            tmux new-session -d -s "${'$'}P_session" -x 160 -y 48 "${'$'}P_dir/run.sh"
            tmux set-option -t "${'$'}P_session" remain-on-exit on >/dev/null
            tmux pipe-pane -o -t "${'$'}P_session" "cat >> '${'$'}P_dir/log.txt'"
            """.trimIndent(), 30.seconds,
        )
        check(r.ok) { "Could not start the session: ${(r.out + r.err).trim().take(300)}" }
        return s
    }

    /** Sessions llmwizard started, newest first; STOPPED when the agent has exited. */
    suspend fun list(): List<AgentSession> {
        val d = dir() + "/sessions"
        val r = remote.probe(
            """
            P_dir=${Shell.quote(d)}
            for f in "${'$'}P_dir"/*/session.json; do
                [ -f "${'$'}f" ] || continue
                s=${'$'}(sed -n 's/.*"tmuxSession": *"\([^"]*\)".*/\1/p' "${'$'}f")
                dead=${'$'}(tmux display-message -p -t "${'$'}s" '#{pane_dead}' 2>/dev/null || echo gone)
                printf 'STATE %s\n' "${'$'}dead"
                cat "${'$'}f"; printf '\n\036\n'
            done
            true
            """.trimIndent(), 30.seconds,
        )
        return r.out.split("\u001e").map { it.trim() }.filter { it.startsWith("STATE ") }.mapNotNull { block ->
            val state = block.lineSequence().first().removePrefix("STATE ").trim()
            runCatching { json.decodeFromString(AgentSession.serializer(), block.substringAfter('\n')) }.getOrNull()
                ?.copy(status = if (state == "0") SessionStatus.RUNNING else SessionStatus.STOPPED)
        }.sortedByDescending { it.startedAt }
    }

    /** Types [text] into the agent and presses Enter. Text is sent literally (`send-keys -l`). */
    suspend fun send(s: AgentSession, text: String) {
        Shell.text(text, 8000)
        val r = remote.probe("P_session=${Shell.quote(s.tmuxSession)}\nP_text=${Shell.quote(text)}\ntmux send-keys -t \"\$P_session\" -l -- \"\$P_text\" && tmux send-keys -t \"\$P_session\" Enter", 30.seconds)
        check(r.ok) { "The session is not running." }
    }

    suspend fun key(s: AgentSession, key: AgentKey) {
        val r = remote.probe("P_session=${Shell.quote(s.tmuxSession)}\ntmux send-keys -t \"\$P_session\" ${key.tmux}" + if (key == AgentKey.YES || key == AgentKey.NO) " Enter" else "", 30.seconds)
        check(r.ok) { "The session is not running." }
    }

    /** What the terminal shows now, plus up to [history] lines of scrollback. */
    suspend fun screen(s: AgentSession, history: Int = 200): String {
        val r = remote.probe("P_session=${Shell.quote(s.tmuxSession)}\ntmux capture-pane -p -J -t \"\$P_session\" -S -${history.coerceIn(0, 5000)}", 30.seconds)
        check(r.ok) { "The session is not running." }
        return r.out.trimEnd()
    }

    /** The end of the session's log file, which outlives the session. ANSI codes are stripped. */
    suspend fun log(s: AgentSession, maxBytes: Int = 64_000): String {
        val r = remote.probe("tail -c ${maxBytes.coerceIn(1, 1_000_000)} ${Shell.quote(sessionDir(s.id) + "/log.txt")} 2>/dev/null; true", 30.seconds)
        return r.out.replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "").replace(Regex("\u001B[()][A-Z0-9]"), "").replace("\r", "")
    }

    suspend fun stop(s: AgentSession) {
        remote.probe("tmux kill-session -t ${Shell.quote(s.tmuxSession)} 2>/dev/null; true", 30.seconds)
    }

    /** Stops the session if needed and deletes its folder (launcher, instructions, log). */
    suspend fun forget(s: AgentSession) {
        stop(s)
        remote.probe("rm -rf ${Shell.quote(sessionDir(s.id))}", 30.seconds)
    }

    companion object {
        fun tmuxName(agent: AgentType, id: String) = "llmw-${agent.dir}-${Shell.identifier(id)}"
        fun newId(): String = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) +
            "-" + (1..4).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
    }
}
