package dev.dreamz.llmwizard.core.recipe

import dev.dreamz.llmwizard.core.shell.Shell
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A recipe is data (PLAN.md 5.6): every command a user's machine will run is in here, shown
 * in the preview before it runs. Parameter values never appear inside the scripts; the
 * engine prepends `P_<name>='…'` assignments built by [Shell.quote], and scripts use
 * `"$P_<name>"`.
 */
@Serializable
data class Recipe(
    val id: String,
    val version: Int,
    val title: String,
    val description: String = "",
    val targets: Targets = Targets(),
    val params: Map<String, ParamSpec> = emptyMap(),
    val preflight: List<Check> = emptyList(),
    val steps: List<StepSpec>,
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Recipe = json.decodeFromString(serializer(), text)

        fun bundled(id: String): Recipe = parse(
            requireNotNull(Recipe::class.java.getResourceAsStream("/llmwizard/recipes/${Shell.identifier(id)}.json")) {
                "bundled recipe $id missing"
            }.readBytes().toString(Charsets.UTF_8),
        )

        val BUNDLED = listOf("ollama-install", "ollama-uninstall", "ollama-update", "ollama-configure", "authorized-key-add", "model-pull")
    }
}

@Serializable
data class Targets(val os: List<String> = emptyList(), val arch: List<String> = emptyList(), val init: String? = null)

@Serializable
enum class ParamType {
    @SerialName("tag") TAG,
    @SerialName("path") PATH,
    @SerialName("bind") BIND,
    @SerialName("int") INT,
    @SerialName("version") VERSION,
    @SerialName("sha256") SHA256,
    @SerialName("duration") DURATION,
    @SerialName("sshPublicKey") SSH_PUBLIC_KEY,
    @SerialName("bool") BOOL,
    @SerialName("ident") IDENT,
}

@Serializable
data class ParamSpec(
    val type: ParamType,
    val default: String? = null,
    /** An empty value is allowed (and means "not set") unless this is true. */
    val required: Boolean = false,
    val description: String = "",
    val min: Long? = null,
    val max: Long? = null,
)

@Serializable
data class Check(val id: String, val title: String, val run: String, val failMessage: String)

@Serializable
data class StepSpec(
    val id: String,
    val title: String,
    val why: String,
    val sudo: Boolean = false,
    /** Read-only probe; exit 0 means the step's work is already done. Runs as the login user. */
    val skipIf: String? = null,
    val run: String,
    /** Read-only probe run after the step; non-zero fails the step. */
    val verify: String? = null,
    /** Reverses this step after a failed install. Runs with the same privilege. */
    val undo: String? = null,
    val detached: Boolean = false,
    val timeoutSec: Int = 600,
    /** What this step creates, recorded in the manifest only when the step actually ran. */
    val creates: List<Created> = emptyList(),
    /** What this step removes; dropped from the manifest when the step succeeds. */
    val removes: List<Created> = emptyList(),
    /** Read-only probe run after success; its `key=value` lines are stored in the manifest settings. */
    val facts: String? = null,
    /** Manifest conditions, all of which must hold for the step to apply: "created:<kind>:<name>" or "param:<name>". */
    val requires: List<String> = emptyList(),
)

@Serializable
data class Created(val kind: String, val name: String)

/** Validates a raw parameter value by type. Throws with a plain message when it is not acceptable. */
object Params {
    private val VERSION = Regex("^(latest|[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.]+)?)$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val DURATION = Regex("^(-1|0|[0-9]{1,6}(s|m|h))$")
    private val PUBKEY = Regex("^(ssh-ed25519|ecdsa-sha2-nistp256|ecdsa-sha2-nistp384|ssh-rsa) [A-Za-z0-9+/]+={0,3}( [A-Za-z0-9@._+-]{1,64})?$")

    fun validate(name: String, spec: ParamSpec, value: String): String {
        if (value.isEmpty()) {
            require(!spec.required) { "Parameter $name is required" }
            return value
        }
        when (spec.type) {
            ParamType.TAG -> Shell.modelTag(value)
            ParamType.PATH -> Shell.path(value)
            ParamType.BIND -> Shell.bind(value)
            ParamType.INT -> Shell.integer(value, (spec.min ?: Long.MIN_VALUE)..(spec.max ?: Long.MAX_VALUE))
            ParamType.VERSION -> require(VERSION.matches(value)) { "Not a version: $value" }
            ParamType.SHA256 -> require(SHA256.matches(value)) { "Not a sha256 hex digest" }
            ParamType.DURATION -> require(DURATION.matches(value)) { "Not a duration like 30m: $value" }
            ParamType.SSH_PUBLIC_KEY -> require(PUBKEY.matches(Shell.text(value))) { "Not an SSH public key line" }
            ParamType.BOOL -> require(value == "yes" || value == "no") { "Expected yes or no for $name" }
            ParamType.IDENT -> Shell.identifier(value)
        }
        return value
    }
}
