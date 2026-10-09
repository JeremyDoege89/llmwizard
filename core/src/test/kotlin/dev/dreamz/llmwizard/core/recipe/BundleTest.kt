package dev.dreamz.llmwizard.core.recipe

import dev.dreamz.llmwizard.core.Fixtures
import dev.dreamz.llmwizard.core.bundle.BundleResult
import dev.dreamz.llmwizard.core.bundle.BundleSigning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class BundleTest {
    private val root = Fixtures.root
    private fun bundle(version: Int) = BundleSigning.make(version, "2026-10-10T00:00:00Z",
        File(root, "recipes").listFiles()!!.filter { it.name.endsWith(".json") }.associate { it.nameWithoutExtension to it.readText() },
        File(root, "catalog/catalog.json").readText(), File(root, "catalog/snippets.json").readText()).toByteArray()

    private val current = BundleSigning.generateKeyPair()
    private val next = BundleSigning.generateKeyPair()
    private val keys = listOf(BundleSigning.pinned("current", current.second), BundleSigning.pinned("next", next.second))

    @Test fun `a signed bundle verifies and its recipes and catalog load`() {
        val b = bundle(3)
        val r = BundleSigning.verify(b, BundleSigning.sign(b, current.first), keys, minVersion = 2) as BundleResult.Accepted
        assertEquals("current", r.keyId)
        assertEquals("Install Ollama", r.bundle.recipe("ollama-install")!!.title)
        assertTrue(r.bundle.catalog().models.size >= 15)
    }

    @Test fun `the next key also verifies, so the key can be rotated`() {
        val b = bundle(3)
        assertEquals("next", (BundleSigning.verify(b, BundleSigning.sign(b, next.first), keys, 0) as BundleResult.Accepted).keyId)
    }

    @Test fun `tampering, an unknown key and rollback are all rejected`() {
        val b = bundle(5)
        val sig = BundleSigning.sign(b, current.first)
        val tampered = String(b).replaceFirst("apt-get", "apt-get -y purge openssh-server; apt-get").toByteArray()
        assertTrue(!tampered.contentEquals(b))
        assertTrue(BundleSigning.verify(tampered, sig, keys, 0) is BundleResult.Rejected)
        val stranger = BundleSigning.generateKeyPair()
        assertTrue(BundleSigning.verify(b, BundleSigning.sign(b, stranger.first), keys, 0) is BundleResult.Rejected)
        val old = BundleSigning.verify(b, sig, keys, minVersion = 6)
        assertTrue(old is BundleResult.Rejected && "older" in old.reason)
        assertTrue(BundleSigning.verify(b, "not base64!!", keys, 0) is BundleResult.Rejected)
    }
}
