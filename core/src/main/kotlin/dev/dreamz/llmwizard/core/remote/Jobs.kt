package dev.dreamz.llmwizard.core.remote

import dev.dreamz.llmwizard.core.shell.Shell
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Remote jobs (PLAN.md 5.2). Each job is a directory on the target:
 *
 *     ~/.local/state/llmwizard/jobs/<jobId>/
 *         step-NN.sh     the exact script shown in the preview
 *         run-NN.sh      a fixed wrapper: records the pid, runs the step, writes the exit code
 *         step-NN.log    stdout and stderr
 *         step-NN.exit   the exit code, written only when the step ends
 *         step-NN.pid    the wrapper's pid, which is also its process group
 *
 * Attached steps run the wrapper inside the SSH exec. Detached steps start it with
 * `setsid nohup … &`, so it survives the connection dropping and the app being killed; the
 * phone reads new output by byte offset and watches for the exit file.
 */
class Jobs(private val remote: Remote) {

    suspend fun jobsDir(): String = remote.stateDir() + "/jobs"

    suspend fun jobDir(jobId: String): String = jobsDir() + "/" + Shell.identifier(jobId)

    suspend fun create(kind: String): String {
        val id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" +
            Shell.identifier(kind) + "-" + Integer.toHexString(SecureRandom().nextInt(0x10000) or 0x10000).drop(1)
        val dir = jobDir(id)
        val r = remote.probe("umask 077\nmkdir -p ${Shell.quote(dir)}")
        check(r.ok) { "Could not create the job directory: ${r.err.take(300)}" }
        return id
    }

    private fun nn(step: Int) = step.toString().padStart(2, '0')

    /** Writes the step's script and its wrapper. [script] must already include the preamble. */
    suspend fun writeStep(jobId: String, step: Int, script: String) {
        val dir = jobDir(jobId)
        val n = nn(step)
        remote.writeFile("$dir/step-$n.sh", script.toByteArray())
        // The wrapper must not use `set -e`: it has to survive the step failing.
        val wrapper = """
            cd ${Shell.quote(dir)} || exit 1
            echo $$ > step-$n.pid
            /bin/sh step-$n.sh > step-$n.log 2>&1 < /dev/null
            code=$?
            echo "${'$'}code" > step-$n.exit.tmp
            mv -f step-$n.exit.tmp step-$n.exit
            exit "${'$'}code"
        """.trimIndent() + "\n"
        remote.writeFile("$dir/run-$n.sh", wrapper.toByteArray())
        val launch = "cd ${Shell.quote(dir)} || exit 1\nrm -f step-$n.exit step-$n.log\n" +
            "setsid nohup /bin/sh ${Shell.quote("$dir/run-$n.sh")} > /dev/null 2>&1 < /dev/null &\n"
        remote.writeFile("$dir/launch-$n.sh", launch.toByteArray())
    }

    /** Runs a step to completion inside this connection. Returns the exit code and the log. */
    suspend fun runAttached(jobId: String, step: Int, privilege: Privilege?, timeout: Duration): StepOutcome {
        val dir = jobDir(jobId)
        val n = nn(step)
        val r = remote.runFile("$dir/run-$n.sh", privilege, timeout)
        val log = tail(jobId, step, 0)
        val exit = status(jobId, step).exitCode
        // A sudo failure (wrong password) never reaches the wrapper, so fall back to sudo's own exit.
        return StepOutcome(exit ?: r.exitCode, log.text + if (exit == null) r.err else "")
    }

    /** Starts a step in the background on the target and returns at once. */
    suspend fun startDetached(jobId: String, step: Int, privilege: Privilege?) {
        val dir = jobDir(jobId)
        val r = remote.runFile("$dir/launch-${nn(step)}.sh", privilege, 60.seconds)
        check(r.ok) { "Could not start step $step: ${r.err.take(300)}" }
    }

