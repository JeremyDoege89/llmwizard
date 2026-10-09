package dev.dreamz.llmwizard.cli

import dev.dreamz.llmwizard.core.agent.Eval
import dev.dreamz.llmwizard.core.agent.EvalReport
import dev.dreamz.llmwizard.core.agent.Proposal
import dev.dreamz.llmwizard.core.agent.ServerBrain
import dev.dreamz.llmwizard.core.agent.ToolRegistry
import dev.dreamz.llmwizard.core.catalog.Catalog
import dev.dreamz.llmwizard.core.catalog.Intent
import dev.dreamz.llmwizard.core.catalog.Recommender
import dev.dreamz.llmwizard.core.ollama.Benchmark
import dev.dreamz.llmwizard.core.ollama.ChatMessage
import dev.dreamz.llmwizard.core.ollama.ChatRequest
import dev.dreamz.llmwizard.core.recipe.EngineEvent
import dev.dreamz.llmwizard.core.recipe.PlannedStep
import dev.dreamz.llmwizard.core.recipe.Recipe
import dev.dreamz.llmwizard.core.remote.Privilege
import dev.dreamz.llmwizard.core.scan.ScanParser
import dev.dreamz.llmwizard.core.scan.Severity
import dev.dreamz.llmwizard.core.scan.Verdicts
import dev.dreamz.llmwizard.core.server.Server
import dev.dreamz.llmwizard.core.ssh.FileHostKeyStore
import dev.dreamz.llmwizard.core.ssh.HostKeyPolicy
import dev.dreamz.llmwizard.core.ssh.SshSession
import dev.dreamz.llmwizard.core.ssh.SshjTransport
import dev.dreamz.llmwizard.core.target.TargetEntry
import dev.dreamz.llmwizard.core.target.Targets
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration.Companion.seconds

class Commands(private val args: List<String>) {
    private val targetsFile = File("targets.local.properties")
    private val configDir = File(System.getProperty("user.home"), ".config/llmwizard")
    private val hostKeys = FileHostKeyStore(File(configDir, "known_hosts.properties"))
    private val resume = FileResumeStore(File(configDir, "resume.json"))
    private val json = Json { prettyPrint = true; encodeDefaults = false }
    private val catalog by lazy { Catalog.bundled() }

