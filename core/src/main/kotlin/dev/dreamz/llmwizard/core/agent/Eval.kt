package dev.dreamz.llmwizard.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime

@Serializable
data class EvalItem(val id: String, val request: String, val tool: String?, val args: Map<String, String> = emptyMap())

@Serializable
data class EvalOutcome(val id: String, val request: String, val pass: Boolean, val got: String, val why: String?, val millis: Long)

@Serializable
data class EvalReport(val brain: String, val total: Int, val passed: Int, val medianMillis: Long, val p90Millis: Long, val perTool: Map<String, String>, val outcomes: List<EvalOutcome>) {
    val accuracy: Double get() = if (total == 0) 0.0 else passed.toDouble() / total
}

/** The eval set (PLAN.md 5.14): how often a brain proposes the right tool, and how fast. */
object Eval {
    /** The eval's fixed "now": Friday 2026-10-09 10:00. */
    val CLOCK: () -> LocalDateTime = { LocalDateTime.of(2026, 10, 9, 10, 0) }

    fun bundled(): List<EvalItem> = parse(
        requireNotNull(Eval::class.java.getResourceAsStream("/llmwizard/eval/commands.jsonl")) { "eval set missing" }
            .readBytes().toString(Charsets.UTF_8),
    )

    fun parse(text: String): List<EvalItem> = text.lineSequence().filter { it.isNotBlank() }
        .map { Json.decodeFromString(EvalItem.serializer(), it) }.toList()

    /** Null when [p] is what [item] expects, else the reason it is not. */
    fun judge(item: EvalItem, p: Proposal): String? = when (p) {
        is Proposal.Answer -> if (item.tool == null) null else "answered in words instead of calling ${item.tool}"
        is Proposal.Rejected -> if (item.tool == null) "proposed an invalid call: ${p.reason}" else "invalid call: ${p.reason}"
        is Proposal.Call -> when {
            item.tool == null -> "called ${p.tool} but no tool was expected"
            p.tool != item.tool -> "called ${p.tool}, expected ${item.tool}"
            else -> item.args.entries.firstNotNullOfOrNull { (k, want) ->
                val got = p.args[k] ?: return@firstNotNullOfOrNull "missing $k"
                if (matches(want, got)) null else "$k was '$got', expected '$want'"
            }
        }
    }

    private fun matches(want: String, got: String): Boolean {
        if (want.startsWith("~")) return got.contains(want.drop(1), ignoreCase = true)
        val time = Regex("^(\\d{1,2}):(\\d{2})$")
        val a = time.find(want); val b = time.find(got.trim())
        if (a != null && b != null) return a.groupValues[1].toInt() == b.groupValues[1].toInt() && a.groupValues[2] == b.groupValues[2]
        return want.trim().equals(got.trim(), ignoreCase = true)
    }

    suspend fun run(brain: Brain, tools: ToolRegistry, items: List<EvalItem>, onEach: (EvalOutcome) -> Unit = {}): EvalReport {
        val outcomes = items.map { item ->
            val p = runCatching { brain.propose(item.request, tools) }.getOrElse { Proposal.Rejected("error: ${it.message}", "", brain.name, 0) }
            val why = judge(item, p)
            val got = when (p) {
                is Proposal.Call -> "${p.tool} ${p.args}"
                is Proposal.Answer -> "answer: ${p.text.take(80)}"
                is Proposal.Rejected -> "rejected: ${p.reason}"
            }
            val ms = when (p) { is Proposal.Call -> p.millis; is Proposal.Answer -> p.millis; is Proposal.Rejected -> p.millis }
            EvalOutcome(item.id, item.request, why == null, got, why, ms).also(onEach)
        }
        val times = outcomes.map { it.millis }.sorted()
        val perTool = items.zip(outcomes).groupBy { it.first.tool ?: "(none)" }.mapValues { (_, l) -> "${l.count { it.second.pass }}/${l.size}" }
        return EvalReport(brain.name, outcomes.size, outcomes.count { it.pass },
            times.getOrElse(times.size / 2) { 0 }, times.getOrElse((times.size * 9) / 10) { 0 }, perTool, outcomes)
    }
}
