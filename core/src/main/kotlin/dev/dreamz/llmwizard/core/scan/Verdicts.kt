package dev.dreamz.llmwizard.core.scan

import kotlinx.serialization.Serializable

@Serializable
enum class Severity {
    /** Worth knowing. */
    INFO,
    /** Works, but something is worse than it could be, with a fix the user can make. */
    WARN,
    /** The app cannot install on this machine. Recommendations still work. */
    BLOCK,
}

@Serializable
data class Verdict(val code: String, val severity: Severity, val message: String)

/** Short plain-English findings on top of a [HardwareProfile] (PLAN.md 5.4). */
object Verdicts {

    fun of(p: HardwareProfile): List<Verdict> = buildList {
        support(p)?.let(::add)
        p.cpu?.let { cpu(p, it) }?.let(::add)
        gpu(p).forEach(::add)
        p.cpu?.numaNodes?.takeIf { it > 1 }?.let {
            add(Verdict("numa", Severity.INFO,
                "This machine has $it memory nodes (one per processor). Models run, but speed depends on how much memory traffic crosses between them."))
        }
        p.container?.let {
            add(Verdict("container", Severity.INFO,
                "This machine is a container ($it). Ollama runs in containers, but GPUs are only visible if the host passes them through."))
        }
        memory(p)?.let(::add)
        p.ollama?.takeIf { it.installed }?.let {
            add(Verdict("ollama-present", Severity.INFO,
                "Ollama ${it.version ?: "(unknown version)"} is already installed at ${it.path}. The app will use it as it is and only add its own settings file."))
        }
        privilege(p)?.let(::add)
    }

    fun blocking(p: HardwareProfile) = of(p).filter { it.severity == Severity.BLOCK }

    /** Ubuntu 22.04+ or Debian 12+ on x86_64 with systemd. Null when supported. */
    fun support(p: HardwareProfile): Verdict? {
        val os = p.os
        val arch = os?.arch
        if (arch != null && arch != "x86_64") {
            return Verdict("arch", Severity.BLOCK, "This machine is $arch. Only 64-bit Intel and AMD (x86_64) machines are supported for now.")
        }
        if (p.systemd == false) {
            return Verdict("no-systemd", Severity.BLOCK,
                "This machine does not use systemd${os?.prettyName?.let { " ($it)" } ?: ""}. It is not supported yet. You can still use the recommendation, and install Ollama by hand.")
        }
        val id = os?.id
        val v = os?.versionId?.let(::version)
        val ok = when (id) {
            "ubuntu" -> v != null && v >= listOf(22, 4)
            "debian" -> v != null && v >= listOf(12)
            else -> false
        }
        if (ok) return null
        val what = os?.prettyName ?: os?.name ?: "This operating system"
        return when {
            os == null || id == null -> Verdict("os-unknown", Severity.BLOCK, "Could not tell which Linux this is. Only Ubuntu 22.04 or newer and Debian 12 or newer are supported.")
            id == "ubuntu" || id == "debian" -> Verdict("os-old", Severity.BLOCK, "$what is too old. Ubuntu 22.04 or newer, or Debian 12 or newer, is needed.")
            else -> Verdict("os-unsupported", Severity.BLOCK, "$what is not supported yet. Only Ubuntu 22.04+ and Debian 12+ are, for now.")
        }
    }

    private fun cpu(p: HardwareProfile, cpu: CpuInfo): Verdict? {
        val avx = cpu.has("avx") ?: return null
        val avx2 = cpu.has("avx2") ?: false
        val hyperV = p.virtualization == "microsoft" || cpu.hypervisorVendor.equals("Microsoft", true)
        return when {
            !avx && hyperV -> Verdict("hyperv-compat", Severity.WARN,
                "This is a Hyper-V virtual machine and AVX is not visible to it. Processor compatibility mode is probably switched on for this VM. " +
                    "Turning it off on the host (VM settings, Processor, Compatibility) will make models run several times faster.")
            !avx -> Verdict("no-avx", Severity.WARN,
                "This processor has no AVX. Local models still run, but very slowly. Only the smallest models will be usable.")
            !avx2 -> Verdict("avx-no-avx2", Severity.INFO,
                "This CPU has AVX but not AVX2. Local models work, slower than on newer chips.")
            else -> null
        }
    }

    private fun gpu(p: HardwareProfile): List<Verdict> = buildList {
        val nvidiaPci = p.gpus.any { it.vendor == GpuVendor.NVIDIA }
        when (p.nvidia?.smi) {
            SmiState.WORKING -> {
                val cards = p.nvidia.cards
                add(Verdict("nvidia-ok", Severity.INFO,
                    "NVIDIA ${cards.joinToString { it.name }} with a working driver (${cards.firstOrNull()?.driverVersion ?: "?"}). Models that fit in its memory will run on the GPU."))
            }
            SmiState.FAILING -> add(Verdict("nvidia-driver-broken", Severity.WARN,
                "There is an NVIDIA tool installed but the driver is not answering. Models will run on the CPU. " +
                    "Reboot first; if that does not fix it, reinstall the driver (on Ubuntu: sudo ubuntu-drivers install), then scan again."))
            else -> if (nvidiaPci) add(Verdict("nvidia-no-driver", Severity.WARN,
                "There is an NVIDIA card but no working driver. Models will run on the CPU. " +
                    "To use the card, install the driver yourself (on Ubuntu: sudo ubuntu-drivers install; on Debian: the nvidia-driver package from non-free), reboot, and scan again."))
        }
        if (p.gpus.any { it.vendor == GpuVendor.AMD }) add(Verdict("amd-gpu", Severity.INFO,
            "There is an AMD graphics card. It is not used yet, so models will run on the CPU."))
        if (p.gpus.any { it.vendor == GpuVendor.INTEL } && !nvidiaPci) add(Verdict("intel-gpu", Severity.INFO,
            "Intel graphics are not used for models yet. Models will run on the CPU."))
    }

    private fun memory(p: HardwareProfile): Verdict? {
        val total = p.memory?.totalBytes ?: return null
        val gb = total / 1e9
        return when {
            gb < 6 -> Verdict("low-ram", Severity.WARN, "This machine has %.1f GB of memory. Only very small models will fit.".format(gb))
            else -> null
        }
    }

    private fun privilege(p: HardwareProfile): Verdict? {
        val pr = p.privilege ?: return null
        if (pr.uid == 0) return null
        return when (pr.sudo) {
            "absent" -> Verdict("no-sudo", Severity.BLOCK,
                "sudo is not installed and you are not logged in as root, so the app cannot install anything. " +
                    "Either install sudo and add ${pr.user ?: "your user"} to the sudo group (as root: apt install sudo; adduser ${pr.user ?: "<user>"} sudo), or connect as root with a key.")
            "password-or-none" -> if (pr.groups.none { it == "sudo" || it == "admin" || it == "wheel" }) Verdict("maybe-no-sudo", Severity.WARN,
                "${pr.user ?: "This user"} is not in the sudo group, so installing may not be allowed. If the sudo password is refused, add the user to the sudo group as root (adduser ${pr.user ?: "<user>"} sudo) and log in again.")
            else null
            else -> null
        }
    }

    internal fun version(s: String): List<Int>? =
        s.split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

    private operator fun List<Int>.compareTo(other: List<Int>): Int {
        for (i in 0 until maxOf(size, other.size)) {
            val c = (getOrNull(i) ?: 0).compareTo(other.getOrNull(i) ?: 0)
            if (c != 0) return c
        }
        return 0
    }
}
