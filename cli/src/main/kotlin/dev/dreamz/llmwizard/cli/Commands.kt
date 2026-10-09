package dev.dreamz.llmwizard.cli

import dev.dreamz.llmwizard.core.remote.Remote
import dev.dreamz.llmwizard.core.scan.ScanParser
import dev.dreamz.llmwizard.core.scan.Verdicts
import dev.dreamz.llmwizard.core.ssh.FileHostKeyStore
import dev.dreamz.llmwizard.core.ssh.HostKeyPolicy
import dev.dreamz.llmwizard.core.ssh.SshSession
import dev.dreamz.llmwizard.core.ssh.SshjTransport
import dev.dreamz.llmwizard.core.target.TargetEntry
import dev.dreamz.llmwizard.core.target.Targets
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration.Companion.seconds

class Commands(private val args: List<String>) {
    private val targetsFile = File("targets.local.properties")
    private val configDir = File(System.getProperty("user.home"), ".config/llmwizard")
    private val hostKeys = FileHostKeyStore(File(configDir, "known_hosts.properties"))
    private val json = Json { prettyPrint = true; encodeDefaults = false }

    private fun arg(i: Int, what: String) = args.getOrNull(i) ?: throw CliError("missing $what")
    private fun opt(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    private fun flag(name: String) = name in args

    suspend fun run(cmd: String?): Int = when (cmd) {
        "ping" -> ping()
        "scan" -> scan()
        null, "help", "-h", "--help" -> { usage(); 0 }
        else -> throw CliError("unknown command '$cmd' (try help)")
    }

    private fun usage() = println(
        """
        llmwizard <command> <target> [options]

          ping <target>                     connect, print the host key fingerprint, run uname -a
          scan <target> [--json] [--save-fixture <name>]
                                            read-only hardware scan and plain-English verdicts

        <target> is a name in targets.local.properties, or user@host[:port]
        (then LLMWIZARD_KEY=<private key file> or LLMWIZARD_PASSWORD=… is used).
        """.trimIndent(),
    )

    private fun entry(): TargetEntry = Targets.resolve(arg(0, "target"), targetsFile)

    private suspend fun connect(e: TargetEntry): SshSession {
        val known = hostKeys.get(e.target.host, e.target.port)
        val s = SshjTransport().connect(e.target, e.auth(), HostKeyPolicy(hostKeys, acceptNew = true))
        if (known == null) System.err.println("First contact with ${e.target.name}: trusted host key ${s.hostKey.keyType} ${s.hostKey.fingerprint}")
        return s
    }

    private suspend fun ping(): Int {
        val e = entry()
        connect(e).use { s ->
            println("host key: ${s.hostKey.keyType} ${s.hostKey.fingerprint}")
            val r = Remote(s).probe("uname -a", 20.seconds)
            print(r.out)
            return r.exitCode
        }
    }

    private suspend fun scan(): Int {
        val e = entry()
        connect(e).use { s ->
            val r = Remote(s).probe(ScanParser.script, 120.seconds)
            opt("--save-fixture")?.let { name ->
                val f = File("fixtures/$name.txt")
                f.writeText(r.out)
                System.err.println("saved ${f.path}")
            }
            val p = ScanParser.parse(r.out)
            if (flag("--json")) { println(json.encodeToString(p)); return 0 }
            println(Render.profile(p))
            println()
            Verdicts.of(p).forEach { println("[${it.severity}] ${it.message}") }
            return 0
        }
    }
}
