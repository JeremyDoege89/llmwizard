package dev.dreamz.llmwizard.core.server

import dev.dreamz.llmwizard.core.scan.ScanParser
import kotlinx.serialization.Serializable

/** A snapshot for the server screen (PLAN.md Phase 3). Unknown stays null. */
@Serializable
data class ServerStatus(
    val active: String? = null,
    val enabled: String? = null,
    val since: String? = null,
    val bind: String? = null,
    val ollamaVersion: String? = null,
    val load1: Double? = null,
    val cpus: Int? = null,
    val memTotalBytes: Long? = null,
    val memAvailableBytes: Long? = null,
    val diskFreeBytes: Long? = null,
    val diskTotalBytes: Long? = null,
    val log: List<String> = emptyList(),
    val logReadable: Boolean = true,
)

object StatusProbe {
    val script: String by lazy {
        requireNotNull(StatusProbe::class.java.getResourceAsStream("/llmwizard/status.sh")).readBytes().toString(Charsets.UTF_8)
    }

    fun parse(out: String): ServerStatus {
        val s = ScanParser.sections(out)
        fun kv(sec: String) = s[sec].orEmpty().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1).trim() } }.toMap()
        val svc = kv("service")
        val env = svc["env"].orEmpty()
        val bind = Regex("OLLAMA_HOST=(\\S+)").find(env)?.groupValues?.get(1)
        val load = s["load"]?.firstOrNull()?.split(' ')?.firstOrNull()?.toDoubleOrNull()
        fun kb(k: String) = s["meminfo"]?.firstOrNull { it.startsWith("$k:") }?.substringAfter(':')?.trim()?.split(' ')?.firstOrNull()?.toLongOrNull()?.times(1024)
        val disk = s["disk"]?.firstOrNull()?.split(Regex("\\s+"))
        val log = s["log"].orEmpty()
        return ServerStatus(
            active = svc["active"]?.ifBlank { null }, enabled = svc["enabled"]?.ifBlank { null }, since = svc["since"]?.ifBlank { null },
            bind = bind, ollamaVersion = svc["version"]?.ifBlank { null },
            load1 = load, cpus = kv("load")["nproc"]?.toIntOrNull(),
            memTotalBytes = kb("MemTotal"), memAvailableBytes = kb("MemAvailable"),
            diskTotalBytes = disk?.getOrNull(2)?.toLongOrNull(), diskFreeBytes = disk?.getOrNull(4)?.toLongOrNull(),
            log = log.filterNot { it.startsWith("(this user") }.takeLast(80),
            logReadable = log.none { it.startsWith("(this user") },
        )
    }
}
