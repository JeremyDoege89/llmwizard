package dev.dreamz.llmwizard.core.training

import dev.dreamz.llmwizard.core.agents.AgentCommand
import dev.dreamz.llmwizard.core.remote.Remote
import dev.dreamz.llmwizard.core.shell.Shell
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A job as the phone sees it: its config, what the job last reported, and whether it is alive. */
@Serializable
data class JobState(val job: TrainingJob, val progress: TrainingProgress?, val alive: Boolean) {
    val status: TrainingStatus get() = when {
        progress?.stage == TrainingStage.DONE -> TrainingStatus.COMPLETED
        progress?.stage == TrainingStage.CANCELLED -> TrainingStatus.CANCELLED
        alive -> TrainingStatus.RUNNING
        progress == null -> TrainingStatus.PENDING
        else -> TrainingStatus.FAILED
    }

    /** Plain words for the job card. */
    val summary: String get() = when (status) {
        TrainingStatus.PENDING -> "Not started."
        TrainingStatus.RUNNING -> progress?.let { p ->
            when (p.stage) {
                TrainingStage.TRAINING -> "Training: step ${p.currentStep} of ${p.totalSteps}" +
                    (p.loss?.let { ", loss %.3f".format(it) } ?: "") +
                    (p.estimatedRemainingSeconds?.let { ", about ${TrainingEstimator.duration(it)} left" } ?: "")
                else -> p.message ?: p.stage.name.lowercase()
            }
        } ?: "Starting."
        TrainingStatus.COMPLETED -> progress?.message ?: "Done."
        TrainingStatus.CANCELLED -> progress?.message ?: "Stopped."
        TrainingStatus.FAILED -> progress?.error?.let { "Failed: $it" }
            ?: "Stopped unexpectedly (was the server restarted?). Resume continues from the last checkpoint."
    }
}

/**
 * Datasets and fine-tuning jobs on the server (BUILD_PLAN E3.3–E3.5, E3.8), under
 * `~/.local/share/llmwizard/training`. A job runs detached (`setsid nohup`) so the phone can
 * leave; its state is read back from `progress.json` and the pid file, never held on the phone.
 */
