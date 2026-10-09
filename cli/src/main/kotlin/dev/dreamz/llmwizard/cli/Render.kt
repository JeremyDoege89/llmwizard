package dev.dreamz.llmwizard.cli

import dev.dreamz.llmwizard.core.catalog.Recommendation
import dev.dreamz.llmwizard.core.scan.HardwareProfile

/** Plain-text views for the terminal. "?" marks unknown, never zero. */
object Render {
    fun gb(bytes: Long?) = bytes?.let { "%.1f GB".format(it / 1e9) } ?: "?"

    fun profile(p: HardwareProfile): String = buildString {
        val os = p.os
        appendLine("System:  ${os?.prettyName ?: "?"} ${os?.arch ?: ""}, kernel ${os?.kernel ?: "?"}, systemd ${p.systemd ?: "?"}")
        appendLine("Virt:    ${p.virtualization ?: "?"}${p.container?.let { " (container $it)" } ?: ""}")
        val c = p.cpu
        appendLine("CPU:     ${c?.model ?: "?"} — ${c?.sockets ?: "?"} socket(s) x ${c?.coresPerSocket ?: "?"} cores, ${c?.logicalCpus ?: "?"} threads, NUMA ${c?.numaNodes ?: "?"}")
        appendLine("Flags:   ${c?.flags?.sorted()?.joinToString(" ")?.ifEmpty { "(none of avx/avx2/fma/f16c)" } ?: "?"}")
        appendLine("Memory:  ${gb(p.memory?.totalBytes)} total, ${gb(p.memory?.availableBytes)} available")
        val gpus = p.nvidia?.cards?.joinToString { "${it.name} ${it.vramMiB ?: "?"} MiB" }
        appendLine("GPU:     ${gpus?.ifEmpty { null } ?: p.gpus.joinToString { "${it.vendor} ${it.deviceId}" }.ifEmpty { "none found" }} (nvidia-smi ${p.nvidia?.smi ?: "?"})")
        appendLine("Disk /:  ${gb(p.disk?.root?.freeBytes)} free of ${gb(p.disk?.root?.totalBytes)}")
        val o = p.ollama
        append("Ollama:  ${if (o?.installed == true) "${o.version ?: "?"} at ${o.path}, service ${o.serviceActive ?: "?"}" else "not installed"}")
    }

    fun recommendation(r: Recommendation): String = buildString {
        r.bandwidthLabel?.let { appendLine("Memory speed guess: $it") }
        appendLine("Pick:     ${r.pick?.reason ?: "none"}")
        r.pick?.candidate?.let { appendLine("          ${it.entry.ollamaTag}, download ${gb(it.entry.downloadBytes)}") }
        r.faster?.let { appendLine("Faster:   ${it.reason.removePrefix("Faster: ")}  [${it.candidate.entry.ollamaTag}]") }
        r.smarter?.let { appendLine("Smarter:  ${it.reason.removePrefix("Smarter: ")}  [${it.candidate.entry.ollamaTag}]") }
        r.notes.forEach { appendLine("Note:     $it") }
    }.trimEnd()

    /** Progress bars write carriage returns and ANSI codes; keep the last state of each line. */
    fun cleanLog(s: String): String = s.replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "")
        .lines().joinToString("\n") { it.substringAfterLast('\r') }
}