    suspend fun status(jobId: String, step: Int): StepStatus {
        val dir = jobDir(jobId)
        val n = nn(step)
        val r = remote.probe(
            """
            cd ${Shell.quote(dir)} 2>/dev/null || { echo missing; exit 0; }
            if [ -f step-$n.exit ]; then echo "exit $(cat step-$n.exit)"; exit 0; fi
            if [ -f step-$n.pid ] && [ -d "/proc/$(cat step-$n.pid)" ]; then echo running; exit 0; fi
            if [ -f step-$n.pid ]; then echo lost; else echo notstarted; fi
            """.trimIndent(), 30.seconds,
        )
        val line = r.out.trim()
        return when {
            line.startsWith("exit ") -> StepStatus(StepState.FINISHED, line.removePrefix("exit ").trim().toIntOrNull() ?: -1)
            line == "running" -> StepStatus(StepState.RUNNING, null)
            line == "lost" -> StepStatus(StepState.LOST, null)
            line == "missing" -> StepStatus(StepState.MISSING, null)
            else -> StepStatus(StepState.NOT_STARTED, null)
        }
    }

    /** New log bytes from [offset] on. Pass the returned [LogChunk.nextOffset] next time. */
    suspend fun tail(jobId: String, step: Int, offset: Long, maxBytes: Long = 256 * 1024): LogChunk {
        val file = jobDir(jobId) + "/step-${nn(step)}.log"
        val r = remote.probe(
            "[ -f ${Shell.quote(file)} ] || exit 0\n" +
                "tail -c +${offset + 1} ${Shell.quote(file)} | head -c $maxBytes", 60.seconds,
        )
        return LogChunk(r.stdout, offset + r.stdout.size)
    }

    /** Stops a running step and its children, then marks it finished with 143. */
    suspend fun cancel(jobId: String, step: Int, privilege: Privilege?) {
        val dir = jobDir(jobId)
        val n = nn(step)
        val script = Shell.script(
            """
            cd ${Shell.quote(dir)}
            [ -f step-$n.exit ] && exit 0
            pid=$(cat step-$n.pid 2>/dev/null || true)
            if [ -n "${'$'}pid" ]; then
                kill -TERM -- "-${'$'}pid" 2>/dev/null || true
                sleep 2
                kill -KILL -- "-${'$'}pid" 2>/dev/null || true
            fi
            [ -f step-$n.exit ] || echo 143 > step-$n.exit
            """.trimIndent(),
        )
        remote.writeFile("$dir/cancel-$n.sh", script.toByteArray())
        remote.runFile("$dir/cancel-$n.sh", privilege, 60.seconds)
    }

    /** Removes finished job directories older than [days]. Running jobs are kept. */
    suspend fun prune(days: Int = 14) {
        val root = jobsDir()
        remote.probe(
            """
            [ -d ${Shell.quote(root)} ] || exit 0
            find ${Shell.quote(root)} -mindepth 1 -maxdepth 1 -type d -mtime +${Shell.integer(days.toString(), 0L..3650L)} | while read -r d; do
                busy=no
                for p in "${'$'}d"/step-*.pid; do
                    [ -f "${'$'}p" ] || continue
                    e="${'$'}{p%.pid}.exit"
                    [ -f "${'$'}e" ] || { [ -d "/proc/$(cat "${'$'}p")" ] && busy=yes; }
                done
                [ "${'$'}busy" = no ] && rm -rf "${'$'}d"
            done
            exit 0
            """.trimIndent(),
        )
    }
}

enum class StepState { NOT_STARTED, RUNNING, FINISHED, LOST, MISSING }

@Serializable
data class StepStatus(val state: StepState, val exitCode: Int?)

class LogChunk(val bytes: ByteArray, val nextOffset: Long) {
    val text: String get() = bytes.toString(Charsets.UTF_8)
}

data class StepOutcome(val exitCode: Int, val log: String) {
    val ok get() = exitCode == 0
}
