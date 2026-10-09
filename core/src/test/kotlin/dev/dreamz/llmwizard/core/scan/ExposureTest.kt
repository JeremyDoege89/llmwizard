package dev.dreamz.llmwizard.core.scan

import dev.dreamz.llmwizard.core.Fixtures
import dev.dreamz.llmwizard.core.exposure.AddressKind
import dev.dreamz.llmwizard.core.exposure.Exposure
import dev.dreamz.llmwizard.core.exposure.Ipv4
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExposureTest {
    @Test fun `address kinds`() {
        assertEquals(AddressKind.PRIVATE, Ipv4.kind("192.168.1.161"))
        assertEquals(AddressKind.PRIVATE, Ipv4.kind("172.20.0.5"))
        assertEquals(AddressKind.PUBLIC, Ipv4.kind("172.32.0.5"))
        assertEquals(AddressKind.CGNAT, Ipv4.kind("100.100.100.1"))
        assertEquals(AddressKind.LINK_LOCAL, Ipv4.kind("169.254.3.3"))
        assertEquals(AddressKind.PUBLIC, Ipv4.kind("203.0.113.7"))
        assertEquals("192.168.1.0/24", Ipv4.network("192.168.1.161", 24))
        assertEquals("10.138.76.0/24", Ipv4.network("10.138.76.29", 24))
    }

    @Test fun `private LAN machine offers its LAN address and the wide bind`() {
        val s = Exposure.state(Fixtures.profile("dl380p-hyperv-ubuntu2404"), null)
        assertTrue(s.isPrivate)
        val opts = Exposure.options(s)
        assertTrue(opts.any { it.bind == "192.168.1.161:11434" && it.allowed && it.firewallCidr == "192.168.1.0/24" })
        // The Tailscale address is CGNAT, not offered as a LAN bind.
        assertFalse(opts.any { it.bind.startsWith("100.") })
        assertTrue(opts.single { it.bind.startsWith("0.0.0.0") }.allowed)
    }

    @Test fun `a public address makes the app refuse the wide bind`() {
        val p = Fixtures.profile("synthetic-public-address")
        val s = Exposure.state(p, null)
        assertEquals(1, s.publicAddresses.size)
        val wide = Exposure.options(s).single { it.bind.startsWith("0.0.0.0") }
        assertFalse(wide.allowed)
        assertTrue("public address" in wide.reason)
        assertTrue(Exposure.options(s).any { it.bind == "192.168.10.5:11434" && it.allowed })
    }

    @Test fun `warnings say there is no password and no firewall`() {
        val s = Exposure.state(Fixtures.profile("dl380p-hyperv-ubuntu2404"), "192.168.1.161:11434")
        val w = Exposure.warnings(s)
        assertTrue(w.any { "no password" in it })
        assertTrue(w.any { "No firewall" in it })
        assertEquals(listOf("http://192.168.1.161:11434"), Exposure.urls(s))
        assertTrue(Exposure.warnings(Exposure.state(Fixtures.profile("dl380p-hyperv-ubuntu2404"), null)).isEmpty())
    }
}
