package com.example.core.devices

import java.io.InputStream
import java.net.InetAddress
import java.net.URI

/** Manufacturer LAN protocol. No HTTP/TLS relaxation, authentication or universal-bulb claim. */
object YeelightProtocol {
    const val DISCOVERY_PORT = 1982
    const val CONTROL_PORT = 55443
    const val SEARCH = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1982\r\nMAN: \"ssdp:discover\"\r\nST: wifi_bulb\r\n\r\n"
    val required = setOf("get_prop", "set_music", "set_scene")
    fun privateIpv4(raw: String): InetAddress {
        val pieces = raw.split('.')
        require(pieces.size == 4 && pieces.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 }) { "Enter a numeric private IPv4 address." }
        val p = pieces.map(String::toInt)
        require(p[0] == 10 || p[0] == 172 && p[1] in 16..31 || p[0] == 192 && p[1] == 168) { "This adapter only connects to explicitly selected private LAN addresses." }
        // Deliberately conservative: no network/broadcast-style final octets, DNS, WAN or loopback.
        require(p[3] in 1..254)
        return InetAddress.getByAddress(p.map(Int::toByte).toByteArray())
    }
    fun discovery(raw: String, expectedHost: String): LightIdentity {
        privateIpv4(expectedHost)
        require(raw.length <= 8192 && raw.startsWith("HTTP/1.1 200 OK\r\n"))
        val headers = linkedMapOf<String, String>()
        raw.split("\r\n").drop(1).filter { it.isNotBlank() }.forEach {
            val colon = it.indexOf(':'); require(colon > 0)
            val key = it.substring(0, colon).trim().lowercase(java.util.Locale.ROOT)
            require(key !in headers) { "Duplicate discovery field" }
            headers[key] = it.substring(colon + 1).trim()
        }
        val uri = URI(headers.getValue("location"))
        require(uri.scheme == "yeelight" && uri.host == expectedHost && uri.port == CONTROL_PORT &&
            uri.userInfo == null && uri.path.orEmpty().isEmpty() && uri.query == null && uri.fragment == null)
        val id = headers.getValue("id"); require(Regex("0x[0-9a-fA-F]{1,32}").matches(id))
        val model = headers.getValue("model"); require(model.length in 1..64 && model.none { it < ' ' })
        val methods = headers.getValue("support").split(Regex("\\s+")).toSet()
        require(methods.size <= 64 && methods.all { Regex("[a-z_]{1,40}").matches(it) } && methods.containsAll(required)) {
            "The bulb did not advertise the required LAN/music/colour operations."
        }
        return LightIdentity(id, model, methods)
    }
    fun properties(id: Int): String = frame(id, "get_prop", "[\"power\",\"bright\",\"color_mode\",\"rgb\"]")
    fun music(id: Int, host: String, port: Int): String {
        privateIpv4(host); require(port in 1024..65535)
        return frame(id, "set_music", "[1,\"$host\",$port]")
    }
    fun colour(id: Int, value: LightValue): String = frame(id, "set_scene", "[\"color\",${value.rgb},${value.brightness}]")
    private fun frame(id: Int, method: String, params: String): String {
        require(id > 0)
        return "{\"id\":$id,\"method\":\"$method\",\"params\":$params}\r\n"
    }
    /** Reads one bounded CRLF frame; rejects pathological nesting before platform JSON parsing. */
    fun readFrame(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        var previous = -1
        while (bytes.size() < 8192) {
            val c = input.read(); require(c >= 0) { "Connection closed before reply" }
            if (previous == 13 && c == 10) {
                val raw = bytes.toByteArray().copyOf(bytes.size() - 1).toString(Charsets.UTF_8)
                var depth = 0; var string = false; var escape = false
                for (ch in raw) {
                    if (string) { if (escape) escape = false else if (ch == '\\') escape = true else if (ch == '"') string = false }
                    else when (ch) { '"' -> string = true; '{', '[' -> { depth++; require(depth <= 8) }; '}', ']' -> { depth--; require(depth >= 0) } }
                }
                require(!string && depth == 0 && raw.startsWith("{") && raw.endsWith("}"))
                return raw
            }
            bytes.write(c); previous = c
        }
        error("Oversized device reply")
    }
}

data class YeelightReply(val id: Int?, val result: List<String>?, val error: Boolean = false)
fun interface YeelightReplyDecoder { fun decode(raw: String): YeelightReply }
