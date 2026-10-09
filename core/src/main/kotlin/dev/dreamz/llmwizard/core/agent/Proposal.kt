package dev.dreamz.llmwizard.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** What a brain returns. It only ever names a tool and arguments (SPEC.md rule 10). */
@Serializable
sealed interface Proposal {
    /** A tool call, after validation against the registry. Execution is the app's decision. */
    @Serializable
    data class Call(val tool: String, val args: Map<String, String>, val risk: Risk, val brain: String, val millis: Long) : Proposal

    /** The model answered in words instead (or there is nothing to do). */
    @Serializable
    data class Answer(val text: String, val brain: String, val millis: Long) : Proposal

    /** The model proposed something the registry refuses. Shown, never executed. */
    @Serializable
    data class Rejected(val reason: String, val raw: String, val brain: String, val millis: Long) : Proposal
}

/**
 * Checks a raw tool call against the registry: known tool, known arguments only, required ones
 * present, types, enums, ranges and lengths. Code decides; the model only proposes.
 */
object ProposalValidator {

    sealed interface Result {
        data class Ok(val tool: ToolSpec, val args: Map<String, String>) : Result
        data class Bad(val reason: String) : Result
    }

    fun validate(registry: ToolRegistry, name: String, rawArgs: JsonObject): Result {
        val spec = registry.byName(name) ?: return Result.Bad("Unknown tool '$name'")
        val unknown = rawArgs.keys - spec.params.map { it.name }.toSet()
        if (unknown.isNotEmpty()) return Result.Bad("Unknown argument(s) for $name: ${unknown.joinToString()}")
        val out = linkedMapOf<String, String>()
        for (p in spec.params) {
            val v = rawArgs[p.name]?.takeUnless { it is JsonNull || (it is JsonPrimitive && it.isString && it.content.isEmpty()) }
            if (v == null) {
                if (p.required) return Result.Bad("Missing argument '${p.name}' for $name")
                continue
            }
            val prim = v as? JsonPrimitive ?: return Result.Bad("Argument '${p.name}' must be a single value")
            val text = when (p.type) {
                ParamKind.STRING -> prim.content.also { s ->
                    if (p.maxLength != null && s.length > p.maxLength) return Result.Bad("'${p.name}' is too long")
                    if (s.any { it.code < 0x20 && it != '\n' }) return Result.Bad("'${p.name}' has control characters")
                }
                ParamKind.INTEGER -> (prim.longOrNull ?: prim.content.trim().toLongOrNull())?.also { n ->
                    if (p.min != null && n < p.min || p.max != null && n > p.max) return Result.Bad("'${p.name}' is out of range")
                }?.toString() ?: return Result.Bad("'${p.name}' must be a whole number")
                ParamKind.BOOLEAN -> (prim.booleanOrNull ?: return Result.Bad("'${p.name}' must be true or false")).toString()
            }
            if (p.enum != null && text !in p.enum) return Result.Bad("'${p.name}' must be one of ${p.enum.joinToString()}")
            out[p.name] = text
        }
        // Tool-specific shape checks the schema alone cannot express.
        if (spec.name == "clock_set") {
            val kind = out["kind"]
            if (kind == "alarm" && out["time"]?.matches(Regex("^([01]?[0-9]|2[0-3]):[0-5][0-9]$")) != true) return Result.Bad("An alarm needs a time HH:MM")
            if (kind == "timer" && out["seconds"] == null) return Result.Bad("A timer needs a length in seconds")
        }
        if (spec.name == "calendar_create_event" && out["start"]?.matches(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$")) != true) {
            return Result.Bad("The event start must be YYYY-MM-DDTHH:MM")
        }
        return Result.Ok(spec, out)
    }

    fun asString(e: JsonElement) = e.toString()
}
