package com.example.engine

import com.example.core.matrix.Sink
import com.example.util.AppLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Sends stream values as UDP datagrams, off the main thread.
 *
 * The newest value wins: while one send to a destination is waiting, a newer value
 * replaces it instead of queueing behind it, so a slow network makes a stream
 * coarser rather than later — which is what steering needs. Problems are reported
 * once per destination, not once per reading.
 */
class UdpSender(private val report: (String) -> Unit) {

    private var executor: ExecutorService? = null
    private var socket: DatagramSocket? = null
    private val addresses = ConcurrentHashMap<String, InetAddress>()
    private val waiting = ConcurrentHashMap<String, Pair<Sink.Udp, String>>()
    private val reported = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    private fun worker(): ExecutorService = executor ?: Executors.newSingleThreadExecutor { r ->
        Thread(r, "io-udp").apply { isDaemon = true }
    }.also { executor = it }

    fun send(sink: Sink.Udp, line: String) {
        val key = sink.line
        if (waiting.put(key, sink to line) == null) worker().execute { flush(key) }
    }

    private fun flush(key: String) {
        val (sink, line) = waiting.remove(key) ?: return
        try {
            val s = socket ?: DatagramSocket().also { socket = it }
            val address = addresses.getOrPut(sink.host) { InetAddress.getByName(sink.host) }
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            s.send(DatagramPacket(bytes, bytes.size, address, sink.port))
            reported.remove(key)
        } catch (e: Exception) {
            addresses.remove(sink.host)
            if (reported.add(key)) {
                AppLogger.e("UdpSender", "send to ${sink.host}:${sink.port} failed", e)
                report("Stream can't reach ${sink.host}:${sink.port} — ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    @Synchronized
    fun close() {
        executor?.shutdownNow()
        executor = null
        runCatching { socket?.close() }
        socket = null
        waiting.clear()
        reported.clear()
    }
}
