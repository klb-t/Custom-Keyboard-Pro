package com.example.engine

import com.example.core.matrix.NetLines
import com.example.core.matrix.NetMessage
import com.example.util.AppLogger
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Listens for IO Matrix lines on one port, over UDP and TCP both: datagrams for
 * streams, where the newest reading is all that matters; a connection for scripts,
 * where every line should arrive. Each line is checked against the token before
 * anything else happens, and a line that fails is dropped without an answer.
 *
 * Runs only while the engine asks — a port set, a token set, and something that
 * listens to the network — and stops the moment it does not.
 */
class NetListener(private val onMessage: (NetMessage) -> Unit, private val report: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var udp: DatagramSocket? = null
    private var tcp: ServerSocket? = null
    private class Session(val port: Int, val token: String, val allowCommands: Boolean)
    @Volatile private var running: Session? = null
    private val clients = mutableSetOf<Socket>()
    private val connections = AtomicInteger(0)

    val port: Int? get() = running?.port

    @Synchronized
    fun start(port: Int, token: String, allowCommands: Boolean) {
        val previous = running
        if (previous?.port == port && previous.token == token && previous.allowCommands == allowCommands) return
        stop()
        if (port !in 1..65535 || !NetLines.tokenUsable(token)) return
        // Identity, rather than configuration equality, prevents queued work surviving
        // stop→restart even when the user restores the exact same settings.
        val wanted = Session(port, token, allowCommands)
        running = wanted
        // Opening sockets is kept off the main thread along with everything else.
        Thread({
            var openingUdp: DatagramSocket? = null
            var openingTcp: ServerSocket? = null
            try {
                val u = DatagramSocket(null).also { openingUdp = it }.apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                val t = ServerSocket().also { openingTcp = it }.apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                synchronized(this) {
                    if (running !== wanted) return@Thread
                    udp = u
                    tcp = t
                    openingUdp = null
                    openingTcp = null
                }
                Thread({ receiveUdp(u, wanted) }, "io-net-udp").apply { isDaemon = true }.start()
                acceptTcp(t, wanted)
            } catch (e: Exception) {
                if (running === wanted) {
                    AppLogger.e("NetListener", "cannot listen on $port", e)
                    synchronized(this) { if (running === wanted) stop() }
                    main.post { if (running == null) report("Can't listen on port $port") }
                }
            } finally {
                // Also close partially opened sockets when TCP bind fails after UDP bind.
                runCatching { openingUdp?.close() }
                runCatching { openingTcp?.close() }
            }
        }, "io-net").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        running = null
        runCatching { udp?.close() }
        runCatching { tcp?.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        udp = null
        tcp = null
    }

    private fun deliver(line: String, session: Session) {
        if (running !== session) return
        val message = NetLines.parse(line, session.token, session.allowCommands) ?: return
        main.post {
            // Revocation must also discard commands parsed before a settings change.
            if (running === session) onMessage(message)
        }
    }

    private fun receiveUdp(socket: DatagramSocket, session: Session) {
        val buffer = ByteArray(NetLines.MAX_LINE * 4)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                String(packet.data, 0, packet.length, Charsets.UTF_8).lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { deliver(it, session) }
            } catch (e: Exception) {
                if (!socket.isClosed) AppLogger.e("NetListener", "udp receive failed", e)
            }
        }
    }

    private fun acceptTcp(server: ServerSocket, session: Session) {
        while (!server.isClosed) {
            val client: Socket = try {
                server.accept()
            } catch (e: Exception) {
                if (!server.isClosed) AppLogger.e("NetListener", "tcp accept failed", e)
                continue
            }
            // A handful of scripts at once is plenty; more is someone knocking.
            if (connections.incrementAndGet() > 4) {
                connections.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            val accepted = synchronized(this) {
                if (running !== session) false else { clients += client; true }
            }
            if (!accepted) {
                connections.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            Thread({ serve(client, session) }, "io-net-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket, session: Session) {
        try {
            client.soTimeout = 5 * 60_000
            BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8)).use { reader ->
                // Read by hand rather than readLine(), which would hold a line of any
                // length in memory before it could be refused.
                val line = StringBuilder()
                while (true) {
                    val c = reader.read()
                    if (c < 0) break
                    if (c == '\n'.code) {
                        if (line.isNotBlank()) deliver(line.toString().trimEnd('\r'), session)
                        line.setLength(0)
                    } else {
                        line.append(c.toChar())
                        if (line.length > NetLines.MAX_LINE) break
                    }
                }
            }
        } catch (_: Exception) {
            // A client going away mid-line is how most connections end.
        } finally {
            runCatching { client.close() }
            synchronized(this) { clients.remove(client) }
            connections.decrementAndGet()
        }
    }
}
