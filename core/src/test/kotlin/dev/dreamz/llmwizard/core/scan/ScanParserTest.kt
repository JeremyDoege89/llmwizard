package dev.dreamz.llmwizard.core.scan

import dev.dreamz.llmwizard.core.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScanParserTest {

    @Test fun `DL380p Hyper-V guest`() {
        val p = Fixtures.profile("dl380p-hyperv-ubuntu2404")
        assertEquals("ubuntu", p.os?.id)
        assertEquals("24.04", p.os?.versionId)
        assertEquals("x86_64", p.os?.arch)
        assertEquals(true, p.systemd)
        assertEquals("microsoft", p.virtualization)
        val cpu = p.cpu!!
        assertEquals(2, cpu.sockets)
        assertEquals(10, cpu.coresPerSocket)
        assertEquals(20, cpu.physicalCores)
        assertEquals(40, cpu.logicalCpus)
        assertEquals(2, cpu.numaNodes)
        assertEquals(true, cpu.has("avx"))
        assertEquals(false, cpu.has("avx2"))
        assertEquals(false, cpu.has("fma"))
        assertTrue(p.memory!!.totalBytes!! > 100_000_000_000)
        assertEquals(SmiState.ABSENT, p.nvidia?.smi)
        assertTrue(p.disk!!.root!!.freeBytes!! > 0)
        assertFalse(p.ollama!!.installed)
        assertEquals(false, p.firewall?.ufwEnabled)
        assertEquals("nopasswd", p.privilege?.sudo)
        assertTrue(p.network!!.addresses.any { it.iface == "eth0" })
    }

    @Test fun `verdicts on the DL380p say AVX without AVX2 and two NUMA nodes`() {
        val v = Verdicts.of(Fixtures.profile("dl380p-hyperv-ubuntu2404")).map { it.code }
        assertTrue("avx-no-avx2" in v, v.toString())
        assertTrue("numa" in v)
        assertTrue(Verdicts.blocking(Fixtures.profile("dl380p-hyperv-ubuntu2404")).isEmpty())
    }

    @Test fun `debian container without curl`() {
        val p = Fixtures.profile("lxd-debian12-on-dl380p")
        assertEquals("debian", p.os?.id)
        assertEquals(false, p.tools["curl"])
        assertEquals("lxc", p.container)
        assertNull(Verdicts.support(p))
    }

    @Test fun `NVIDIA laptop with a working driver`() {
        val p = Fixtures.profile("synthetic-laptop-rtx4060")
        assertEquals(SmiState.WORKING, p.nvidia?.smi)
        assertEquals(8188L, p.nvidia!!.cards.single().vramMiB)
        assertTrue("nvidia-ok" in Verdicts.of(p).map { it.code })
    }

    @Test fun `NVIDIA card without a driver is explained, not installed`() {
        val v = Verdicts.of(Fixtures.profile("synthetic-nvidia-no-driver"))
        assertEquals(Severity.WARN, v.single { it.code == "nvidia-no-driver" }.severity)
    }

    @Test fun `MX Linux on SysV init is reported as not supported`() {
        val v = Verdicts.support(Fixtures.profile("synthetic-mx-sysvinit"))!!
        assertEquals("no-systemd", v.code)
        assertEquals(Severity.BLOCK, v.severity)
    }

    @Test fun `Hyper-V with compatibility mode on is called out`() {
        val v = Verdicts.of(Fixtures.profile("synthetic-hyperv-compat-mode"))
        assertTrue(v.any { it.code == "hyperv-compat" && "compatibility mode" in it.message })
    }

    @Test fun `tiny machine without sudo is blocked with a way forward`() {
        val v = Verdicts.of(Fixtures.profile("synthetic-tiny-4gb"))
        assertTrue(v.any { it.code == "no-sudo" && it.severity == Severity.BLOCK })
        assertTrue(v.any { it.code == "no-avx" })
        assertTrue(v.any { it.code == "low-ram" })
    }

    @Test fun `existing Ollama is recognized for adoption`() {
        val p = Fixtures.profile("synthetic-ollama-adopt")
        assertEquals("/usr/local/bin/ollama", p.ollama?.path)
        assertEquals("0.12.3", p.ollama?.version)
        assertEquals("0.12.3", p.ollama?.apiVersion)
        assertEquals(listOf("/etc/systemd/system/ollama.service.d/override.conf"), p.ollama?.serviceDropIns)
        assertNull(Verdicts.support(p))
    }

    @Test fun `truncated output parses what is there and leaves the rest unknown`() {
        val p = Fixtures.profile("synthetic-truncated")
        assertEquals("ubuntu", p.os?.id)
        assertNull(p.memory)
        assertNull(p.privilege)
        Verdicts.of(p) // must not throw
    }

    @Test fun `every fixture parses without throwing`() {
        for (name in Fixtures.all()) {
            val p = Fixtures.profile(name)
            Verdicts.of(p)
        }
    }

    @Test fun `empty and garbage input give an all-unknown profile`() {
        assertEquals(HardwareProfile(), ScanParser.parse(""))
        val p = ScanParser.parse("@@ meminfo\nMemTotal: lots\n@@ lscpu\nnonsense\n@@ unknown-section\nx=y")
        assertNull(p.memory?.totalBytes)
        assertNull(p.cpu?.sockets)
    }

    @Test fun `scan script is read-only by inspection`() {
        val s = ScanParser.script
        for (bad in listOf("apt", "rm ", "> /", "tee ", "mkdir", "sudo -S", "systemctl start", "systemctl stop")) {
            assertFalse(bad in s, "scan.sh must not contain '$bad'")
        }
    }
}
