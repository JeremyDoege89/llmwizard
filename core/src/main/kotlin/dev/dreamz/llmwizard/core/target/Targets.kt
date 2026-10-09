package dev.dreamz.llmwizard.core.target

import dev.dreamz.llmwizard.core.ssh.Auth
import dev.dreamz.llmwizard.core.ssh.Target
import java.io.File
import java.util.Properties

/**
 * A test machine from `targets.local.properties` (gitignored), for the CLI:
 *
 *     wiz-ubuntu.host=10.0.0.5
 *     wiz-ubuntu.port=22
 *     wiz-ubuntu.user=wiz
 *     wiz-ubuntu.key=/home/me/.config/llmwizard/test_ed25519
 *     wiz-ubuntu.sudoPassword=…   # throwaway VMs only, never a real machine
 */
data class TargetEntry(val target: Target, val keyFile: File?, val password: String?, val sudoPassword: String?) {
    fun auth(): Auth = when {
        keyFile != null -> Auth.PrivateKey(keyFile.readText())
        password != null -> Auth.Password(password.toCharArray())
        else -> error("Target ${target.name} has neither key nor password")
    }
}

object Targets {
    fun load(file: File): Map<String, TargetEntry> {
        if (!file.isFile) return emptyMap()
        val p = Properties().apply { file.inputStream().use { load(it) } }
        val names = p.stringPropertyNames().map { it.substringBefore('.') }.toSortedSet()
        return names.associateWith { n ->
            TargetEntry(
                Target(host = p.getProperty("$n.host") ?: error("$n.host missing"),
                    port = p.getProperty("$n.port")?.toInt() ?: 22,
                    user = p.getProperty("$n.user") ?: error("$n.user missing"),
                    name = n),
                p.getProperty("$n.key")?.let(::File),
                p.getProperty("$n.password"),
                p.getProperty("$n.sudoPassword"),
            )
        }
    }

    /** "name" from the file, or "user@host[:port]" ad hoc. */
    fun resolve(spec: String, file: File): TargetEntry {
        load(file)[spec]?.let { return it }
        val m = Regex("^([^@]+)@([^:]+)(?::(\\d+))?$").find(spec)
            ?: error("Unknown target '$spec'. Add it to ${file.name} or use user@host[:port].")
        val key = System.getenv("LLMWIZARD_KEY")?.let(::File)
        return TargetEntry(Target(m.groupValues[2], m.groupValues[3].ifEmpty { "22" }.toInt(), m.groupValues[1]),
            key, System.getenv("LLMWIZARD_PASSWORD"), System.getenv("LLMWIZARD_SUDO_PASSWORD"))
    }
}
