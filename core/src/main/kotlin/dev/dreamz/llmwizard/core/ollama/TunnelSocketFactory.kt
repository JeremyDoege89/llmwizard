package dev.dreamz.llmwizard.core.ollama

import dev.dreamz.llmwizard.core.ssh.DuplexStream
import dev.dreamz.llmwizard.core.ssh.SshSession
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * Sockets that are SSH direct-tcpip channels to [host]:[port] as seen from the target
 * (PLAN.md 5.8). OkHttp connects through these, so nothing listens on the phone and other
 * apps cannot piggyback on the tunnel. The address OkHttp asks for is ignored.
 */
class TunnelSocketFactory(private val session: () -> SshSession, private val host: String, private val port: Int) : SocketFactory() {
    override fun createSocket(): Socket = TunnelSocket(session, host, port)
    override fun createSocket(h: String?, p: Int): Socket = createSocket().also { it.connect(InetSocketAddress.createUnresolved(h ?: "x", p)) }
    override fun createSocket(h: String?, p: Int, la: InetAddress?, lp: Int): Socket = createSocket(h, p)
    override fun createSocket(h: InetAddress?, p: Int): Socket = createSocket(h?.hostAddress, p)
    override fun createSocket(h: InetAddress?, p: Int, la: InetAddress?, lp: Int): Socket = createSocket(h?.hostAddress, p)
}

private class TunnelSocket(private val session: () -> SshSession, private val host: String, private val port: Int) : Socket() {
    @Volatile private var channel: DuplexStream? = null
    @Volatile private var closed = false
    private val loopback = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (closed) throw IOException("Socket closed")
        channel = try {
            runBlocking { session().openDirect(host, port) }
        } catch (e: Exception) {
            throw IOException("Could not open a tunnel to $host:$port: ${e.message}", e)
        }
    }

    override fun getInputStream(): InputStream = channel?.input ?: throw IOException("Not connected")
    override fun getOutputStream(): OutputStream = channel?.output ?: throw IOException("Not connected")
    override fun isConnected() = channel != null
    override fun isClosed() = closed
    override fun isInputShutdown() = closed
    override fun isOutputShutdown() = closed
    override fun shutdownInput() {}
    override fun shutdownOutput() {}
    override fun getInetAddress(): InetAddress = loopback
    override fun getPort() = port
    override fun getLocalAddress(): InetAddress = loopback
    override fun getLocalPort() = 0
    override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(loopback, port)
    override fun getLocalSocketAddress(): SocketAddress = InetSocketAddress(loopback, 0)

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        channel?.close()
    }
}
