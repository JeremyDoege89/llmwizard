package dev.dreamz.llmwizard.core.server

import dev.dreamz.llmwizard.core.ollama.OllamaClient
import dev.dreamz.llmwizard.core.ollama.TunnelSocketFactory
import dev.dreamz.llmwizard.core.recipe.EngineEvent
import dev.dreamz.llmwizard.core.recipe.Manifest
import dev.dreamz.llmwizard.core.recipe.PlannedStep
import dev.dreamz.llmwizard.core.recipe.Recipe
import dev.dreamz.llmwizard.core.recipe.RecipeEngine
import dev.dreamz.llmwizard.core.recipe.ResumeStore
import dev.dreamz.llmwizard.core.recipe.RunResult
import dev.dreamz.llmwizard.core.recipe.SshTargetOps
import dev.dreamz.llmwizard.core.recipe.Transcript
import dev.dreamz.llmwizard.core.recipe.TranscriptEntry
import dev.dreamz.llmwizard.core.remote.Privilege
import dev.dreamz.llmwizard.core.remote.Remote
import dev.dreamz.llmwizard.core.remote.SudoSituation
import dev.dreamz.llmwizard.core.scan.HardwareProfile
import dev.dreamz.llmwizard.core.scan.ScanParser
import dev.dreamz.llmwizard.core.ssh.SshSession
import kotlin.time.Duration.Companion.seconds

/** Why a run cannot get root. Each has a plain message for the UI. */
sealed class PrivilegeProblem(message: String) : Exception(message) {
    class NoSudo : PrivilegeProblem("This user cannot use sudo, so nothing can be installed. Add the user to the sudo group, or connect as root with a key.")
    class WrongPassword : PrivilegeProblem("sudo did not accept that password (or this user is not allowed to use sudo).")
    class PasswordNeeded : PrivilegeProblem("This step needs your sudo password.")
}

/**
 * Everything the CLI and the app do with one connected server, so both stay thin:
 * scan, read the manifest, run recipes, and talk to Ollama through the tunnel.
 */
class Server(
    val session: SshSession,
    private val resume: ResumeStore,
    private val transcript: Transcript = Transcript { },
) {
    val remote = Remote(session)

    suspend fun scan(): Pair<String, HardwareProfile> {
        val r = remote.probe(ScanParser.script, 120.seconds)
        transcript.record(TranscriptEntry(java.time.Instant.now().toString(), session.target.name, "scan", "(scan.sh)", r.exitCode, r.err.takeLast(500)))
        return r.out to ScanParser.parse(r.out)
    }

    suspend fun manifest(): Manifest? =
        remote.readFile(remote.stateDir() + "/installed.json")?.let { runCatching { Manifest.decode(it) }.getOrNull() }

    /**
     * Works out how to get root. [password] is asked only when sudo needs one; the result
     * holds it for this run only, and the caller wipes it with [Privilege.SudoPassword.wipe].
     */
    suspend fun privilege(password: suspend () -> CharArray?): Privilege = when (remote.detectPrivilege()) {
        SudoSituation.ROOT -> Privilege.Root
        SudoSituation.PASSWORDLESS -> Privilege.PasswordlessSudo
        SudoSituation.NO_SUDO -> throw PrivilegeProblem.NoSudo()
        SudoSituation.NEEDS_PASSWORD -> {
            val pw = password() ?: throw PrivilegeProblem.PasswordNeeded()
            if (!remote.checkSudoPassword(pw)) { pw.fill('\u0000'); throw PrivilegeProblem.WrongPassword() }
            Privilege.SudoPassword(pw)
        }
    }

    /** One recipe run: resolve, preflight, plan; [approve] sees the preview and may stop it. */
    suspend fun runRecipe(
        recipe: Recipe,
        params: Map<String, String>,
        privilege: Privilege?,
        approve: suspend (preflightFailures: List<String>, plan: List<PlannedStep>) -> Boolean,
        events: suspend (EngineEvent) -> Unit,
    ): RecipeOutcome {
        val ops = SshTargetOps(remote, privilege, resume, transcript)
        val engine = RecipeEngine(ops)
        val values = engine.resolve(recipe, params)
        val failures = engine.preflight(recipe, values).filterNot { it.ok }.map { it.check.failMessage }
        val plan = engine.plan(recipe, values, manifest())
        if (!approve(failures, plan) || failures.isNotEmpty()) return RecipeOutcome(plan, null, engine, values)
        val result = engine.execute(recipe, plan, values, events)
        return RecipeOutcome(plan, result, engine, values)
    }

    /** Where Ollama listens, from the target's point of view (the tunnel's far end). */
    fun ollamaEndpoint(manifest: Manifest?): Pair<String, Int> {
        val bind = manifest?.settings?.get("settings.bind") ?: "127.0.0.1:11434"
        val host = bind.substringBeforeLast(':').let { if (it == "0.0.0.0" || it.isBlank()) "127.0.0.1" else it }
        return host to (bind.substringAfterLast(':').toIntOrNull() ?: 11434)
    }

    fun ollama(endpoint: Pair<String, Int>): OllamaClient =
        OllamaClient(TunnelSocketFactory({ session }, endpoint.first, endpoint.second))
}

class RecipeOutcome(val plan: List<PlannedStep>, val result: RunResult?, val engine: RecipeEngine, val values: Map<String, String>)
