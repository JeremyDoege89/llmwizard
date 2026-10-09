package dev.dreamz.llmwizard.core.recipe

import dev.dreamz.llmwizard.core.shell.Shell
import java.time.Instant

/** A step as it will run: the exact script text the preview shows. */
data class PlannedStep(
    val index: Int,
    val spec: StepSpec,
    /** The complete file written to the target: preamble, parameter block, the step's commands. */
    val script: String,
    /** True when skipIf said the work is already done. */
    val alreadyDone: Boolean,
    /** Manifest entries this step will record if it runs. */
    val creates: List<Created>,
)

data class CheckResult(val check: Check, val ok: Boolean)

sealed interface EngineEvent {
    data class StepSkipped(val step: PlannedStep) : EngineEvent
    data class StepStarted(val step: PlannedStep) : EngineEvent
    data class Log(val step: PlannedStep, val text: String) : EngineEvent
    data class StepFinished(val step: PlannedStep, val exitCode: Int) : EngineEvent
    data class VerifyFailed(val step: PlannedStep) : EngineEvent
    data class UndoStarted(val step: PlannedStep) : EngineEvent
    data class UndoFinished(val step: PlannedStep, val exitCode: Int) : EngineEvent
}

data class RunResult(
    val ok: Boolean,
    val failedStep: PlannedStep?,
    val exitCode: Int?,
    /** Steps that actually ran to success, in order (what an undo would reverse). */
    val ran: List<PlannedStep>,
    val message: String,
) {
    val changed: Boolean get() = ran.isNotEmpty()
}

/**
 * What the engine needs from a target. The real implementation runs over SSH
 * ([SshTargetOps]); tests use a fake.
 */
interface TargetOps {
    /** Values every recipe can use: J (this run's job directory), STATE, CACHE, HOME, USER. */
    suspend fun builtins(): Map<String, String>
    /** Runs a read-only probe; returns its exit code. */
    suspend fun probe(body: String, purpose: String): Int
    /** Runs a probe and returns its standard output (facts). */
    suspend fun probeOutput(body: String, purpose: String): String
    /** Runs a planned step to completion (resuming a detached one if it is still going). */
    suspend fun run(recipeId: String, step: PlannedStep, onLog: suspend (String) -> Unit): Int
    suspend fun runUndo(recipeId: String, step: PlannedStep, script: String, onLog: suspend (String) -> Unit): Int
    /**
     * A detached step this phone started earlier that finished while nobody was watching (the app
     * was killed). Returns its exit code and forgets it; null when there is no such step.
     */
    suspend fun collectFinished(recipeId: String, step: PlannedStep): Int? = null
    suspend fun readManifest(): Manifest?
    suspend fun writeManifest(manifest: Manifest)
}

/**
 * Preflight, plan, preview, execute, verify, record, undo (PLAN.md 5.6). Idempotent by
 * construction: `skipIf` drops finished work, so running a recipe on a half-done machine
 * finishes it, and on a finished machine does nothing.
 */
class RecipeEngine(private val ops: TargetOps, private val now: () -> String = { Instant.now().toString() }) {

    /** Validated parameter values: defaults, then [given], then the target's builtins. */
    suspend fun resolve(recipe: Recipe, given: Map<String, String>): Map<String, String> {
        val unknown = given.keys - recipe.params.keys
        require(unknown.isEmpty()) { "Unknown parameter(s) for ${recipe.id}: ${unknown.joinToString()}" }
        val values = linkedMapOf<String, String>()
        for ((name, spec) in recipe.params) {
            values[name] = Params.validate(name, spec, given[name] ?: spec.default ?: "")
        }
        for ((k, v) in ops.builtins()) {
            require(k !in values) { "Recipe parameter $k shadows a builtin" }
            values[k] = v
        }
        return values
    }

    /** The `P_name='value'` block placed at the top of every script. The only place values enter scripts. */
    fun paramBlock(values: Map<String, String>): String =
        values.entries.joinToString("") { (k, v) -> "P_${Shell.identifier(k).replace('.', '_').replace('-', '_')}=${Shell.quote(v)}\n" }

    suspend fun preflight(recipe: Recipe, values: Map<String, String>): List<CheckResult> =
        recipe.preflight.map { c -> CheckResult(c, ops.probe(paramBlock(values) + c.run, "preflight ${c.id}") == 0) }

    suspend fun plan(recipe: Recipe, values: Map<String, String>, manifest: Manifest?): List<PlannedStep> {
        val block = paramBlock(values)
        return recipe.steps.withIndex()
            .filter { (_, s) -> applies(s, values, manifest) }
            .map { (i, s) ->
                val done = s.skipIf?.let { ops.probe(block + it, "skipIf ${s.id}") == 0 } ?: false
                PlannedStep(
                    index = i + 1,
                    spec = s,
                    script = Shell.script(block + s.run),
                    alreadyDone = done,
                    creates = s.creates.map { Created(it.kind, substitute(it.name, values)) },
                )
            }
    }

