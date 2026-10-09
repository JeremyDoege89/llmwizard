package dev.dreamz.llmwizard.cli

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
}
