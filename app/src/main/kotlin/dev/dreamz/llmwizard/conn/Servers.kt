package dev.dreamz.llmwizard.conn

import dev.dreamz.llmwizard.core.server.Server
import dev.dreamz.llmwizard.core.ssh.Auth
import dev.dreamz.llmwizard.core.ssh.Connection
import dev.dreamz.llmwizard.core.ssh.HostKeyPolicy
import dev.dreamz.llmwizard.core.ssh.SshError
import dev.dreamz.llmwizard.core.ssh.SshjTransport
import dev.dreamz.llmwizard.core.ssh.Target
import dev.dreamz.llmwizard.data.AppDb
import dev.dreamz.llmwizard.data.AuthType
import dev.dreamz.llmwizard.data.KeyVault
import dev.dreamz.llmwizard.data.RoomHostKeyStore
import dev.dreamz.llmwizard.data.RoomResumeStore
import dev.dreamz.llmwizard.data.RoomTranscript
import dev.dreamz.llmwizard.data.ServerEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Live connections, one per server row. A server that logs in with a password needs it on
 * every (re)connect; it is passed in for that call and never stored.
 */
class Servers(val db: AppDb, val vault: KeyVault) {
    private val connections = ConcurrentHashMap<Long, Connection>()
    val hostKeys = RoomHostKeyStore(db.servers())
    val resume = RoomResumeStore(db.jobs())
    val transcript = RoomTranscript(db.transcript())

    fun target(s: ServerEntity) = Target(s.host, s.port, s.user, name = "server-${s.id}")

    /**
     * A connected [Server] for [s]. Unknown host keys fail with [SshError.HostKeyUnknown] so the
     * UI can show the fingerprint; [acceptHostKey] stores it after the user has looked.
     */
    suspend fun open(s: ServerEntity, password: CharArray? = null): Server {
        val conn = connections.getOrPut(s.id) {
            Connection(SshjTransport(), target(s), HostKeyPolicy(hostKeys, acceptNew = false)) {
                when {
                    s.authType == AuthType.KEY -> Auth.PrivateKey(vault.privateKey())
                    password != null -> Auth.Password(password.copyOf())
                    else -> throw SshError.AuthFailed(s.user, null)
                }
            }
        }
        return try {
            Server(conn.session(), resume, transcript)
        } catch (e: Exception) {
            // A failed attempt must not keep a connection object bound to a stale password.
            connections.remove(s.id)?.close()
            throw e
        }
    }

    fun acceptHostKey(s: ServerEntity, e: SshError.HostKeyUnknown) = hostKeys.put(s.host, s.port, e.key)

    fun drop(id: Long) { connections.remove(id)?.close() }
}