    suspend fun execute(recipe: Recipe, plan: List<PlannedStep>, values: Map<String, String>, events: suspend (EngineEvent) -> Unit = {}): RunResult {
        val block = paramBlock(values)
        val ran = mutableListOf<PlannedStep>()
        var manifest = ops.readManifest() ?: Manifest()
        for (step in plan) {
            // Re-check right before running: an earlier step in this run may have done this
            // one's work, or undone it (new settings make a restart necessary again).
            val done = when {
                step.spec.skipIf == null -> false
                ran.isEmpty() && step.alreadyDone -> true
                else -> ops.probe(block + step.spec.skipIf, "skipIf ${step.spec.id}") == 0
            }
            if (done) {
                // Our own background step finished while the app was gone: it did run, so record it.
                if (ops.collectFinished(recipe.id, step) == 0) {
                    ran += step
                    manifest = manifest.plus(step.creates.map { ManifestEntry(it.kind, it.name, recipe.id, now()) })
                    ops.writeManifest(manifest)
                }
                events(EngineEvent.StepSkipped(step)); continue
            }
            events(EngineEvent.StepStarted(step))
            val code = ops.run(recipe.id, step) { events(EngineEvent.Log(step, it)) }
            events(EngineEvent.StepFinished(step, code))
            if (code != 0) {
                manifest = record(manifest, recipe, "failed at ${step.spec.id} (exit $code)")
                ops.writeManifest(manifest)
                return RunResult(false, step, code, ran, "\"${step.spec.title}\" failed (exit code $code).")
            }
            if (step.spec.verify != null && ops.probe(block + step.spec.verify, "verify ${step.spec.id}") != 0) {
                events(EngineEvent.VerifyFailed(step))
                manifest = record(manifest, recipe, "verify failed at ${step.spec.id}")
                ops.writeManifest(manifest)
                return RunResult(false, step, 0, ran, "\"${step.spec.title}\" ran, but the check afterwards failed.")
            }
            ran += step
            manifest = manifest.plus(step.creates.map { ManifestEntry(it.kind, it.name, recipe.id, now()) })
            step.spec.facts?.let { f ->
                val facts = ops.probeOutput(block + f, "facts ${step.spec.id}").lines()
                    .mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0].trim() to it[1].trim() } }
                manifest = manifest.copy(settings = manifest.settings + facts)
            }
            removals(step, values).forEach { (k, n) -> manifest = manifest.minus(k, n) }
            ops.writeManifest(manifest)
        }
        manifest = record(manifest, recipe, if (ran.isEmpty()) "nothing to do" else "ok")
        ops.writeManifest(manifest)
        val msg = if (ran.isEmpty()) "Nothing to do: everything in \"${recipe.title}\" was already in place." else "\"${recipe.title}\" finished."
        return RunResult(true, null, 0, ran, msg)
    }

    /** Reverses [ran] in reverse order using each step's undo, and drops their manifest entries. */
    suspend fun undo(recipe: Recipe, ran: List<PlannedStep>, values: Map<String, String>, events: suspend (EngineEvent) -> Unit = {}): Boolean {
        val block = paramBlock(values)
        var manifest = ops.readManifest() ?: Manifest()
        var allOk = true
        for (step in ran.reversed()) {
            val undo = step.spec.undo ?: continue
            events(EngineEvent.UndoStarted(step))
            val code = ops.runUndo(recipe.id, step, Shell.script(block + undo)) { events(EngineEvent.Log(step, it)) }
            events(EngineEvent.UndoFinished(step, code))
            if (code == 0) step.creates.forEach { manifest = manifest.minus(it.kind, it.name) } else allOk = false
        }
        ops.writeManifest(record(manifest, recipe, if (allOk) "undone" else "undo incomplete"))
        return allOk
    }

    private fun record(m: Manifest, r: Recipe, result: String) =
        m.copy(history = (m.history + HistoryEntry(r.id, r.version, now(), result)).takeLast(50))

    private fun removals(step: PlannedStep, values: Map<String, String>): List<Pair<String, String>> =
        step.spec.removes.map { it.kind to substitute(it.name, values) }

    private fun applies(s: StepSpec, values: Map<String, String>, manifest: Manifest?): Boolean = s.requires.all { req ->
        val parts = req.split(':', limit = 3)
        when (parts[0]) {
            "created" -> manifest?.has(parts[1], substitute(parts[2], values)) == true
            "createdkind" -> manifest?.names(parts[1])?.isNotEmpty() == true
            "param" -> values[parts[1]].let { !it.isNullOrEmpty() && it != "no" }
            "noparam" -> values[parts[1]].let { it.isNullOrEmpty() || it == "no" }
            else -> error("Unknown requirement '$req' in step ${s.id}")
        }
    }

    /** `{{name}}` in manifest names only (never in scripts): raw, already-validated values. */
    private fun substitute(text: String, values: Map<String, String>): String =
        Regex("\\{\\{([A-Za-z0-9_]+)\\}\\}").replace(text) { m -> values[m.groupValues[1]] ?: error("Unknown placeholder ${m.value}") }
}
