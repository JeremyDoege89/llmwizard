package dev.dreamz.llmwizard.core.scan

import dev.dreamz.llmwizard.core.Fixtures
import dev.dreamz.llmwizard.core.recipe.TranscriptEntry
import dev.dreamz.llmwizard.core.support.Diagnostics
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticsTest {
    @Test fun `report redacts host, user and addresses by default`() {
        val t = listOf(TranscriptEntry("2026-10-10T10:00:00Z", "server-1", "step settings", "...", 1,
            "install: cannot create /home/dreamz/x: denied while connecting to 192.168.1.161 from fe80::1:2:3:4 (bind 127.0.0.1:11434) as dreamz on wiz-box"))
        val r = Diagnostics.report(Diagnostics.Input("0.1.0", "15", Fixtures.profile("dl380p-hyperv-ubuntu2404"), null, t, "wiz-box", "dreamz"))
        for (secret in listOf("192.168.1.161", "dreamz", "wiz-box", "fe80::1:2:3:4")) assertFalse(secret in r, "leaked $secret:\n$r")
        assertTrue("127.0.0.1:11434" in r)
        assertTrue("E5-2680" in r)
        assertTrue("verdict INFO avx-no-avx2" in r)
    }
}
