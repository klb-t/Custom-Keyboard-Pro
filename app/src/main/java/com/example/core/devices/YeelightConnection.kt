package com.example.core.devices

import java.io.Closeable
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit private IPv4 target, unicast discovery, then the documented reverse music socket.
 * IP pinning is NOT cryptographic authentication. This protocol is for a trusted LAN only.
 * Call connect/send off the UI thread; close is thread-safe and interrupts blocking I/O.
 */
class YeelightConnection(host: String, private val decoder: YeelightReplyDecoder) : LightSink {
    private val address = YeelightProtocol.privateIpv4(host)
    private val target = address.hostAddress!!
    private val closed = AtomicBoolean(false)
    private val opened = mutableListOf<Closeable>()
    @Volatile private var musicSocket: Socket? = null
    private var sequence = 2
    private var connecting = false
    var priorState: List<String>? = null
        private set
    private fun live() { check(!closed.get()) { "Light session stopped" } }
    private fun <T : Closeable> own(value: T): T = synchronized(opened) {
        if (closed.get()) { value.close(); error("Light session stopped") }
        opened += value; value
    }
    override fun connect(): LightIdentity {
        synchronized(this) { live(); check(!connecting); connecting = true }
        try {
            val discovery = own(DatagramSocket().apply { soTimeout = 2000 })
            val request = YeelightProtocol.SEARCH.toByteArray(Charsets.US_ASCII)
            discovery.send(DatagramPacket(request, request.size, address, YeelightProtocol.DISCOVERY_PORT))
            var identity: LightIdentity? = null
            val deadline = System.nanoTime() + 3_000_000_000L
            repeat(8) {
                if (identity == null && System.nanoTime() < deadline) {
                    live()
                    val buffer = ByteArray(8193); val response = DatagramPacket(buffer, buffer.size)
                    discovery.receive(response)
                    if (response.address == address) {
                        require(response.length <= 8192)
                        identity = YeelightProtocol.discovery(String(buffer, 0, response.length, Charsets.UTF_8), target)
                    }
                }
            }
            discovery.close()
            val device = identity ?: error("No supported Yeelight reply from the selected address")
            val control = own(Socket())
            control.connect(InetSocketAddress(address, YeelightProtocol.CONTROL_PORT), 2500)
            control.soTimeout = 1000; control.tcpNoDelay = true
            // Bind the callback to the actual interface selected for this route, not all interfaces.
            val local = control.localAddress
            YeelightProtocol.privateIpv4(local.hostAddress!!)
            val listener = own(ServerSocket().apply { reuseAddress = false; bind(InetSocketAddress(local, 0), 1); soTimeout = 2500 })
            val input = control.getInputStream(); val output = control.getOutputStream()
            fun reply(id: Int): List<String> {
                val until = System.nanoTime() + 3_000_000_000L
                repeat(8) {
                    live(); check(System.nanoTime() < until)
                    val r = decoder.decode(YeelightProtocol.readFrame(input))
                    if (r.id == id) { check(!r.error); return r.result ?: error("Missing device result") }
                }
                error("No correlated device response")
            }
            output.write(YeelightProtocol.properties(1).toByteArray(Charsets.UTF_8)); output.flush()
            val previous = reply(1)
            require(previous.size == 4 && previous[0] in setOf("on", "off") &&
                previous[1].toIntOrNull() in 1..100 && previous[2].toIntOrNull() in 1..3 &&
                previous[3].toIntOrNull() in 0..0xffffff) { "Bulb did not expose valid colour/brightness state" }
            priorState = previous.toList()
            live()
            output.write(YeelightProtocol.music(2, local.hostAddress!!, listener.localPort).toByteArray(Charsets.UTF_8)); output.flush()
            check(reply(2) == listOf("ok"))
            val inbound = own(listener.accept())
            require(inbound.inetAddress == address) { "Unexpected music socket peer" }
            inbound.tcpNoDelay = true
            musicSocket = inbound
            listener.close(); control.close()
            live(); return device
        } catch (e: Exception) { close(); throw e }
    }
    override fun send(value: LightValue) {
        live(); val s = musicSocket ?: error("No connected light")
        val raw = YeelightProtocol.colour(++sequence, value)
        s.getOutputStream().write(raw.toByteArray(Charsets.UTF_8)); s.getOutputStream().flush()
        // No reply/property notification is promised in music mode. Caller reports SENT, never VERIFIED.
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(opened) { opened.asReversed().forEach { runCatching { it.close() } }; opened.clear() }
        musicSocket = null
        // The protocol defines music-channel disconnect as ending music mode. No state restoration:
        // a blind rollback could overwrite a newer physical/app/user change.
    }
}