    private fun arg(i: Int, what: String) = args.getOrNull(i) ?: throw CliError("missing $what")
    private fun opt(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) ?: throw CliError("$name needs a value") }
    private fun flag(name: String) = name in args

    suspend fun run(cmd: String?): Int = when (cmd) {
        "ping" -> ping()
        "scan" -> scan()
        "recommend" -> recommend()
        "plan" -> install(dryRun = true)
        "install" -> install(dryRun = false)
        "status" -> status()
        "models" -> models()
        "pull" -> pull()
        "chat" -> chat()
        "bench" -> bench()
        "configure" -> configure()
        "update" -> update()
        "uninstall" -> uninstall()
        "transcript" -> transcript()
        "agent" -> agent()
        "expose" -> expose()
        "bundle" -> bundle()
        "catalog-check" -> catalogCheck()
        "eval-tools" -> evalTools()
        null, "help", "-h", "--help" -> { usage(); 0 }
        else -> throw CliError("unknown command '$cmd' (try help)")
    }

    private fun usage() = println(
        """
        llmwizard <command> <target> [options]

          ping <target>            connect, print the host key fingerprint, run uname -a
          scan <target> [--json] [--save-fixture <name>]
                                   read-only hardware scan and plain-English verdicts
          recommend <target|fixture:<name>> [--for chat|code|assistant]
                                   model recommendation with speed estimates
          plan <target> [--model <tag>] [--show-scripts]
                                   preflight and the exact steps install would run; runs nothing
          install <target> [--model <tag>] [--for …] [--yes] [--undo-on-failure]
                                   install Ollama and a model, then benchmark it
          status <target>          service, version, settings, loaded models
          models <target>          installed and loaded models
          pull <target> <tag>      download a model (background job on the server)
          chat <target> [--model <tag>]
                                   chat in the terminal; an empty line or Ctrl-D ends it
          bench <target> [--model <tag>]
                                   measure tokens/s on the server
          configure <target> [--bind a:p] [--context n] [--keep-alive 30m]
          update <target>          update Ollama in place
          uninstall <target> [--delete-models] [--yes]
                                   remove what llmwizard installed (and only that)
          transcript <target>      every script sent to the target, with exit codes
          expose <target> [lan [--address <ip>] | private]
                                   show exposure state, share with the LAN, or go back to private
          bundle keygen <private-key-file>       new signing key; prints the public key to pin in the app
          bundle make <version> <out.json>        recipes/ + catalog/ into one bundle file
          bundle sign <bundle.json> <private-key-file>   writes <bundle.json>.sig
          bundle verify <bundle.json> <public-key-base64> [--min-version n]
          catalog-check [--write]  resolve every catalog tag on registry.ollama.ai; --write updates sizes
          agent <target> "<request>" [--model <tag>]
                                   print the tool proposal the server model makes; executes nothing
          eval-tools <target> --model <tag> [--limit n]
                                   tool-calling accuracy and time per command on the eval set

        <target> is a name in targets.local.properties, or user@host[:port]
        (then LLMWIZARD_KEY=<private key file> or LLMWIZARD_PASSWORD=… is used).
        """.trimIndent(),
    )

    private fun entry(): TargetEntry = Targets.resolve(arg(0, "target"), targetsFile)

    private fun transcriptFile(e: TargetEntry) = File(configDir, "transcripts/${e.target.name.replace(Regex("[^A-Za-z0-9@._-]"), "_")}.jsonl")

    private suspend fun connect(e: TargetEntry): SshSession {
        val known = hostKeys.get(e.target.host, e.target.port)
        val s = SshjTransport().connect(e.target, e.auth(), HostKeyPolicy(hostKeys, acceptNew = true))
        if (known == null) System.err.println("First contact with ${e.target.name}: trusted host key ${s.hostKey.keyType} ${s.hostKey.fingerprint}")
        return s
    }

    private fun server(e: TargetEntry, s: SshSession) = Server(s, resume, FileTranscript(transcriptFile(e)))

    private suspend fun privilege(server: Server, e: TargetEntry): Privilege = server.privilege {
        (e.sudoPassword ?: System.getenv("LLMWIZARD_SUDO_PASSWORD"))?.toCharArray()
            ?: System.console()?.readPassword("sudo password for ${e.target.user}@${e.target.host}: ")
    }

    private fun intent(): Intent = when (opt("--for")) {
        null, "chat" -> Intent.CHAT
        "code" -> Intent.CODE
        "assistant", "tools" -> Intent.ASSISTANT
        else -> throw CliError("--for takes chat, code or assistant")
    }

    // ---- commands ----

    private suspend fun ping(): Int {
        val e = entry()
        connect(e).use { s ->
            println("host key: ${s.hostKey.keyType} ${s.hostKey.fingerprint}")
            val r = server(e, s).remote.probe("uname -a", 20.seconds)
            print(r.out)
            return r.exitCode
        }
    }

    private suspend fun scan(): Int {
        val e = entry()
        connect(e).use { s ->
            val (raw, p) = server(e, s).scan()
            opt("--save-fixture")?.let { name ->
                val f = File("fixtures/$name.txt")
                f.writeText(raw)
                System.err.println("saved ${f.path}")
            }
            if (flag("--json")) { println(json.encodeToString(p)); return 0 }
            println(Render.profile(p))
            println()
            Verdicts.of(p).forEach { println("[${it.severity}] ${it.message}") }
            return 0
        }
    }

    private suspend fun recommend(): Int {
        val spec = arg(0, "target")
        val profile = if (spec.startsWith("fixture:")) {
            ScanParser.parse(File("fixtures/${spec.removePrefix("fixture:")}.txt").readText())
        } else {
            val e = entry()
            connect(e).use { s -> server(e, s).scan().second }
        }
        println(Render.recommendation(Recommender(catalog).recommend(profile, intent())))
        return 0
    }

    private suspend fun install(dryRun: Boolean): Int {
        val e = entry()
        connect(e).use { s ->
            val server = server(e, s)
            val (_, profile) = server.scan()
            println(Render.profile(profile)); println()
            val verdicts = Verdicts.of(profile)
            verdicts.forEach { println("[${it.severity}] ${it.message}") }
            if (verdicts.any { it.severity == Severity.BLOCK }) { println("\nCannot install on this machine."); return 3 }

            val model = opt("--model") ?: run {
                val rec = Recommender(catalog).recommend(profile, intent())
                println(); println(Render.recommendation(rec))
                rec.pick?.candidate?.entry?.ollamaTag
            }
            val params = buildMap {
                model?.let { put("model", it) }
                opt("--bind")?.let { put("bind", it) }
                opt("--context")?.let { put("contextLength", it) }
                opt("--keep-alive")?.let { put("keepAlive", it) }
                opt("--ollama-version")?.let { put("ollamaVersion", it) }
            }
            val privilege = if (dryRun) null else privilege(server, e)
            try {
                val recipe = Recipe.bundled("ollama-install")
                val outcome = server.runRecipe(recipe, params, privilege,
                    approve = { failures, plan -> preview(recipe, plan, failures, dryRun) },
                    events = ::printEvent)
                val result = outcome.result ?: return if (dryRun) 0 else 4
                println("\n${result.message}")
                if (!result.ok) {
                    println("The log is above. Running install again resumes where it stopped.")
                    if (flag("--undo-on-failure") && result.ran.isNotEmpty()) {
                        println("Undoing the steps that ran…")
                        val ok = outcome.engine.undo(recipe, result.ran, outcome.values, ::printEvent)
                        println(if (ok) "Undone." else "Undo did not complete; see the log.")
                    }
                    return 1
                }
                if (model != null) benchAndReport(server, model, profile)
                return 0
            } finally {
                (privilege as? Privilege.SudoPassword)?.wipe()
            }
        }
    }

    private suspend fun preview(recipe: Recipe, plan: List<PlannedStep>, failures: List<String>, dryRun: Boolean): Boolean {
        println("\n== ${recipe.title}: ${recipe.description}")
        if (failures.isNotEmpty()) {
            println("Preflight problems:"); failures.forEach { println("  - $it") }
            return false
        }
        for (st in plan) {
            val mark = if (st.alreadyDone) "done   " else if (st.spec.sudo) "as root" else "       "
            println("  ${st.index}. [$mark] ${st.spec.title}${if (st.spec.detached) " (background)" else ""}")
            println("        ${st.spec.why}")
            if (flag("--show-scripts") && !st.alreadyDone) println(st.script.prependIndent("        | "))
        }
        if (plan.all { it.alreadyDone }) println("  Everything is already in place.")
        if (dryRun) { println("\n(plan only: nothing was run. --show-scripts prints each script.)"); return false }
        if (flag("--yes")) return true
        print("\nRun these steps? [y/N] ")
        return readlnOrNull()?.trim()?.lowercase() == "y"
    }

    private suspend fun printEvent(ev: EngineEvent) {
        when (ev) {
            is EngineEvent.StepSkipped -> println("-- ${ev.step.spec.title}: already done")
            is EngineEvent.StepStarted -> println("-> ${ev.step.spec.title}")
            is EngineEvent.Log -> print(Render.cleanLog(ev.text).prependIndent("   "))
            is EngineEvent.StepFinished -> println("   exit ${ev.exitCode}")
            is EngineEvent.VerifyFailed -> println("   the check after this step failed")
            is EngineEvent.UndoStarted -> println("<- undo: ${ev.step.spec.title}")
            is EngineEvent.UndoFinished -> println("   exit ${ev.exitCode}")
        }
        System.out.flush()
    }

    private suspend fun benchAndReport(server: Server, model: String, profile: dev.dreamz.llmwizard.core.scan.HardwareProfile?) {
        val client = server.ollama(server.ollamaEndpoint(server.manifest()))
        try {
            println("\nMeasuring speed of $model (loads the model first; this can take a while on a CPU)…")
            val b = Benchmark.run(client, model)
            println("Measured: %.1f tokens/s, model loaded in %.1f s".format(b.tokensPerSecond, b.loadSeconds))
            val estimate = profile?.let { p ->
                Recommender(catalog).recommend(p).candidates.firstOrNull { it.entry.ollamaTag == model }?.estimate
            }
            estimate?.let { println("Estimated before install: ${it.describe()}") }
            BenchStore(File(configDir, "benchmarks.jsonl")).add(server.session.target.name, b, estimate?.describe())
        } finally {
            client.close()
        }
    }

    private suspend fun status(): Int {
        val e = entry()
        connect(e).use { s ->
            val server = server(e, s)
            val (_, p) = server.scan()
            val m = server.manifest()
            println(Render.profile(p))
            println("Manifest: ${if (m == null) "none (llmwizard has not installed anything here)" else "${m.created.size} item(s) created by llmwizard; last: ${m.history.lastOrNull()?.let { "${it.recipe} ${it.result} at ${it.at}" }}"}")
            m?.settings?.forEach { (k, v) -> println("  $k = $v") }
            if (p.ollama?.serviceActive == "active") {
                val c = server.ollama(server.ollamaEndpoint(m))
                try {
                    println("Ollama API: ${c.version()}")
                    c.ps().forEach { println("  loaded: ${it.name} (${Render.gb(it.size)}, until ${it.expiresAt})") }
                } finally { c.close() }
            }
            return 0
        }
    }

    private suspend fun models(): Int {
        val e = entry()
        connect(e).use { s ->
            val server = server(e, s)
            val c = server.ollama(server.ollamaEndpoint(server.manifest()))
            try {
                val loaded = c.ps().map { it.name }.toSet()
                for (m in c.tags()) {
                    println("%-28s %9s  %-8s %s%s".format(m.name, Render.gb(m.size), m.details?.quantizationLevel ?: "?", m.details?.parameterSize ?: "", if (m.name in loaded) "  [loaded]" else ""))
                }
            } finally { c.close() }
            return 0
        }
    }

    private suspend fun pull(): Int {
        val e = entry()
        val tag = arg(1, "model tag")
        connect(e).use { s ->
            val server = server(e, s)
            val recipe = Recipe.bundled("model-pull")
            val bind = server.manifest()?.settings?.get("settings.bind")
            val outcome = server.runRecipe(recipe, buildMap { put("model", tag); bind?.let { put("bind", it) } }, null,
                approve = { f, plan -> preview(recipe, plan, f, false) }, events = ::printEvent)
            println(outcome.result?.message ?: "Not run.")
            return if (outcome.result?.ok == true) 0 else 1
        }
    }

    private suspend fun chat(): Int {
        val e = entry()
        connect(e).use { s ->
            val server = server(e, s)
            val c = server.ollama(server.ollamaEndpoint(server.manifest()))
            try {
                val model = opt("--model") ?: c.tags().firstOrNull()?.name ?: throw CliError("no models installed")
                println("Chatting with $model. Empty line or Ctrl-D to quit.")
                val history = mutableListOf<ChatMessage>()
                while (true) {
                    print("\nyou> "); System.out.flush()
                    val line = readlnOrNull()?.takeIf { it.isNotBlank() } ?: break
                    history += ChatMessage("user", line)
                    val loaded = c.ps().any { it.name == model }
                    if (!loaded) println("(loading model…)")
                    print("$model> "); System.out.flush()
                    val reply = StringBuilder()
                    var tps: Double? = null
                    c.chat(ChatRequest(model, history.toList(), think = false)).collect { ch ->
                        ch.message?.content?.let { print(it); System.out.flush(); reply.append(it) }
                        if (ch.done) tps = ch.tokensPerSecond
                    }
                    println(); tps?.let { println("(%.1f tokens/s, measured)".format(it)) }
                    history += ChatMessage("assistant", reply.toString())
                }
            } finally { c.close() }
            return 0
        }
    }

    private suspend fun bench(): Int {
        val e = entry()
        connect(e).use { s ->
            val server = server(e, s)
            val c = server.ollama(server.ollamaEndpoint(server.manifest()))
            val model = try { opt("--model") ?: c.tags().firstOrNull()?.name ?: throw CliError("no models installed") } finally { c.close() }
            benchAndReport(server, model, server.scan().second)
            return 0
        }
    }

    private suspend fun simpleRecipe(id: String, params: Map<String, String>): Int {
        val e = entry()
        connect(e).use { s ->
            val server = server(e, s)
            val privilege = privilege(server, e)
            try {
                val recipe = Recipe.bundled(id)
                val outcome = server.runRecipe(recipe, params, privilege, approve = { f, plan -> preview(recipe, plan, f, false) }, events = ::printEvent)
                println(outcome.result?.message ?: "Not run.")
                return if (outcome.result?.ok == true) 0 else 1
            } finally {
                (privilege as? Privilege.SudoPassword)?.wipe()
            }
        }
    }

    private suspend fun configure(): Int = simpleRecipe("ollama-configure", buildMap {
        opt("--bind")?.let { put("bind", it) }
        opt("--context")?.let { put("contextLength", it) }
        opt("--keep-alive")?.let { put("keepAlive", it) }
    })

    private suspend fun update(): Int = simpleRecipe("ollama-update", emptyMap())

    private suspend fun uninstall(): Int {
        val e = entry()
        val deleteModels = flag("--delete-models")
        if (deleteModels) {
            connect(e).use { s ->
                val server = server(e, s)
                val c = server.ollama(server.ollamaEndpoint(server.manifest()))
                try {
                    val models = runCatching { kotlinx.coroutines.runBlocking { c.tags() } }.getOrDefault(emptyList())
                    println("These models will be deleted:")
                    if (models.isEmpty()) println("  (could not list them; the whole models folder will be deleted)")
                    models.forEach { println("  ${it.name}  ${Render.gb(it.size)}") }
                } finally { c.close() }
            }
        }
        return simpleRecipe("ollama-uninstall", mapOf("deleteModels" to if (deleteModels) "yes" else "no"))
    }

    private suspend fun expose(): Int {
        val e = entry()
        val mode = args.getOrNull(1)
        connect(e).use { s ->
            val server = server(e, s)
            val manifest = server.manifest()
            val bind = manifest?.settings?.get("settings.bind")
            val state = dev.dreamz.llmwizard.core.exposure.Exposure.state(server.scan().second, bind)
            println("Ollama listens on ${state.bind}. Firewall (ufw) active: ${state.ufwActive ?: "unknown"}.")
            dev.dreamz.llmwizard.core.exposure.Exposure.warnings(state).forEach { println("! $it") }
            val opts = dev.dreamz.llmwizard.core.exposure.Exposure.options(state)
            if (mode == null) {
                println("Home-network choices:")
                opts.forEach { println("  ${if (it.allowed) "ok " else "NO "} ${it.bind}  ${it.label}: ${it.reason}") }
                dev.dreamz.llmwizard.core.exposure.Exposure.urls(state).forEach { println("Other apps: $it/v1 (any API key), native: $it") }
                return 0
            }
            val keep = mapOf("contextLength" to (manifest?.settings?.get("settings.contextLength") ?: "8192"), "keepAlive" to (manifest?.settings?.get("settings.keepAlive") ?: "30m"))
            val privilege = privilege(server, e)
            try {
                val (recipeId, params) = when (mode) {
                    "lan" -> {
                        val want = opt("--address")
                        val choice = opts.firstOrNull { it.allowed && it.firewallCidr != null && (want == null || it.bind.startsWith("$want:")) }
                            ?: throw CliError("no allowed LAN address" + (want?.let { " matching $it" } ?: ""))
                        "expose-lan" to keep + mapOf("bind" to choice.bind) + (if (state.ufwActive == true) mapOf("firewallCidr" to choice.firewallCidr!!) else emptyMap())
                    }
                    "private" -> "expose-private" to keep + (manifest?.names("ufw")?.firstOrNull()?.let { mapOf("firewallCidr" to it) } ?: emptyMap())
                    else -> throw CliError("expose takes lan or private")
                }
                val recipe = Recipe.bundled(recipeId)
                val out = server.runRecipe(recipe, params, privilege, approve = { f, plan -> preview(recipe, plan, f, false) }, events = ::printEvent)
                println(out.result?.message ?: "Not run.")
                return if (out.result?.ok == true) 0 else 1
            } finally {
                (privilege as? Privilege.SudoPassword)?.wipe()
            }
        }
    }

    private fun bundle(): Int {
        val signing = dev.dreamz.llmwizard.core.bundle.BundleSigning
        when (arg(0, "bundle subcommand")) {
            "keygen" -> {
                val f = File(arg(1, "private key file"))
                if (f.exists()) throw CliError("${f.path} exists; not overwriting a key")
                val (priv, pub) = signing.generateKeyPair()
                f.parentFile?.mkdirs()
                f.writeText(priv + "\n")
                f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true)
                println("Private key written to ${f.path} (keep it offline, never in git).")
                println("Public key to pin in the app: $pub")
            }
            "make" -> {
                val version = arg(1, "version").toInt()
                val out = File(arg(2, "output file"))
                val recipes = File("recipes").listFiles()!!.filter { it.name.endsWith(".json") }.associate { it.nameWithoutExtension to it.readText() }
                out.writeText(signing.make(version, java.time.Instant.now().toString(), recipes, File("catalog/catalog.json").readText(), File("catalog/snippets.json").readText()))
                println("Wrote ${out.path}: version $version, ${recipes.size} recipes")
            }
            "sign" -> {
                val b = File(arg(1, "bundle")); val key = File(arg(2, "private key file")).readText()
                File(b.path + ".sig").writeText(signing.sign(b.readBytes(), key) + "\n")
                println("Wrote ${b.path}.sig")
            }
            "verify" -> {
                val b = File(arg(1, "bundle"))
                val r = signing.verify(b.readBytes(), File(b.path + ".sig").readText(), listOf(signing.pinned("given", arg(2, "public key"))), opt("--min-version")?.toInt() ?: 0)
                println(r)
                return if (r is dev.dreamz.llmwizard.core.bundle.BundleResult.Accepted) 0 else 1
            }
            else -> throw CliError("bundle takes keygen, make, sign or verify")
        }
        return 0
    }

    private suspend fun catalogCheck(): Int {
        val file = File("catalog/catalog.json")
        val cat = Catalog.parse(file.readText())
        val check = dev.dreamz.llmwizard.core.catalog.CatalogCheck()
        val rows = check.check(cat)
        rows.forEach { r -> println("%-24s %s %s".format(r.entry.ollamaTag, if (r.resolved) "ok " else "BAD", r.problem ?: "")) }
        val bad = rows.count { !it.resolved }
        if (flag("--write") && rows.any { it.problem?.startsWith("size changed") == true }) {
            val pretty = Json { prettyPrint = true; encodeDefaults = true }
            file.writeText(pretty.encodeToString(Catalog.serializer(), check.updated(cat, rows)) + "\n")
            println("catalog.json sizes updated")
        }
        println(if (bad == 0) "All ${rows.size} tags resolve." else "$bad tag(s) do not resolve.")
        return if (bad == 0) 0 else 1
    }

    private suspend fun agent(): Int {
        val e = entry()
        val request = arg(1, "request")
        connect(e).use { s ->
            val server = server(e, s)
            val c = server.ollama(server.ollamaEndpoint(server.manifest()))
            try {
                val model = opt("--model") ?: c.tags().firstOrNull()?.name ?: throw CliError("no models installed")
                val p = ServerBrain(c, model).propose(request, ToolRegistry.DEFAULT)
                println(json.encodeToString(Proposal.serializer(), p))
                println("(nothing was executed)")
            } finally { c.close() }
            return 0
        }
    }

    private suspend fun evalTools(): Int {
        val e = entry()
        val model = opt("--model") ?: throw CliError("--model is required")
        val items = Eval.bundled().let { all -> opt("--limit")?.toInt()?.let { all.take(it) } ?: all }
        connect(e).use { s ->
            val server = server(e, s)
            val c = server.ollama(server.ollamaEndpoint(server.manifest()))
            try {
                println("Warming up $model…")
                ServerBrain(c, model, Eval.CLOCK).propose("set a timer for 1 minute", ToolRegistry.DEFAULT)
                val report = Eval.run(ServerBrain(c, model, Eval.CLOCK), ToolRegistry.DEFAULT, items) { o ->
                    println("%s %-5s %6d ms  %s%s".format(o.id, if (o.pass) "ok" else "FAIL", o.millis, o.request, o.why?.let { "  -> $it" } ?: ""))
                }
                println("\n$model: ${report.passed}/${report.total} right (%.0f%%, measured), median %d ms, p90 %d ms per command".format(report.accuracy * 100, report.medianMillis, report.p90Millis))
                println("Per tool: ${report.perTool.entries.joinToString { "${it.key} ${it.value}" }}")
                File(configDir, "eval-results.jsonl").apply { parentFile.mkdirs() }
                    .appendText(Json.encodeToString(EvalReport.serializer(), report) + "\n")
            } finally { c.close() }
            return 0
        }
    }

    private fun transcript(): Int {
        val f = transcriptFile(entry())
        if (!f.isFile) { println("No transcript yet."); return 0 }
        f.forEachLine { line ->
            val t = Json.decodeFromString(dev.dreamz.llmwizard.core.recipe.TranscriptEntry.serializer(), line)
            println("${t.at}  ${t.kind}  exit ${t.exitCode}")
        }
        return 0
    }
}
