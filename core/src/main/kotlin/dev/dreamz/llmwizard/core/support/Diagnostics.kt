package dev.dreamz.llmwizard.core.support

import dev.dreamz.llmwizard.core.recipe.Manifest
import dev.dreamz.llmwizard.core.recipe.TranscriptEntry
import dev.dreamz.llmwizard.core.scan.HardwareProfile
import dev.dreamz.llmwizard.core.scan.Verdicts

/**
 * "Copy diagnostic report" (PLAN.md Phase 5): the support channel, since there is no crash
 * reporter. Hostnames, addresses and user names are redacted by default; the user sees the text
 * before sharing it.
 */
object Diagnostics {
    data class Input(
        val appVersion: String,
        val androidVersion: String,
        val profile: HardwareProfile?,
        val manifest: Manifest?,
        val transcript: List<TranscriptEntry>,
        val host: String?,
        val user: String?,
    )

    fun report(i: Input, redact: Boolean = true): String {
        val raw = buildString {
            appendLine("llmwizard diagnostic report")
            appendLine("app ${i.appVersion}, Android ${i.androidVersion}")
            i.profile?.let { p ->
                appendLine("target: ${p.os?.prettyName ?: "?"} ${p.os?.arch ?: ""}, kernel ${p.os?.kernel ?: "?"}, systemd=${p.systemd}, virt=${p.virtualization}, container=${p.container}")
                appendLine("cpu: ${p.cpu?.model} sockets=${p.cpu?.sockets} cores=${p.cpu?.coresPerSocket} threads=${p.cpu?.logicalCpus} flags=${p.cpu?.flags?.sorted()}")
                appendLine("memory: total=${p.memory?.totalBytes} available=${p.memory?.availableBytes}")
                appendLine("gpu: smi=${p.nvidia?.smi} cards=${p.nvidia?.cards} pci=${p.gpus.map { "${it.vendor}:${it.deviceId}:${it.driver}" }}")
                appendLine("ollama: ${p.ollama}")
                appendLine("firewall: ${p.firewall}")
                Verdicts.of(p).forEach { appendLine("verdict ${it.severity} ${it.code}") }
            }
            i.manifest?.let { m ->
                appendLine("manifest: ${m.created.size} entries; settings ${m.settings}")
                m.history.takeLast(10).forEach { appendLine("history: ${it.at} ${it.recipe} v${it.recipeVersion} ${it.result}") }
            }
            appendLine("transcript (last ${minOf(30, i.transcript.size)}):")
            i.transcript.takeLast(30).forEach { t ->
                appendLine("--- ${t.at} ${t.kind} exit=${t.exitCode}")
                if (t.exitCode != 0 && t.outputTail.isNotBlank()) appendLine(t.outputTail.takeLast(600).prependIndent("    "))
            }
        }
        return if (redact) redact(raw, i.host, i.user) else raw
    }

    private val IPV4 = Regex("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b")
    private val IPV6 = Regex("\\b(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}\\b")
    private val HOME = Regex("/home/[A-Za-z0-9._-]+")

    fun redact(text: String, host: String?, user: String?): String {
        var t = text
        host?.takeIf { it.length > 1 }?.let { t = t.replace(it, "<host>") }
        user?.takeIf { it.length > 1 }?.let { u -> t = t.replace(Regex("\\b${Regex.escape(u)}\\b"), "<user>") }
        t = HOME.replace(t, "/home/<user>")
        t = IPV4.replace(t) { m -> if (m.value.startsWith("127.") || m.value == "0.0.0.0") m.value else "<ip>" }
        t = IPV6.replace(t, "<ip6>")
        return t
    }
}
