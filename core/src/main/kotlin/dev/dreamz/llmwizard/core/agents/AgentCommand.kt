package dev.dreamz.llmwizard.core.agents

import dev.dreamz.llmwizard.core.shell.Shell

/**
 * The exact command line each agent is started with (BUILD_PLAN E4.1/E4.2), as a launcher script
 * the preview shows. Every value is passed through [Shell.quote]; nothing the model says ever
 * becomes part of this script.
 *
 * Privacy (SPEC.md rule 9): Aider's analytics and update check are off and LiteLLM uses its local
 * cost map; Open Interpreter runs offline with telemetry disabled.
 */
object AgentCommand {
    /** `$HOME/.local/share/llmwizard`, as the recipes install it. */
    fun dataDir(home: String) = "${Shell.path(home)}/.local/share/llmwizard"

    /** [data] is the llmwizard data folder on the server, normally [dataDir] of the home folder. */
    fun argv(p: AgentProfile, data: String, ollamaApi: String, instructionsFile: String?): List<String> {
        val d = Shell.path(data)
        return when (p.agent) {
            AgentType.AIDER -> buildList {
                add("$d/agents/aider/bin/aider")
                add("--model"); add("ollama_chat/${p.model}")
                addAll(listOf("--analytics-disable", "--no-check-update", "--no-show-model-warnings", "--no-show-release-notes", "--no-pretty"))
                if (p.approval == ApprovalMode.AUTO_RUN) add("--yes-always")
                instructionsFile?.let { add("--read"); add(it) }
                addAll(p.extraArgs)
            }
            AgentType.OPEN_INTERPRETER -> buildList {
                add("$d/agents/open-interpreter/bin/interpreter")
                add("--model"); add("ollama/${p.model}")
                add("--api_base"); add(ollamaApi)
                add("--offline"); add("--disable_telemetry")
                p.contextLength?.let { add("--context_window"); add(it.toString()) }
                p.systemPrompt?.takeIf { it.isNotBlank() }?.let { add("--custom_instructions"); add(it) }
                if (p.approval == ApprovalMode.AUTO_RUN) add("--auto_run")
                addAll(p.extraArgs)
            }
        }
    }

    fun env(p: AgentProfile, ollamaApi: String): Map<String, String> = when (p.agent) {
        AgentType.AIDER -> mapOf("OLLAMA_API_BASE" to ollamaApi, "LITELLM_LOCAL_MODEL_COST_MAP" to "True")
        AgentType.OPEN_INTERPRETER -> mapOf("LITELLM_LOCAL_MODEL_COST_MAP" to "True", "DISABLE_TELEMETRY" to "true")
    }

    /** The launcher file's content: fixed preamble, quoted environment, `exec` of the quoted argv. */
    fun launcher(p: AgentProfile, data: String, ollamaApi: String, instructionsFile: String?): String {
        val problems = p.problems()
        require(problems.isEmpty()) { problems.joinToString(" ") }
        Shell.bind(ollamaApi.removePrefix("http://"))
        val env = env(p, ollamaApi).entries.joinToString(" ") { (k, v) -> "$k=${Shell.quote(v)}" }
        return buildString {
            appendLine("#!/bin/sh")
            appendLine("# Written by llmwizard for profile ${Shell.identifier(p.id)} (${p.agent.label}, ${if (p.approval == ApprovalMode.AUTO_RUN) "AUTO-RUN: acts without asking" else "asks before each command"}).")
            appendLine("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            appendLine("export LANG=C.UTF-8 TERM=xterm-256color")
            p.workDir?.let { appendLine("cd ${Shell.quote(it)} || exit 1") }
            appendLine("exec env $env ${argv(p, data, ollamaApi, instructionsFile).joinToString(" ") { Shell.quote(it) }}")
        }
    }
}
