package dev.dreamz.llmwizard.core.ssh

import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration

/** A machine to connect to. Holds no secret. */
data class Target(
    val host: String,
    val port: Int = 22,
    val user: String,
    /** A label for messages and the transcript; defaults to user@host. */
    val name: String = "$user@$host",
)

/** How to log in. Passwords are [CharArray] so they can be wiped (SPEC.md rule 3). */
sealed interface Auth {
    class Password(val password: CharArray) : Auth {
        fun wipe() = password.fill('\u0000')
        override fun toString() = "Password(****)"
    }

    /** An OpenSSH-format private key, decrypted, held only for the connection attempt. */
    class PrivateKey(val openSshPrivateKey: String) : Auth {
        override fun toString() = "PrivateKey(****)"
    }
}

data class ExecResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    /** True when output went past the cap and the rest was discarded. */
    val truncated: Boolean = false,
) {
    val out: String get() = stdout.toString(Charsets.UTF_8)
    val err: String get() = stderr.toString(Charsets.UTF_8)
    val ok: Boolean get() = exitCode == 0
    override fun toString() = "ExecResult(exit=$exitCode, out=${stdout.size}B, err=${stderr.size}B)"
}

/** A byte pipe to a host:port reached from the target (SSH direct-tcpip). */
interface DuplexStream : AutoCloseable {
    val input: InputStream
    val output: OutputStream
}

interface SshSession : AutoCloseable {
    val target: Target
    val hostKey: KnownHost
    val isConnected: Boolean

    /**
     * Runs [command] through the login user's shell. Callers pass only fixed command lines
     * built by [dev.dreamz.llmwizard.core.remote.Remote]; script bodies travel on stdin or
     * in files, never inside this string.
     *
     * Cancellation and [timeout] close the channel, which is the only way to unblock it.
     */
    suspend fun exec(command: String, stdin: ByteArray? = null, timeout: Duration): ExecResult

    /** Opens a direct-tcpip channel to [host]:[port] as seen from the target. */
    suspend fun openDirect(host: String, port: Int): DuplexStream
}

interface SshTransport {
    suspend fun connect(target: Target, auth: Auth, hostKeys: HostKeyPolicy): SshSession
}

/** A host key as remembered: its type and OpenSSH-style SHA256 fingerprint. */
data class KnownHost(val keyType: String, val fingerprint: String)

interface HostKeyStore {
    fun get(host: String, port: Int): KnownHost?
    fun put(host: String, port: Int, key: KnownHost)
    fun forget(host: String, port: Int)
}

/**
 * Trust on first use. With [acceptNew] false, an unknown host fails with
 * [SshError.HostKeyUnknown] carrying the fingerprint, so the UI can show it, let the user
 * compare it with `ssh-keygen -lf` on the server, store it, and connect again.
 * A key that differs from the stored one always fails with [SshError.HostKeyChanged].
 */
class HostKeyPolicy(val store: HostKeyStore, val acceptNew: Boolean)

/** Typed SSH failures. Each maps to a plain message with a next step. */
sealed class SshError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreachable(val host: String, cause: Throwable?) :
        SshError("Could not reach $host. Check the address and that the machine is on and on this network.", cause)

    class AuthFailed(val user: String, cause: Throwable?) :
        SshError("The server refused the login for $user. Check the user name and password, or that the app's key is installed.", cause)

    class HostKeyUnknown(val host: String, val key: KnownHost) :
        SshError("First connection to $host. Its key fingerprint is ${key.fingerprint}. Compare it with the server before trusting it.")

    class HostKeyChanged(val host: String, val expected: KnownHost, val actual: KnownHost) :
        SshError("The key of $host has changed (was ${expected.fingerprint}, now ${actual.fingerprint}). " +
            "This happens after a reinstall, or when something is intercepting the connection. Not connecting.")

    class Timeout(val what: String, cause: Throwable? = null) :
        SshError("Timed out: $what.", cause)

    class Disconnected(cause: Throwable?) :
        SshError("The connection dropped. It will be retried.", cause)

    /** Android 17 with target SDK 37 refuses LAN sockets without ACCESS_LOCAL_NETWORK. Raised by the app. */
    class LocalNetworkPermission(cause: Throwable?) :
        SshError("The app needs permission to reach devices on your local network.", cause)

    class Protocol(message: String, cause: Throwable?) : SshError(message, cause)
}