class Training(
    private val remote: Remote,
    /** Overrides `~/.local/share/llmwizard` (tests). */
    private val dataDirOverride: String? = null,
    private val now: () -> String = { java.time.Instant.now().toString() },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private suspend fun data() = dataDirOverride ?: AgentCommand.dataDir(remote.home())
    private suspend fun root() = data() + "/training"
    private suspend fun jobDir(id: String) = root() + "/jobs/" + Shell.identifier(id)

    /** Whether `training-stack-install` has run here. */
    suspend fun stackInstalled(): Boolean =
        remote.probe("[ -x ${Shell.quote(root() + "/venv/bin/python")} ] && [ -f ${Shell.quote(root() + "/llama.cpp/convert_hf_to_gguf.py")} ] && [ -x ${Shell.quote(root() + "/llama.cpp/bin/llama-quantize")} ]", 30.seconds).ok

    // ---- datasets ----

    suspend fun uploadDataset(name: String, checked: DatasetValidator.Result, id: String = newId("ds")): TrainingDataset {
        require(checked.ok) { checked.problems.joinToString(" ").ifEmpty { "The dataset has no samples." } }
        require(name.isNotBlank() && name.length <= 80) { "Give the dataset a name of up to 80 characters." }
        val dir = root() + "/datasets/" + Shell.identifier(id)
        val bytes = checked.normalized.toByteArray()
        remote.writeFile("$dir/data.jsonl", bytes, 10.minutes)
        val meta = TrainingDataset(id, name.trim(), checked.format!!, "$dir/data.jsonl", checked.samples, bytes.size.toLong(), checked.approxTokens, now(), true)
        remote.writeFile("$dir/meta.json", json.encodeToString(TrainingDataset.serializer(), meta).toByteArray())
        return meta
    }

    suspend fun datasets(): List<TrainingDataset> = readAll(root() + "/datasets/*/meta.json", TrainingDataset.serializer()).sortedByDescending { it.uploadedAt }

    suspend fun deleteDataset(id: String) {
        remote.probe("rm -rf ${Shell.quote(root() + "/datasets/" + Shell.identifier(id))}", 30.seconds)
    }

    /** The first lines of an uploaded dataset, for the preview. */
    suspend fun datasetHead(id: String, lines: Int = 3): String =
        remote.probe("head -n ${lines.coerceIn(1, 50)} ${Shell.quote(root() + "/datasets/" + Shell.identifier(id) + "/data.jsonl")}", 30.seconds).out

    // ---- jobs ----

    fun script(): String = Training.script()
    fun configJson(job: TrainingJob): String = Training.configJson(job)

    /** Writes the job folder (config and script) without starting it. */
    suspend fun prepare(job: TrainingJob): TrainingJob {
        val problems = job.problems()
        require(problems.isEmpty()) { problems.joinToString(" ") }
        val j = if (job.createdAt.isEmpty()) job.copy(createdAt = now()) else job
        val dir = jobDir(j.id)
        remote.writeFile("$dir/config.json", configJson(j).toByteArray())
        remote.writeFile("$dir/train.py", script().toByteArray())
        return j
    }

    /** Starts (or resumes) a prepared job in the background. Running it twice is refused. */
    suspend fun start(id: String) {
        val dir = jobDir(id)
        val r = remote.probe(
            """
            P_dir=${Shell.quote(dir)}
            P_py=${Shell.quote(root() + "/venv/bin/python")}
            P_data=${Shell.quote(data())}
            [ -x "${'$'}P_py" ] || { echo "The fine-tuning tools are not installed."; exit 3; }
            [ -f "${'$'}P_dir/config.json" ] || { echo "No such job."; exit 4; }
            if [ -f "${'$'}P_dir/pid" ] && kill -0 "${'$'}(cat "${'$'}P_dir/pid")" 2>/dev/null; then echo "Already running."; exit 5; fi
            rm -f "${'$'}P_dir/cancel"
            cd "${'$'}P_dir"
            LLMWIZARD_DATA="${'$'}P_data" HF_HUB_DISABLE_TELEMETRY=1 PYTHONUNBUFFERED=1 setsid nohup nice -n 10 "${'$'}P_py" train.py >> log.txt 2>&1 < /dev/null &
            echo "${'$'}!" > pid
            echo started
            """.trimIndent(), 30.seconds,
        )
        check(r.ok) { r.out.trim().ifEmpty { r.err.trim().take(300) } }
    }

    /** Asks the job to stop after the current step; it saves a checkpoint first, so it can be resumed. */
    suspend fun cancel(id: String) {
        remote.probe("touch ${Shell.quote(jobDir(id) + "/cancel")}", 30.seconds)
    }

    /** Stops the job at once (no checkpoint for the current step). */
    suspend fun kill(id: String) {
        val p = Shell.quote(jobDir(id) + "/pid")
        remote.probe("[ -f $p ] && kill \"$(cat $p)\" 2>/dev/null; rm -f $p; true", 30.seconds)
    }

    suspend fun state(id: String): JobState? {
        val dir = jobDir(id)
        val r = remote.probe(
            """
            P_dir=${Shell.quote(dir)}
            [ -f "${'$'}P_dir/config.json" ] || exit 4
            cat "${'$'}P_dir/config.json"; printf '\n\036\n'
            cat "${'$'}P_dir/progress.json" 2>/dev/null; printf '\n\036\n'
            if [ -f "${'$'}P_dir/pid" ] && kill -0 "${'$'}(cat "${'$'}P_dir/pid")" 2>/dev/null; then echo alive; else echo dead; fi
            """.trimIndent(), 30.seconds,
        )
        if (r.exitCode == 4) return null
        val parts = r.out.split("\u001e").map { it.trim() }
        val job = runCatching { json.decodeFromString(TrainingJob.serializer(), parts[0]) }.getOrNull() ?: return null
        return JobState(job, ProgressParser.parse(parts.getOrNull(1).orEmpty()), parts.getOrNull(2) == "alive")
    }

    suspend fun jobs(): List<JobState> {
        val r = remote.probe("for d in ${Shell.quote(root() + "/jobs")}/*/; do [ -f \"\$d/config.json\" ] && basename \"\$d\"; done; true", 30.seconds)
        return r.out.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { runCatching { state(it) }.getOrNull() }
            .sortedByDescending { it.job.createdAt }
    }

    suspend fun log(id: String, maxBytes: Int = 32_000): String =
        remote.probe("tail -c ${maxBytes.coerceIn(1, 1_000_000)} ${Shell.quote(jobDir(id) + "/log.txt")} 2>/dev/null; true", 30.seconds).out

    /** Deletes a job's folder (checkpoints, adapter, log). A model already registered with Ollama stays. */
    suspend fun delete(id: String) {
        kill(id)
        remote.probe("rm -rf ${Shell.quote(jobDir(id))}", 60.seconds)
    }

    private suspend fun <T> readAll(glob: String, s: kotlinx.serialization.KSerializer<T>): List<T> {
        // The glob is built from a quoted directory and a fixed pattern.
        val dirPart = glob.substringBeforeLast("/*/")
        val file = glob.substringAfterLast("/")
        val r = remote.probe("for f in ${Shell.quote(dirPart)}/*/${Shell.identifier(file)}; do [ -f \"\$f\" ] && { cat \"\$f\"; printf '\\n\\036\\n'; }; done; true", 30.seconds)
        return r.out.split("\u001e").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { runCatching { json.decodeFromString(s, it) }.getOrNull() }
    }

    companion object {
        private val pretty = Json { encodeDefaults = true; prettyPrint = true }

        /** The script every job runs; shown in the preview (SPEC.md rule 1). It is the same for every job. */
        fun script(): String = requireNotNull(Training::class.java.getResourceAsStream("/llmwizard/training/train.py")) { "train.py missing" }
            .readBytes().toString(Charsets.UTF_8)

        fun configJson(job: TrainingJob): String = pretty.encodeToString(TrainingJob.serializer(), job)

        /** What the preview shows before a job starts: its config.json, then the script that reads it. */
        fun previewText(job: TrainingJob): String = "config.json\n" + configJson(job) + "\n\ntrain.py\n" + script()

        fun newId(prefix: String): String = prefix + "-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) +
            "-" + (1..4).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
    }
}

/** Reads `progress.json` leniently: a half-written or missing file is "no progress yet", never an error. */
object ProgressParser {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    fun parse(text: String): TrainingProgress? = text.trim().takeIf { it.startsWith("{") }?.let { runCatching { json.decodeFromString(TrainingProgress.serializer(), it) }.getOrNull() }
}
