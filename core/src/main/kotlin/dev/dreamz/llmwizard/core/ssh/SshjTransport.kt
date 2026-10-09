package dev.dreamz.llmwizard.core.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * [SshTransport] over sshj. sshj blocks, so every call runs on the IO dispatcher, and
 * cancellation closes the channel because nothing else unblocks a read (PLAN.md 5.1).
 */
class SshjTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val keepAliveSeconds: Int = 15,
    /** Lets the Android app adjust the sshj config (security provider quirks). */
    private val configure: (DefaultConfig) -> Unit = {},
) : SshTransport {

    override suspend fun connect(target: Target, auth: Auth, hostKeys: HostKeyPolicy): SshSession =
        withContext(Dispatchers.IO) {
            val config = DefaultConfig().also {
                it.keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
                configure(it)
            }
            val client = SSHClient(config)
            val verifier = TofuVerifier(target.host, hostKeys)
            client.addHostKeyVerifier(verifier)
            client.connectTimeout = connectTimeoutMs
            client.timeout = 0 // reads are bounded per call, not per socket
            try {
                try {
                    client.connect(target.host, target.port)
                } catch (e: TransportException) {
                    verifier.failure?.let { throw it }
                    throw e
                }
                client.connection.keepAlive.keepAliveInterval = keepAliveSeconds
                (client.connection.keepAlive as? net.schmizz.keepalive.KeepAliveRunner)?.maxAliveCount = 4
                when (auth) {
                    is Auth.Password -> client.authPassword(target.user, auth.password)
                    is Auth.PrivateKey -> client.authPublickey(target.user, client.loadKeys(auth.openSshPrivateKey, null, null))
                }
                SshjSession(target, client, verifier.seen!!)
            } catch (e: Throwable) {
                runCatching { client.close() }
                throw map(e, target)
            }
        }

    private class TofuVerifier(private val host: String, private val policy: HostKeyPolicy) : HostKeyVerifier {
        var failure: SshError? = null
        var seen: KnownHost? = null

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val actual = KnownHost(KeyType.fromKey(key).toString(), fingerprint(key))
            seen = actual
            val stored = policy.store.get(hostname, port)
            return when {
                stored == null && policy.acceptNew -> { policy.store.put(hostname, port, actual); true }
                stored == null -> { failure = SshError.HostKeyUnknown(host, actual); false }
                stored.fingerprint == actual.fingerprint -> true
                else -> { failure = SshError.HostKeyChanged(host, stored, actual); false }
            }
        }

        /** Asks the server for the key type we stored, so negotiation cannot pick a different one. */
        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
            val type = policy.store.get(hostname, port)?.keyType ?: return emptyList()
            return if (type == "ssh-rsa") listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa") else listOf(type)
        }
    }

    companion object {
        /** SHA256 fingerprint in OpenSSH's format, comparable with `ssh-keygen -lf`. */
        fun fingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

        internal fun map(e: Throwable, target: Target): Throwable = when (e) {
            is SshError, is CancellationException -> e
            is UserAuthException -> SshError.AuthFailed(target.user, e)
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> SshError.Unreachable(target.host, e)
            is SocketTimeoutException -> SshError.Timeout("connecting to ${target.host}", e)
            is TransportException -> if (e.cause is SocketTimeoutException) SshError.Timeout("talking to ${target.host}", e)
                else SshError.Disconnected(e)
            is IOException -> SshError.Disconnected(e)
            else -> e
        }
    }
}

private class SshjSession(
    override val target: Target,
    private val client: SSHClient,
    override val hostKey: KnownHost,
) : SshSession {

    override val isConnected: Boolean get() = client.isConnected && client.isAuthenticated

    // Main-safe: every sshj call here blocks, so the whole body runs on the IO dispatcher.
    override suspend fun exec(command: String, stdin: ByteArray?, timeout: Duration): ExecResult = try {
        withContext(Dispatchers.IO) { withTimeout(timeout) {
            val session = client.startSession()
            closingOnCancel(session) {
                val cmd = session.exec(command)
                coroutineScope {
                    val out = async(Dispatchers.IO) { readCapped(cmd.inputStream) }
                    val err = async(Dispatchers.IO) { readCapped(cmd.errorStream) }
                    withContext(Dispatchers.IO) {
                        cmd.outputStream.use { os -> stdin?.let { os.write(it); os.flush() } }
                    }
                    val (o, oTrunc) = out.await()
                    val (e, eTrunc) = err.await()
                    withContext(Dispatchers.IO) { cmd.join(30, TimeUnit.SECONDS) }
                    val code = cmd.exitStatus ?: if (cmd.exitSignal != null) 128 + 15 else -1
                    ExecResult(code, o, e, oTrunc || eTrunc)
                }
            }
        } }
    } catch (e: TimeoutCancellationException) {
        throw SshError.Timeout("running a command on ${target.name}", e)
    } catch (e: Throwable) {
        throw SshjTransport.map(e, target)
    }

    override suspend fun openDirect(host: String, port: Int): DuplexStream = try {
        withContext(Dispatchers.IO) {
            val ch = client.newDirectConnection(host, port)
            object : DuplexStream {
                override val input: InputStream = ch.inputStream
                override val output = ch.outputStream
                override fun close() { runCatching { ch.close() } }
            }
        }
    } catch (e: Throwable) {
        throw SshjTransport.map(e, target)
    }

    override fun close() {
        runCatching { client.disconnect() }
        runCatching { client.close() }
    }
}

/** At most [cap] bytes are kept; the rest is drained and dropped so the remote side never blocks. */
private fun readCapped(input: InputStream, cap: Int = 8 * 1024 * 1024): Pair<ByteArray, Boolean> {
    val buf = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    var truncated = false
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        val room = cap - buf.size()
        if (room > 0) buf.write(chunk, 0, minOf(n, room))
        if (n > room) truncated = true
    }
    return buf.toByteArray() to truncated
}

/**
 * Runs [block]; the moment the coroutine is cancelled (or times out), closes [closeable].
 * A thread blocked in an sshj read only wakes up when its channel closes, and a plain
 * try/catch would never get the chance: the scope waits for that blocked child first.
 */
internal suspend fun <T> closingOnCancel(closeable: Closeable, block: suspend CoroutineScope.() -> T): T = try {
    coroutineScope {
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { runCatching { closeable.close() } }
        }
        try { block() } finally { watcher.cancel() }
    }
} finally {
    runCatching { closeable.close() }
}
