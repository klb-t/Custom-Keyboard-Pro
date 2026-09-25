package com.example.core.matrix

/** What a line from the network asks for. */
sealed interface NetMessage {
    /** Values on a channel — a stream's readings, or, with none, just "it happened". */
    class Values(val channel: String, val values: FloatArray) : NetMessage

    /** A verb line to run, as a key would. Only when commands are allowed. */
    data class Do(val line: String) : NetMessage
}

/**
 * The line protocol the phone listens to — the same one its UDP streams speak, with
 * a token in front, so a phone can be steered by another phone, a script or a PC:
 *
 *     <token> io <channel> [<value> …]
 *     <token> do <verb line>            (only when commands are allowed)
 *
 * A listening socket that can make the phone do things is a door, so: nothing
 * listens unless a port is set, no token means no door (a short one too), a wrong
 * token is silence rather than an error message, and "do" lines are refused unless
 * separately allowed.
 */
object NetLines {

    const val MIN_TOKEN = 8
    const val MAX_LINE = 512
    private val CHANNEL = Regex("[A-Za-z0-9_.-]{1,32}")

    fun tokenUsable(token: String): Boolean = token.length >= MIN_TOKEN && token.none { it.isWhitespace() }

    /** Compares in time independent of where they differ, so the token cannot be found a character at a time. */
    fun sameToken(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        var diff = x.size xor y.size
        for (i in 0 until maxOf(x.size, y.size)) {
            diff = diff or ((x.getOrElse(i) { 0 }.toInt()) xor (y.getOrElse(i) { 0 }.toInt()))
        }
        return diff == 0
    }

    /** What [line] asks for, or null — for a wrong token, a malformed line, or a command not allowed. */
    fun parse(line: String, token: String, allowCommands: Boolean): NetMessage? {
        if (!tokenUsable(token) || line.length > MAX_LINE) return null
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2 || !sameToken(parts[0], token)) return null
        return when (parts[1]) {
            "io" -> {
                val channel = parts.getOrNull(2)?.takeIf { CHANNEL.matches(it) } ?: return null
                val values = parts.drop(3).map { it.replace(',', '.').toFloatOrNull()?.takeIf { v -> v.isFinite() } ?: return null }
                NetMessage.Values(channel, values.toFloatArray())
            }
            "do" -> if (!allowCommands) null else {
                val rest = line.trim().substringAfter(parts[0]).trim().removePrefix("do").trim()
                rest.takeIf { it.isNotBlank() }?.let { NetMessage.Do(it) }
            }
            else -> null
        }
    }
}
