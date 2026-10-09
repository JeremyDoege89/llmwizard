package dev.dreamz.llmwizard.core.ssh

import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import java.nio.file.Files
import java.security.KeyPair
import java.security.PublicKey

/**
 * An SSH server inside the test process. Commands run as real local processes, so exec,
 * stdin, exit codes and timeouts behave as they would on a target.
 */
class TestSshServer(
    val user: String = "wiz",
    val password: String = "secret",
    /** Fingerprints of public keys allowed to log in. */
    val allowedKeys: MutableSet<String> = mutableSetOf(),
    hostKeyAlgorithm: String = "EC",
) : AutoCloseable {
    private val keyFile = Files.createTempFile("hostkey", ".ser").also { Files.delete(it) }
    val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile).apply { algorithm = hostKeyAlgorithm }
        setPasswordAuthenticator { u, p, _ -> u == user && p == password }
        setPublickeyAuthenticator { u, key, _ -> u == user && SshjTransport.fingerprint(key) in allowedKeys }
        commandFactory = ProcessShellCommandFactory.INSTANCE
        forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        start()
    }

    val port: Int get() = server.port

    val hostPublicKey: PublicKey
        get() = server.keyPairProvider.loadKeys(null).first().public

    /** Fingerprint as MINA computes it — independent of our own implementation. */
    val minaFingerprint: String get() = KeyUtils.getFingerPrint(hostPublicKey)

    fun target() = Target(host = "127.0.0.1", port = port, user = user)

    override fun close() {
        server.stop(true)
        Files.deleteIfExists(keyFile)
    }
}
