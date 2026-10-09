package dev.dreamz.llmwizard.core.ssh

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One server's connection that comes back by itself (rule 4). [session] hands out the live
 * session or reconnects; nothing is retried automatically, because a command that may have run
 * must not run twice. Callers that hit [SshError.Disconnected] just ask for [session] again.
 */
class Connection(
    private val transport: SshTransport,
    val target: Target,
    private val hostKeys: HostKeyPolicy,
    /** Asked on every (re)connect, so a decrypted key or password lives only as long as needed. */
    private val auth: suspend () -> Auth,
) : AutoCloseable {
    private val lock = Mutex()
    @Volatile private var current: SshSession? = null
    var reconnects = 0
        private set

    suspend fun session(): SshSession = lock.withLock {
        current?.takeIf { it.isConnected }?.let { return it }
        current?.close()
        val a = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { auth() }
        try {
            transport.connect(target, a, hostKeys).also { if (current != null) reconnects++; current = it }
        } finally {
            (a as? Auth.Password)?.wipe()
        }
    }

    /** Runs [block] with a live session; on a dropped connection, reconnects once for the next caller. */
    suspend fun <T> use(block: suspend (SshSession) -> T): T {
        val s = session()
        return try { block(s) } catch (e: SshError.Disconnected) { lock.withLock { current?.close(); current = null }; throw e }
    }

    override fun close() { current?.close(); current = null }
}
