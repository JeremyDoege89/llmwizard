package dev.dreamz.llmwizard.core.shell

/**
 * The one place values enter a shell script (SPEC.md rule 2).
 *
 * Everything interpolated into a script that runs on a user's machine goes through [quote],
 * after the matching validator for its kind has accepted it. A model tag from the catalog or
 * a path a user typed ends up inside a root shell, so this file is the injection boundary:
 * nothing else in the codebase builds script text from values.
 */
object Shell {

    /** Wraps [value] in single quotes, escaping embedded single quotes the POSIX way. */
    fun quote(value: String): String {
        require('\u0000' !in value) { "NUL byte in shell value" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /** An Ollama model tag: `name`, `name:tag`, `namespace/name:tag`. */
    private val MODEL_TAG = Regex("^[a-z0-9][a-z0-9._-]{0,63}(/[a-z0-9][a-z0-9._-]{0,63})?(:[A-Za-z0-9][A-Za-z0-9._-]{0,127})?$")

    /** Absolute path made of a conservative character set, with no `..` segments. */
    private val PATH = Regex("^/[A-Za-z0-9._/+-]{0,1023}$")

    /** host:port where host is an IPv4 address or a plain hostname. */
    private val BIND = Regex("^([0-9]{1,3}(\\.[0-9]{1,3}){3}|[A-Za-z0-9.-]{1,253}):[0-9]{1,5}$")

    private val IDENT = Regex("^[A-Za-z0-9_.-]{1,64}$")

    fun modelTag(value: String): String = value.also {
        require(MODEL_TAG.matches(it)) { "Not a valid model tag: ${printable(it)}" }
    }

    fun path(value: String): String = value.also {
        require(PATH.matches(it) && it.split('/').none { seg -> seg == ".." }) {
            "Not an allowed path: ${printable(it)}"
        }
    }

    fun bind(value: String): String = value.also {
        require(BIND.matches(it)) { "Not a host:port: ${printable(it)}" }
        val port = it.substringAfterLast(':').toInt()
        require(port in 1..65535) { "Port out of range: $port" }
        val host = it.substringBeforeLast(':')
        if (host.all { c -> c.isDigit() || c == '.' }) {
            require(host.split('.').all { o -> o.toInt() in 0..255 }) { "Not an IPv4 address: $host" }
        }
    }

    fun identifier(value: String): String = value.also {
        require(IDENT.matches(it)) { "Not an allowed identifier: ${printable(it)}" }
    }

    fun integer(value: String, range: LongRange = Long.MIN_VALUE..Long.MAX_VALUE): String = value.also {
        val n = requireNotNull(it.toLongOrNull()) { "Not an integer: ${printable(it)}" }
        require(n in range) { "Out of range $range: $n" }
    }

    /** Free text (a public key line, a comment). Single-line, printable only, then quoted. */
    fun text(value: String, maxLength: Int = 4096): String = value.also {
        require(it.length <= maxLength) { "Value too long (${it.length} > $maxLength)" }
        require(it.none { c -> c == '\n' || c == '\r' || c.code < 0x20 && c != '\t' }) {
            "Control characters are not allowed"
        }
    }

    private fun printable(s: String) = s.take(80).map { if (it.code < 0x20) '?' else it }.joinToString("")

    /**
     * The first lines of every script we run (SPEC.md rule 5): strict mode, a fixed PATH that
     * includes the sbin directories (Debian's non-interactive PATH lacks them), untranslated
     * output for the parsers, and no apt prompts.
     */
    const val PREAMBLE = """set -eu
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
LC_ALL=C
LANG=C
DEBIAN_FRONTEND=noninteractive
export PATH LC_ALL LANG DEBIAN_FRONTEND
"""

    /** A complete script: preamble, then [body]. */
    fun script(body: String): String = PREAMBLE + body.trimEnd() + "\n"
}
