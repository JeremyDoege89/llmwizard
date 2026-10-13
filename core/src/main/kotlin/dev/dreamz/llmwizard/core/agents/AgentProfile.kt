package dev.dreamz.llmwizard.core.agents

import dev.dreamz.llmwizard.core.shell.Shell
import kotlinx.serialization.Serializable

/** A coding agent that runs in a terminal on the server (BUILD_PLAN E4). */
@Serializable
enum class AgentType(val dir: String, val label: String) {
    AIDER("aider", "Aider"),
    OPEN_INTERPRETER("open-interpreter", "Open Interpreter"),
}

/**
 * Who says yes to each command or edit (Jay's call, DECISIONS.md 2026-10-13). [APPROVE_EACH] is
 * the default: the agent stops and asks, and the phone shows the question with Yes/No buttons.
 * [AUTO_RUN] lets the agent act unattended; the user has to pick it per profile.
 */
@Serializable
enum class ApprovalMode { APPROVE_EACH, AUTO_RUN }

/** A saved way of starting an agent (BUILD_PLAN E4.4). Kept on the server so phone and CLI share it. */
@Serializable
data class AgentProfile(
    val id: String,
    val name: String,
    val agent: AgentType,
    /** Ollama tag of the model the agent talks to. */
    val model: String,
    /** Extra instructions (Aider: a read-only conventions file; Open Interpreter: custom instructions). */
    val systemPrompt: String? = null,
    /** Absolute path on the server. Aider wants a git repository here. */
    val workDir: String? = null,
    val approval: ApprovalMode = ApprovalMode.APPROVE_EACH,
    /** Context window to ask Ollama for; null leaves the agent's default. */
    val contextLength: Int? = null,
    /** Extra command-line arguments, each passed as one quoted argument. */
    val extraArgs: List<String> = emptyList(),
) {
    fun problems(): List<String> = buildList {
        if (runCatching { Shell.identifier(id) }.isFailure) add("The profile id may use letters, digits, '.', '_' and '-'.")
        if (name.isBlank() || name.length > 80) add("Give the profile a name of up to 80 characters.")
        if (runCatching { Shell.modelTag(model) }.isFailure) add("The model name is not valid.")
        workDir?.let { if (runCatching { Shell.path(it) }.isFailure) add("The working folder must be an absolute path without spaces or special characters.") }
        systemPrompt?.let { if (it.length > 16_000) add("The instructions are longer than 16,000 characters.") }
        contextLength?.let { if (it !in 2048..262_144) add("Context length must be between 2048 and 262144.") }
        if (extraArgs.size > 20 || extraArgs.any { a -> a.isEmpty() || a.length > 200 || a.any { it.code < 0x20 } }) add("Extra arguments must be single-line, up to 200 characters each.")
    }
}
