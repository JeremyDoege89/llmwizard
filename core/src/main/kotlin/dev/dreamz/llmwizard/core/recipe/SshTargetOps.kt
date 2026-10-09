package dev.dreamz.llmwizard.core.recipe

import dev.dreamz.llmwizard.core.remote.Jobs
import dev.dreamz.llmwizard.core.remote.Privilege
import dev.dreamz.llmwizard.core.remote.Remote
import dev.dreamz.llmwizard.core.remote.StepState
import dev.dreamz.llmwizard.core.shell.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** Where a detached step is running, so a later run can pick it up instead of starting over. */
@Serializable
data class JobRef(val jobId: String, val step: Int, val startedAt: String)

/** Phone-side memory of running jobs (rule 4). Room in the app, a file in the CLI. */
interface ResumeStore {
    fun get(target: String, recipeId: String, stepId: String): JobRef?
    fun put(target: String, recipeId: String, stepId: String, ref: JobRef)
    fun clear(target: String, recipeId: String, stepId: String)
}

class MemoryResumeStore : ResumeStore {
    private val map = mutableMapOf<String, JobRef>()
    override fun get(target: String, recipeId: String, stepId: String) = map["$target/$recipeId/$stepId"]
    override fun put(target: String, recipeId: String, stepId: String, ref: JobRef) { map["$target/$recipeId/$stepId"] = ref }
    override fun clear(target: String, recipeId: String, stepId: String) { map.remove("$target/$recipeId/$stepId") }
}

/** One line in the per-server transcript (PLAN.md 5.2). Never contains anything sent on stdin. */
@Serializable
data class TranscriptEntry(val at: String, val target: String, val kind: String, val script: String, val exitCode: Int?, val outputTail: String)

fun interface Transcript {
    fun record(entry: TranscriptEntry)
}

/**
 * [TargetOps] over SSH: steps become files in a job directory; detached ones are started
 * with setsid/nohup and followed by byte offset, and survive the phone disconnecting.
 */
class SshTargetOps(
    private val remote: Remote,
    private val privilege: Privilege?,
    private val resume: ResumeStore,
    private val transcript: Transcript = Transcript { },
    private val pollMs: Long = 1000,
) : TargetOps {
    private val jobs = Jobs(remote)
    private val targetName = remote.session.target.name
    private var jobId: String? = null
    private var cachedBuiltins: Map<String, String>? = null

    private suspend fun job(): String = jobId ?: jobs.create("run").also { jobId = it }

    override suspend fun builtins(): Map<String, String> = cachedBuiltins ?: run {
        val state = remote.stateDir()
        val user = remote.probe("id -un", 20.seconds).out.trim()
        mapOf(
            "J" to Shell.path(jobs.jobDir(job())),
            "STATE" to Shell.path(state),
            "CACHE" to Shell.path("$state/cache"),
            "HOME" to remote.home(),
            "USER" to Shell.identifier(user),
        ).also { cachedBuiltins = it }
    }

    override suspend fun probe(body: String, purpose: String): Int {
        val r = remote.probe(body, 120.seconds)
        log("probe: $purpose", body, r.exitCode, r.out + r.err)
        return r.exitCode
    }

    override suspend fun probeOutput(body: String, purpose: String): String {
        val r = remote.probe(body, 120.seconds)
        log("probe: $purpose", body, r.exitCode, r.out + r.err)
        return if (r.ok) r.out else ""
    }

    override suspend fun run(recipeId: String, step: PlannedStep, onLog: suspend (String) -> Unit): Int {
        val priv = if (step.spec.sudo) privilege ?: error("Step \"${step.spec.title}\" needs root, and no sudo is available") else null
        val code = if (step.spec.detached) runDetached(recipeId, step, priv, onLog) else {
            val id = job()
            jobs.writeStep(id, step.index, step.script)
            val o = jobs.runAttached(id, step.index, priv, step.spec.timeoutSec.seconds)
            if (o.log.isNotEmpty()) onLog(o.log)
            o.exitCode.also { log("step ${step.spec.id}", step.script, it, o.log) }
        }
        return code
    }

    private suspend fun runDetached(recipeId: String, step: PlannedStep, priv: Privilege?, onLog: suspend (String) -> Unit): Int {
        val existing = io { resume.get(targetName, recipeId, step.spec.id) }
        var ref: JobRef? = null
        if (existing != null) {
            val st = jobs.status(existing.jobId, existing.step)
            if (st.state == StepState.RUNNING) {
                onLog("[picking up the step already running since ${existing.startedAt}]\n")
                ref = existing
            }
        }
        if (ref == null) {
            val id = job()
            jobs.writeStep(id, step.index, step.script)
            jobs.startDetached(id, step.index, priv)
            ref = JobRef(id, step.index, Instant.now().toString())
            io { resume.put(targetName, recipeId, step.spec.id, ref) }
        }
        var offset = 0L
        val tailOut = StringBuilder()
        while (true) {
            val chunk = jobs.tail(ref.jobId, ref.step, offset)
            if (chunk.bytes.isNotEmpty()) {
                offset = chunk.nextOffset
                onLog(chunk.text)
                tailOut.append(chunk.text); if (tailOut.length > 8000) tailOut.delete(0, tailOut.length - 4000)
            }
            val st = jobs.status(ref.jobId, ref.step)
            when (st.state) {
                StepState.FINISHED -> {
                    val last = jobs.tail(ref.jobId, ref.step, offset)
                    if (last.bytes.isNotEmpty()) { onLog(last.text); tailOut.append(last.text) }
                    io { resume.clear(targetName, recipeId, step.spec.id) }
                    log("step ${step.spec.id} (detached)", step.script, st.exitCode, tailOut.toString())
                    return st.exitCode ?: -1
                }
                StepState.LOST, StepState.MISSING, StepState.NOT_STARTED -> {
                    io { resume.clear(targetName, recipeId, step.spec.id) }
                    onLog("[the step stopped without finishing; the machine may have restarted]\n")
                    return 255
                }
                StepState.RUNNING -> delay(pollMs)
            }
        }
    }

    override suspend fun collectFinished(recipeId: String, step: PlannedStep): Int? {
        val ref = io { resume.get(targetName, recipeId, step.spec.id) } ?: return null
        val st = jobs.status(ref.jobId, ref.step)
        if (st.state == StepState.RUNNING) return null
        io { resume.clear(targetName, recipeId, step.spec.id) }
        return if (st.state == StepState.FINISHED) st.exitCode else null
    }

    override suspend fun runUndo(recipeId: String, step: PlannedStep, script: String, onLog: suspend (String) -> Unit): Int {
        val priv = if (step.spec.sudo) privilege else null
        val id = job()
        val n = 50 + step.index
        jobs.writeStep(id, n, script)
        val o = jobs.runAttached(id, n, priv, step.spec.timeoutSec.seconds)
        if (o.log.isNotEmpty()) onLog(o.log)
        log("undo ${step.spec.id}", script, o.exitCode, o.log)
        return o.exitCode
    }

    override suspend fun readManifest(): Manifest? =
        remote.readFile(remote.stateDir() + "/installed.json")?.let { runCatching { Manifest.decode(it) }.getOrNull() }

    override suspend fun writeManifest(manifest: Manifest) =
        remote.writeFile(remote.stateDir() + "/installed.json", manifest.encode().toByteArray())

    /** Stores and the transcript are blocking (Room on Android); keep them off the caller's thread. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun log(kind: String, script: String, exit: Int?, output: String) = io {
        transcript.record(TranscriptEntry(Instant.now().toString(), targetName, kind, script, exit, output.takeLast(2000)))
    }
}
