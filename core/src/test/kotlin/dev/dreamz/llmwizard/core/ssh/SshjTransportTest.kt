package dev.dreamz.llmwizard.core.ssh

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class SshjTransportTest {
    private val server = TestSshServer()
    private val transport = SshjTransport()
    private val store = MemoryHostKeyStore()

    @AfterEach fun tearDown() = server.close()

    private fun password() = Auth.Password(server.password.toCharArray())

    @Test fun `password login runs a command and reports the exit code`() = runBlocking {
        transport.connect(server.target(), password(), HostKeyPolicy(store, acceptNew = true)).use { s ->
            val r = s.exec("/bin/sh -s", "echo hello; exit 3".toByteArray(), 10.seconds)
            assertEquals(3, r.exitCode)
            assertEquals("hello\n", r.out)
        }
    }

    @Test fun `fingerprint matches the server's own computation`() = runBlocking {
        transport.connect(server.target(), password(), HostKeyPolicy(store, acceptNew = true)).use { s ->
            assertEquals(server.minaFingerprint, s.hostKey.fingerprint)
            assertEquals(server.minaFingerprint, store.get("127.0.0.1", server.port)!!.fingerprint)
        }
    }

    @Test fun `wrong password is AuthFailed`() = runBlocking {
        val e = assertThrows<SshError.AuthFailed> {
            runBlocking { transport.connect(server.target(), Auth.Password("nope".toCharArray()), HostKeyPolicy(store, true)) }
        }
        assertEquals("wiz", e.user)
    }

    @Test fun `generated ed25519 key logs in`() = runBlocking {
        val key = Keys.generateEd25519("llmwizard-test")
        server.allowedKeys += key.fingerprint
        transport.connect(server.target(), Auth.PrivateKey(key.privateKeyOpenSsh), HostKeyPolicy(store, true)).use { s ->
            assertEquals("ok\n", s.exec("/bin/sh -s", "echo ok".toByteArray(), 10.seconds).out)
        }
    }

    @Test fun `unknown host without acceptNew fails with the fingerprint and stores nothing`() {
        val e = assertThrows<SshError.HostKeyUnknown> {
            runBlocking { transport.connect(server.target(), password(), HostKeyPolicy(store, acceptNew = false)) }
        }
        assertEquals(server.minaFingerprint, e.key.fingerprint)
        assertEquals(null, store.get("127.0.0.1", server.port))
    }

    @Test fun `changed host key refuses to connect`() {
        store.put("127.0.0.1", server.port, KnownHost("ecdsa-sha2-nistp256", "SHA256:somethingelse"))
        assertThrows<SshError.HostKeyChanged> {
            runBlocking { transport.connect(server.target(), password(), HostKeyPolicy(store, acceptNew = true)) }
        }
    }

    @Test fun `stdin reaches the command and is closed afterwards`() = runBlocking {
        transport.connect(server.target(), password(), HostKeyPolicy(store, true)).use { s ->
            val r = s.exec("cat", "line one\nline two\n".toByteArray(), 10.seconds)
            assertEquals("line one\nline two\n", r.out)
        }
    }

    @Test fun `timeout closes the channel and throws Timeout`() = runBlocking {
        transport.connect(server.target(), password(), HostKeyPolicy(store, true)).use { s ->
            val t0 = System.nanoTime()
            assertThrows<SshError.Timeout> { runBlocking { s.exec("sleep 30", null, 1.seconds) } }
            assertTrue((System.nanoTime() - t0) / 1e9 < 10, "should not wait for the command")
            // The session is still usable afterwards.
            assertEquals(0, s.exec("true", null, 10.seconds).exitCode)
        }
    }

    @Test fun `unreachable port is Unreachable`() {
        val freePort = ServerSocket(0).use { it.localPort }
        assertThrows<SshError.Unreachable> {
            runBlocking { transport.connect(Target("127.0.0.1", freePort, "wiz"), password(), HostKeyPolicy(store, true)) }
        }
    }

    @Test fun `direct-tcpip carries bytes both ways`() = runBlocking {
        val echo = ServerSocket(0)
        thread(isDaemon = true) {
            echo.accept().use { c -> val b = ByteArray(5); c.getInputStream().read(b); c.getOutputStream().write(b.reversedArray()) }
        }
        transport.connect(server.target(), password(), HostKeyPolicy(store, true)).use { s ->
            s.openDirect("127.0.0.1", echo.localPort).use { d ->
                d.output.write("abcde".toByteArray()); d.output.flush()
                val b = ByteArray(5)
                var n = 0
                while (n < 5) n += d.input.read(b, n, 5 - n)
                assertEquals("edcba", String(b))
            }
        }
        echo.close()
    }
}

class ConnectionTest {
    @Test fun `a dropped session is replaced on the next call`() = runBlocking {
        TestSshServer().use { server ->
            val c = Connection(SshjTransport(), server.target(), HostKeyPolicy(MemoryHostKeyStore(), true)) { Auth.Password(server.password.toCharArray()) }
            val first = c.session()
            assertEquals(0, first.exec("true", null, 10.seconds).exitCode)
            first.close() // the phone changed networks
            val second = c.session()
            assertTrue(second !== first)
            assertEquals(0, second.exec("true", null, 10.seconds).exitCode)
            assertEquals(1, c.reconnects)
            c.close()
        }
    }
}
