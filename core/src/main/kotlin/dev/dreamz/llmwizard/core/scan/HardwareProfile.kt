package dev.dreamz.llmwizard.core.scan

import kotlinx.serialization.Serializable

/**
 * What the scan found. Every field is optional: any probe can be missing or fail, and
 * "unknown" (null) is never treated as zero (SPEC.md rule 6).
 */
@Serializable
data class HardwareProfile(
    val os: OsInfo? = null,
    val systemd: Boolean? = null,
    val virtualization: String? = null,
    val container: String? = null,
    val cpu: CpuInfo? = null,
    val memory: MemoryInfo? = null,
    val gpus: List<PciGpu> = emptyList(),
    val nvidia: NvidiaInfo? = null,
    val disk: DiskInfo? = null,
    val ollama: OllamaInfo? = null,
    val network: NetworkInfo? = null,
    val firewall: FirewallInfo? = null,
    val tools: Map<String, Boolean> = emptyMap(),
    val privilege: PrivilegeInfo? = null,
    /** Set when the profile was typed in by hand rather than scanned. */
    val manual: Boolean = false,
)

@Serializable
data class OsInfo(
    val id: String? = null,
    val idLike: List<String> = emptyList(),
    val versionId: String? = null,
    val prettyName: String? = null,
    val name: String? = null,
    val arch: String? = null,
    val kernel: String? = null,
)

@Serializable
data class CpuInfo(
    val model: String? = null,
    val vendor: String? = null,
    val sockets: Int? = null,
    val coresPerSocket: Int? = null,
    val threadsPerCore: Int? = null,
    val logicalCpus: Int? = null,
    /** Only the flags that matter for inference: avx, avx2, avx512f, fma, f16c, avx_vnni. */
    val flags: Set<String>? = null,
    val numaNodes: Int? = null,
    val hypervisorVendor: String? = null,
    val family: Int? = null,
    val modelNumber: Int? = null,
) {
    val physicalCores: Int? get() = if (sockets != null && coresPerSocket != null) sockets * coresPerSocket else null
    fun has(flag: String): Boolean? = flags?.contains(flag)
}

@Serializable
data class MemoryInfo(val totalBytes: Long? = null, val availableBytes: Long? = null, val swapTotalBytes: Long? = null)

@Serializable
data class PciGpu(val address: String, val vendorId: String?, val deviceId: String?, val classCode: String?, val driver: String?) {
    val vendor: GpuVendor get() = when (vendorId?.lowercase()) {
        "0x10de" -> GpuVendor.NVIDIA
        "0x1002" -> GpuVendor.AMD
        "0x8086" -> GpuVendor.INTEL
        "0x1414" -> GpuVendor.MICROSOFT_VIRTUAL
        else -> GpuVendor.OTHER
    }
}

enum class GpuVendor { NVIDIA, AMD, INTEL, MICROSOFT_VIRTUAL, OTHER }

@Serializable
data class NvidiaInfo(
    /** ABSENT: no nvidia-smi. FAILING: present but errored (driver not loaded). WORKING: answered. */
    val smi: SmiState,
    val cards: List<NvidiaCard> = emptyList(),
    val error: String? = null,
)

enum class SmiState { ABSENT, FAILING, WORKING }

@Serializable
data class NvidiaCard(val name: String, val vramMiB: Long?, val driverVersion: String?)

@Serializable
data class DiskInfo(val root: Filesystem? = null, val models: List<Pair<String, Filesystem>> = emptyList())

@Serializable
data class Filesystem(val device: String, val totalBytes: Long?, val usedBytes: Long?, val freeBytes: Long?, val mount: String)

@Serializable
data class OllamaInfo(
    val path: String? = null,
    val version: String? = null,
    val serviceActive: String? = null,
    val serviceEnabled: String? = null,
    val serviceFragment: String? = null,
    val serviceDropIns: List<String> = emptyList(),
    val serviceEnvironment: String? = null,
    val apiVersion: String? = null,
    val manifestPresent: Boolean? = null,
) {
    val installed: Boolean get() = !path.isNullOrBlank()
}

@Serializable
data class NetworkInfo(val addresses: List<IfAddress> = emptyList(), val listening: List<String> = emptyList())

@Serializable
data class IfAddress(val iface: String, val cidr: String)

@Serializable
data class FirewallInfo(val ufwPresent: Boolean? = null, val ufwEnabled: Boolean? = null, val firewalld: Boolean? = null, val tailscale: Boolean? = null)

@Serializable
data class PrivilegeInfo(val uid: Int? = null, val user: String? = null, val groups: List<String> = emptyList(), val sudo: String? = null, val shell: String? = null)
