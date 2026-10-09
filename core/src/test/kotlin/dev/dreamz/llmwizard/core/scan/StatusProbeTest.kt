package dev.dreamz.llmwizard.core.scan

import dev.dreamz.llmwizard.core.server.StatusProbe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StatusProbeTest {
    @Test fun `parses a running server`() {
        val s = StatusProbe.parse("""
            @@ service
            active=active
            enabled=enabled
            since=Fri 2026-10-09 15:43:48 UTC
            env=PATH=/usr/bin OLLAMA_HOST=127.0.0.1:11434 OLLAMA_CONTEXT_LENGTH=8192
            version=0.40.2
            @@ load
            0.88 0.50 0.40 1/500 1234
            nproc=40
            @@ meminfo
            MemTotal:       128894720 kB
            MemAvailable:   127000000 kB
            @@ disk
            /usr/share/ollama/.ollama/models /dev/sda1 524875522048 200000000000 315203162112 39% /
            @@ log
            2026-10-09T15:43:48 wiz-debian ollama[1]: Listening on 127.0.0.1:11434
            @@ end
        """.trimIndent())
        assertEquals("active", s.active)
        assertEquals("127.0.0.1:11434", s.bind)
        assertEquals("0.40.2", s.ollamaVersion)
        assertEquals(0.88, s.load1)
        assertEquals(40, s.cpus)
        assertEquals(315203162112, s.diskFreeBytes)
        assertEquals(1, s.log.size)
    }

    @Test fun `unknown stays unknown and an unreadable journal is reported`() {
        val s = StatusProbe.parse("@@ service\nactive=inactive\n@@ log\n(this user cannot read the system journal)\n")
        assertEquals("inactive", s.active)
        assertNull(s.memTotalBytes)
        assertFalse(s.logReadable)
    }
}
