package dev.dreamz.llmwizard.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** PLAN.md 5.12. */
@Serializable enum class Risk { READ, WRITE, SEND, DESTRUCTIVE }

@Serializable enum class Tier { NORMAL, SHIZUKU, ROOT, SYSTEM }

/** A typed argument. Kept deliberately small: what phone actions need, nothing free-form beyond text. */
@Serializable
data class ToolParam(
    val name: String,
    val type: ParamKind,
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null,
    val maxLength: Int? = null,
    val min: Long? = null,
    val max: Long? = null,
)

@Serializable enum class ParamKind { STRING, INTEGER, BOOLEAN }

@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam>,
    val risk: Risk,
    val minTier: Tier = Tier.NORMAL,
    /** The Android permissions the executor needs (documentation for the app; checked there). */
    val permissions: List<String> = emptyList(),
    /** True when the tool's result contains text from outside (messages): PLAN.md 5.13 applies. */
    val outsideContent: Boolean = false,
) {
    /** The function definition Ollama's `/api/chat` expects in its `tools` array. */
    fun toOllamaJson(): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    for (p in params) putJsonObject(p.name) {
                        put("type", when (p.type) { ParamKind.STRING -> "string"; ParamKind.INTEGER -> "integer"; ParamKind.BOOLEAN -> "boolean" })
                        put("description", p.description)
                        p.enum?.let { e -> put("enum", JsonArray(e.map(::JsonPrimitive))) }
                    }
                }
                put("required", buildJsonArray { params.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } })
            }
        }
    }
}

/**
 * The tool registry: data, so adding a tool never touches the loop. Six to start (PLAN.md 5.12);
 * the list grows only when the eval set shows models still pick correctly.
 */
class ToolRegistry(val tools: List<ToolSpec>) {
    fun byName(name: String) = tools.firstOrNull { it.name == name }

    fun ollamaTools(): List<JsonElement> = tools.map { it.toOllamaJson() }

    companion object {
        val DEFAULT = ToolRegistry(listOf(
            ToolSpec(
                "contacts_find", "Look up a contact by name. Returns matching names and the labels of their numbers.",
                listOf(ToolParam("name", ParamKind.STRING, "The name as the user said it", maxLength = 100)),
                Risk.READ, permissions = listOf("android.permission.READ_CONTACTS"),
            ),
            ToolSpec(
                "sms_send", "Send a text message (SMS) to a contact or phone number.",
                listOf(
                    ToolParam("to", ParamKind.STRING, "Contact name or phone number, exactly as the user said it", maxLength = 100),
                    ToolParam("body", ParamKind.STRING, "The message text", maxLength = 1600),
                ),
                Risk.SEND, permissions = listOf("android.permission.SEND_SMS"),
            ),
            ToolSpec(
                "sms_read_recent", "Read the most recent text messages, optionally only those from one contact.",
                listOf(
                    ToolParam("from", ParamKind.STRING, "Only messages from this contact or number", required = false, maxLength = 100),
                    ToolParam("count", ParamKind.INTEGER, "How many messages, 1 to 20", required = false, min = 1, max = 20),
                ),
                Risk.READ, permissions = listOf("android.permission.READ_SMS"), outsideContent = true,
            ),
            ToolSpec(
                "call_place", "Place a phone call to a contact or phone number.",
                listOf(ToolParam("to", ParamKind.STRING, "Contact name or phone number, exactly as the user said it", maxLength = 100)),
                Risk.SEND, permissions = listOf("android.permission.CALL_PHONE"),
            ),
            ToolSpec(
                "clock_set", "Set an alarm for a time of day, or a timer for a duration.",
                listOf(
                    ToolParam("kind", ParamKind.STRING, "alarm or timer", enum = listOf("alarm", "timer")),
                    ToolParam("time", ParamKind.STRING, "For an alarm: 24-hour time HH:MM. Leave empty for a timer.", required = false, maxLength = 5),
                    ToolParam("seconds", ParamKind.INTEGER, "For a timer: its length in seconds", required = false, min = 1, max = 86400),
                    ToolParam("label", ParamKind.STRING, "A short label", required = false, maxLength = 60),
                ),
                Risk.WRITE,
            ),
            ToolSpec(
                "calendar_create_event", "Add an event to the calendar.",
                listOf(
                    ToolParam("title", ParamKind.STRING, "Event title", maxLength = 200),
                    ToolParam("start", ParamKind.STRING, "Start as YYYY-MM-DDTHH:MM in local time", maxLength = 16),
                    ToolParam("minutes", ParamKind.INTEGER, "Length in minutes", required = false, min = 1, max = 1440),
                    ToolParam("location", ParamKind.STRING, "Where", required = false, maxLength = 200),
                ),
                Risk.WRITE, permissions = listOf("android.permission.WRITE_CALENDAR"),
            ),
        ))
    }
}
