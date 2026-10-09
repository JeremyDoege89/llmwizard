package dev.dreamz.llmwizard.core.scan

/**
 * Turns scan.sh output into a [HardwareProfile]. Tolerant by design: unknown sections are
 * ignored, missing sections leave fields null, and a garbled line is skipped, never fatal.
 */
object ScanParser {

    /** The script the app shows and runs, from resources. */
    val script: String by lazy {
        requireNotNull(ScanParser::class.java.getResourceAsStream("/llmwizard/scan.sh")) { "scan.sh missing" }
            .readBytes().toString(Charsets.UTF_8)
    }

    private val INTERESTING_FLAGS = setOf("avx", "avx2", "avx512f", "fma", "f16c", "avx_vnni", "avx512_vnni")

    fun sections(output: String): Map<String, List<String>> {
        val map = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("@@ ")) {
                current = map.getOrPut(line.removePrefix("@@ ").trim()) { mutableListOf() }
            } else if (line.isNotBlank()) {
                current?.add(line)
            }
        }
        return map
    }

    fun parse(output: String): HardwareProfile {
        val s = sections(output)
        return HardwareProfile(
            os = s["os"]?.let(::os),
            systemd = s["init"]?.let { kv(it)["systemd"] }?.let { it == "yes" },
            virtualization = s["virt"]?.let { kv(it)["virt"] }?.takeIf { it.isNotBlank() },
            container = s["virt"]?.let { kv(it)["container"] }?.takeIf { it.isNotBlank() && it != "none" },
            cpu = cpu(s["lscpu"], s["cpuflags"], s["numa"]),
            memory = s["meminfo"]?.let(::memory),
            gpus = s["pci"]?.mapNotNull(::pci).orEmpty(),
            nvidia = s["nvidia"]?.let(::nvidia),
            disk = s["disk"]?.let(::disk),
            ollama = s["ollama"]?.let(::ollama),
            network = s["net"]?.let(::network),
            firewall = s["firewall"]?.let(::firewall),
            tools = s["tools"]?.let { kv(it) }?.mapValues { it.value == "yes" }.orEmpty(),
            privilege = s["priv"]?.let(::priv),
        )
    }

    private fun kv(lines: List<String>): Map<String, String> = lines.mapNotNull { l ->
        val i = l.indexOf('=')
        if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim().removeSurrounding("\"")
    }.toMap()

    private fun os(lines: List<String>): OsInfo {
        val m = kv(lines)
        return OsInfo(
            id = m["ID"]?.lowercase(),
            idLike = m["ID_LIKE"]?.lowercase()?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
            versionId = m["VERSION_ID"],
            prettyName = m["PRETTY_NAME"],
            name = m["NAME"],
            arch = m["ARCH"]?.takeIf { it.isNotBlank() },
            kernel = m["KERNEL"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun cpu(lscpu: List<String>?, flags: List<String>?, numa: List<String>?): CpuInfo? {
        if (lscpu == null && flags == null) return null
        val l = lscpu.orEmpty().mapNotNull { line ->
            val i = line.indexOf(':'); if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()
        val procFlags = flags?.firstOrNull { it.startsWith("flags") }?.substringAfter(':')?.trim()?.split(' ')
        val allFlags = procFlags ?: l["Flags"]?.split(' ')
        val procModel = flags?.firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim()
        val nproc = flags?.let { kv(it)["nproc"] }?.toIntOrNull()
        val numaFromSys = numa?.count { it.startsWith("node") }?.takeIf { it > 0 }
        return CpuInfo(
            model = l["Model name"] ?: procModel,
            vendor = l["Vendor ID"],
            sockets = l["Socket(s)"]?.toIntOrNull(),
            coresPerSocket = l["Core(s) per socket"]?.toIntOrNull() ?: l["Core(s) per cluster"]?.toIntOrNull(),
            threadsPerCore = l["Thread(s) per core"]?.toIntOrNull(),
            logicalCpus = l["CPU(s)"]?.toIntOrNull() ?: nproc,
            flags = allFlags?.map { it.lowercase() }?.filter { it in INTERESTING_FLAGS }?.toSet(),
            numaNodes = numaFromSys ?: l["NUMA node(s)"]?.toIntOrNull(),
            hypervisorVendor = l["Hypervisor vendor"],
            family = l["CPU family"]?.toIntOrNull(),
            modelNumber = l["Model"]?.toIntOrNull(),
        )
    }

    private fun memory(lines: List<String>): MemoryInfo {
        fun kb(key: String) = lines.firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')?.trim()?.split(' ')?.firstOrNull()?.toLongOrNull()?.times(1024)
        return MemoryInfo(kb("MemTotal"), kb("MemAvailable"), kb("SwapTotal"))
    }

    private fun pci(line: String): PciGpu? {
        val parts = line.split(' ')
        if (parts.isEmpty() || !parts[0].contains(':')) return null
        val m = parts.drop(1).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        return PciGpu(parts[0], m["vendor"], m["device"], m["class"], m["driver"]?.takeIf { it.isNotBlank() })
    }

    private fun nvidia(lines: List<String>): NvidiaInfo {
        if (lines.any { it == "nvidia_smi=absent" }) return NvidiaInfo(SmiState.ABSENT)
        val exit = lines.firstOrNull { it.startsWith("nvidia_smi_exit=") }?.substringAfter('=')?.toIntOrNull()
        val body = lines.filterNot { it.startsWith("nvidia_smi_exit=") }
        if (exit != 0) return NvidiaInfo(SmiState.FAILING, error = body.joinToString("\n").take(500))
        val cards = body.mapNotNull { line ->
            val f = line.split(',').map { it.trim() }
            if (f.size < 3) null else NvidiaCard(f[0], f[1].toLongOrNull(), f[2])
        }
        return NvidiaInfo(if (cards.isEmpty()) SmiState.FAILING else SmiState.WORKING, cards)
    }

    private fun disk(lines: List<String>): DiskInfo {
        fun fs(fields: List<String>): Filesystem? {
            // device total used free capacity mount
            if (fields.size < 6) return null
            return Filesystem(fields[0], fields[1].toLongOrNull(), fields[2].toLongOrNull(), fields[3].toLongOrNull(), fields.drop(5).joinToString(" "))
        }
        var root: Filesystem? = null
        val models = mutableListOf<Pair<String, Filesystem>>()
        for (line in lines) {
            val parts = line.split(Regex("\\s+"))
            when {
                parts[0] == "root" -> root = fs(parts.drop(1))
                parts[0].startsWith("models:") -> fs(parts.drop(1))?.let { models += parts[0].removePrefix("models:") to it }
            }
        }
        return DiskInfo(root, models)
    }

    private fun ollama(lines: List<String>): OllamaInfo {
        val m = kv(lines)
        val api = m["api"]?.let { Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        return OllamaInfo(
            path = m["path"]?.takeIf { it.isNotBlank() },
            version = m["version"]?.let { Regex("([0-9]+\\.[0-9]+\\.[0-9]+[0-9A-Za-z.+-]*)").find(it)?.value },
            serviceActive = m["service_active"]?.takeIf { it.isNotBlank() },
            serviceEnabled = m["service_enabled"]?.takeIf { it.isNotBlank() },
            serviceFragment = m["service_fragment"]?.takeIf { it.isNotBlank() },
            serviceDropIns = m["service_dropins"]?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
            serviceEnvironment = m["service_env"]?.takeIf { it.isNotBlank() },
            apiVersion = api,
            manifestPresent = m["manifest"]?.let { it == "yes" },
        )
    }

    private fun network(lines: List<String>): NetworkInfo {
        val addrs = lines.mapNotNull { l ->
            Regex("^\\d+:\\s+(\\S+)\\s+inet\\s+(\\S+)").find(l)?.let { IfAddress(it.groupValues[1], it.groupValues[2]) }
        }
        val listening = lines.filter { it.startsWith("LISTEN") }.mapNotNull { it.split(Regex("\\s+")).getOrNull(3) }
        return NetworkInfo(addrs, listening)
    }

    private fun firewall(lines: List<String>): FirewallInfo {
        val m = kv(lines)
        return FirewallInfo(
            ufwPresent = m["ufw"]?.let { it == "present" },
            ufwEnabled = m["ENABLED"]?.let { it.equals("yes", true) },
            firewalld = if (m["firewalld"] == "present") true else null,
            tailscale = m["tailscale"] == "present",
        )
    }

    private fun priv(lines: List<String>): PrivilegeInfo {
        val m = kv(lines)
        return PrivilegeInfo(
            uid = m["uid"]?.toIntOrNull(),
            user = m["user"],
            groups = m["groups"]?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
            sudo = m["sudo"],
            shell = m["shell"]?.takeIf { it.isNotBlank() },
        )
    }
}
