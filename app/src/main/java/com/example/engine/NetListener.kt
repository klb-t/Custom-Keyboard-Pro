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
    private var running: Triple<Int, String, Boolean>? = null
    private val connections = AtomicInteger(0)

    val port: Int? get() = running?.first

    @Synchronized
    fun start(port: Int, token: String, allowCommands: Boolean) {
        val wanted = Triple(port, token, allowCommands)
        if (running == wanted) return
        stop()
        if (port !in 1..65535 || !NetLines.tokenUsable(token)) return
        running = wanted
        // Opening sockets is kept off the main thread along with everything else.
        Thread({
            try {
                val u = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                val t = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                synchronized(this) {
                    if (running != wanted) {
                        runCatching { u.close() }
                        runCatching { t.close() }
                        return@Thread
                    }
                    udp = u
                    tcp = t
                }
                Thread({ receiveUdp(u, token, allowCommands) }, "io-net-udp").apply { isDaemon = true }.start()
                acceptTcp(t, token, allowCommands)
            } catch (e: Exception) {
                AppLogger.e("NetListener", "cannot listen on $port", e)
                main.post { report("Can't listen on port $port — ${e.message ?: e.javaClass.simpleName}") }
                synchronized(this) { if (running == wanted) stop() }
            }
        }, "io-net").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        running = null
        runCatching { udp?.close() }
        runCatching { tcp?.close() }
        udp = null
        tcp = null
    }

    private fun deliver(line: String, token: String, allowCommands: Boolean) {
        val message = NetLines.parse(line, token, allowCommands) ?: return
        main.post { onMessage(message) }
    }

    private fun receiveUdp(socket: DatagramSocket, token: String, allowCommands: Boolean) {
        val buffer = ByteArray(NetLines.MAX_LINE * 4)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                String(packet.data, 0, packet.length, Charsets.UTF_8).lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { deliver(it, token, allowCommands) }
            } catch (e: Exception) {
                if (!socket.isClosed) AppLogger.e("NetListener", "udp receive failed", e)
            }
        }
    }

    private fun acceptTcp(server: ServerSocket, token: String, allowCommands: Boolean) {
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
            Thread({ serve(client, token, allowCommands) }, "io-net-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket, token: String, allowCommands: Boolean) {
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
                        if (line.isNotBlank()) deliver(line.toString().trimEnd('\r'), token, allowCommands)
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
            connections.decrementAndGet()
        }
    }
}
