package dev.dreamz.llmwizard.core.remote

import dev.dreamz.llmwizard.core.shell.Shell
import dev.dreamz.llmwizard.core.ssh.ExecResult
import dev.dreamz.llmwizard.core.ssh.SshSession
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How privileged scripts get root on this target (PLAN.md 5.3).
 * [Password] holds the sudo password for one run; call [wipe] when the run ends.
 */
sealed interface Privilege {
    data object Root : Privilege
    data object PasswordlessSudo : Privilege
    class SudoPassword(val password: CharArray) : Privilege {
        fun wipe() = password.fill('\u0000')
        override fun toString() = "SudoPassword(****)"
    }
}

/** What [Remote.detectPrivilege] found, before any password is involved. */
enum class SudoSituation { ROOT, PASSWORDLESS, NEEDS_PASSWORD, NO_SUDO }

/**
 * Runs scripts on a target the way SPEC.md rule 5 requires: under `/bin/sh`, from a script
 * (on stdin for read-only probes, from a file for anything that changes the machine), with
 * the fixed preamble. The command lines sent to the login shell are fixed strings and
 * quoted absolute paths only, so they work under bash, dash, zsh and fish alike.
 */
class Remote(
    val session: SshSession,
    /** Overrides `$HOME/.local/state/llmwizard` (tests). */
    private val stateDirOverride: String? = null,
) {
    private var cachedHome: String? = null

    /** Runs a read-only [body] through `/bin/sh -s`. Nothing is written to the target. */
    suspend fun probe(body: String, timeout: Duration = 60.seconds): ExecResult =
        session.exec("/bin/sh -s", Shell.script(body).toByteArray(), timeout)

    suspend fun home(): String = cachedHome ?: run {
        val r = probe("printf '%s' \"\$HOME\"", 20.seconds)
        check(r.ok && r.out.startsWith("/")) { "Could not read the home directory on ${session.target.name}" }
        Shell.path(r.out).also { cachedHome = it }
    }

    suspend fun stateDir(): String = stateDirOverride ?: (home() + "/.local/state/llmwizard")

    /** Writes [content] to [path] (created 0600 under umask 077). Content travels on stdin. */
    suspend fun writeFile(path: String, content: ByteArray, timeout: Duration = 60.seconds) {
        Shell.path(path)
        // A fixed command line; the path is the only argument, validated and quoted, and the
        // content never touches the command line.
        val r = session.exec(
            "/bin/sh -c 'umask 077 && mkdir -p \"\${1%/*}\" && cat > \"\$1\"' sh " + Shell.quote(path),
            content, timeout,
        )
        check(r.ok) { "Could not write $path: ${r.err.take(300)}" }
    }

    suspend fun readFile(path: String, timeout: Duration = 30.seconds): String? {
        val r = probe("if [ -f ${Shell.quote(Shell.path(path))} ]; then cat ${Shell.quote(path)}; else exit 44; fi", timeout)
        return if (r.exitCode == 44) null else r.also { check(it.ok) { "Could not read $path" } }.out
    }

    /**
     * Runs the script file at [path], as root when [privilege] is set. The sudo password, if
     * any, is the only thing on stdin, and `-k` makes sudo read it every time so it can never
     * be left over for the script to read.
     */
    suspend fun runFile(path: String, privilege: Privilege?, timeout: Duration): ExecResult {
        val p = Shell.quote(Shell.path(path))
        return when (privilege) {
            null, Privilege.Root -> session.exec("/bin/sh $p", null, timeout)
            Privilege.PasswordlessSudo -> session.exec("sudo -n /bin/sh $p", null, timeout)
            is Privilege.SudoPassword -> {
                val stdin = passwordLine(privilege.password)
                try {
                    session.exec("sudo -k -S -p '' /bin/sh $p", stdin, timeout)
                } finally {
                    stdin.fill(0)
                }
            }
        }
    }

    suspend fun detectPrivilege(): SudoSituation {
        val r = probe(
            """
            if [ "$(id -u)" = 0 ]; then echo root; exit 0; fi
            if ! command -v sudo >/dev/null 2>&1; then echo nosudo; exit 0; fi
            if sudo -n true 2>/dev/null; then echo nopasswd; exit 0; fi
            echo password
            """.trimIndent(), 30.seconds,
        )
        return when (r.out.trim()) {
            "root" -> SudoSituation.ROOT
            "nopasswd" -> SudoSituation.PASSWORDLESS
            "password" -> SudoSituation.NEEDS_PASSWORD
            else -> SudoSituation.NO_SUDO
        }
    }

    /**
     * Checks a sudo password once, up front, by exit status alone (prompts are translated on
     * non-English systems, so none are parsed). False means wrong password or no sudo rights.
     */
    suspend fun checkSudoPassword(password: CharArray): Boolean {
        val stdin = passwordLine(password)
        return try {
            session.exec("sudo -k -S -p '' true", stdin, 1.minutes).ok
        } finally {
            stdin.fill(0)
        }
    }

    private fun passwordLine(password: CharArray): ByteArray {
        val buf = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(password))
        val out = ByteArray(buf.remaining() + 1)
        buf.get(out, 0, buf.remaining())
        out[out.size - 1] = '\n'.code.toByte()
        if (buf.hasArray()) buf.array().fill(0)
        return out
    }
}
