package dev.dreamz.llmwizard.core.exposure

import dev.dreamz.llmwizard.core.scan.HardwareProfile
import dev.dreamz.llmwizard.core.scan.IfAddress
import kotlinx.serialization.Serializable

@Serializable enum class AddressKind { LOOPBACK, PRIVATE, CGNAT, LINK_LOCAL, PUBLIC }

@Serializable
data class Iface(val name: String, val address: String, val prefix: Int, val kind: AddressKind) {
    /** The network this address is on, for a firewall rule: 192.168.1.0/24. */
    val network: String get() = Ipv4.network(address, prefix)
}

@Serializable
data class ExposureState(
    val bind: String,
    val interfaces: List<Iface>,
    val ufwActive: Boolean?,
    val firewalldPresent: Boolean,
    val tailscalePresent: Boolean,
) {
    val publicAddresses get() = interfaces.filter { it.kind == AddressKind.PUBLIC }
    val isPrivate get() = bind.startsWith("127.") || bind.startsWith("localhost")
}

/** One address the user may bind Ollama to, with why (PLAN.md 5.9). */
@Serializable
data class BindOption(val bind: String, val label: String, val allowed: Boolean, val reason: String, val firewallCidr: String?)

object Ipv4 {
    fun parse(a: String): Long? {
        val p = a.split('.')
        if (p.size != 4) return null
        return p.fold(0L) { acc, s -> val n = s.toIntOrNull() ?: return null; if (n !in 0..255) return null; acc * 256 + n }
    }

    fun format(v: Long) = listOf(24, 16, 8, 0).joinToString(".") { ((v shr it) and 255).toString() }

    fun network(a: String, prefix: Int): String {
        val v = parse(a) ?: return "$a/$prefix"
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return format(v and mask) + "/" + prefix
    }

    private fun inRange(v: Long, base: String, prefix: Int): Boolean {
        val b = parse(base)!!
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (v and mask) == (b and mask)
    }

    fun kind(a: String): AddressKind {
        val v = parse(a) ?: return AddressKind.PUBLIC
        return when {
            inRange(v, "127.0.0.0", 8) -> AddressKind.LOOPBACK
            inRange(v, "10.0.0.0", 8) || inRange(v, "172.16.0.0", 12) || inRange(v, "192.168.0.0", 16) -> AddressKind.PRIVATE
            inRange(v, "100.64.0.0", 10) -> AddressKind.CGNAT
            inRange(v, "169.254.0.0", 16) -> AddressKind.LINK_LOCAL
            else -> AddressKind.PUBLIC
        }
    }
}

/**
 * Exposure guardrails (PLAN.md 5.9). Pure functions over what the scan saw, so every rule is
 * unit-tested with fixtures, including one with a public address.
 */
object Exposure {
    const val PORT = 11434

    fun state(p: HardwareProfile, currentBind: String?): ExposureState = ExposureState(
        bind = currentBind ?: "127.0.0.1:$PORT",
        interfaces = p.network?.addresses.orEmpty().mapNotNull(::iface),
        ufwActive = p.firewall?.ufwEnabled,
        firewalldPresent = p.firewall?.firewalld == true,
        tailscalePresent = p.firewall?.tailscale == true,
    )

    private fun iface(a: IfAddress): Iface? {
        val addr = a.cidr.substringBefore('/')
        val prefix = a.cidr.substringAfter('/', "32").toIntOrNull() ?: 32
        Ipv4.parse(addr) ?: return null
        return Iface(a.iface, addr, prefix, Ipv4.kind(addr))
    }

    /** The choices for "home network" mode, each with a plain reason. */
    fun options(s: ExposureState): List<BindOption> = buildList {
        val public = s.publicAddresses
        for (i in s.interfaces.filter { it.kind == AddressKind.PRIVATE }) {
            add(BindOption("${i.address}:$PORT", "${i.name} (${i.address})", true,
                "Devices on ${i.network} can use the model." + if (s.ufwActive == true) " The firewall will allow port $PORT from ${i.network} only." else "",
                i.network))
        }
        add(if (public.isEmpty()) BindOption("0.0.0.0:$PORT", "Every network this machine is on", true,
                "Anything that can reach this machine on any of its networks can use the model. Prefer a single network above.", null)
            else BindOption("0.0.0.0:$PORT", "Every network this machine is on", false,
                "Not offered: ${public.joinToString { "${it.name} has the public address ${it.address}" }}, so this would put the model on the internet with no password.", null))
    }

    /** Plain-language warnings about the current state. */
    fun warnings(s: ExposureState): List<String> = buildList {
        if (!s.isPrivate) add("Ollama has no password. Anything that can reach ${s.bind} can use the model and download or delete models.")
        if (!s.isPrivate && s.ufwActive != true) add("No firewall is active on this machine, so nothing limits who on the network can connect. The app does not turn a firewall on for you, because doing that over SSH can lock you out.")
        if (s.bind.startsWith("0.0.0.0") && s.publicAddresses.isNotEmpty()) add("Ollama is listening on every network, including the public address ${s.publicAddresses.first().address}. Go back to private now.")
        if (!s.isPrivate && !s.bind.startsWith("0.0.0.0")) add("If this machine gets a new address from the router, other devices will lose the connection until you choose the network again.")
    }

    /** The URLs other apps use. Any non-empty API key works with Ollama's OpenAI-compatible path. */
    fun urls(s: ExposureState): List<String> {
        val hosts = when {
            s.isPrivate -> emptyList()
            s.bind.startsWith("0.0.0.0") -> s.interfaces.filter { it.kind != AddressKind.LOOPBACK && it.kind != AddressKind.PUBLIC }.map { it.address }
            else -> listOf(s.bind.substringBeforeLast(':'))
        }
        val port = s.bind.substringAfterLast(':')
        return hosts.map { "http://$it:$port" }
    }
}
