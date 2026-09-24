package com.example.core.io

/**
 * One thing to do outside the text field: a verb and what it is done to.
 *
 * Everything a keyboard key used to be able to do stayed inside the field it was
 * typing into. This is the other direction — press Back, tap a point, play the next
 * track, tick every checkbox down a feed — and it is written as a short line of text
 * rather than as a class per operation, for the same reason layouts are data: a verb
 * that exists can be bound to any key, gesture, macro or (later) sensor without code,
 * and a verb that does not exist yet is one registry entry away rather than one
 * release away.
 *
 * The text form is deliberately the one a person would write:
 *
 *     back
 *     tap 0.5 0.8
 *     media next
 *     click text="Select all"
 *     sweep mode=checkboxes pages=3
 *
 * Arguments are positional in the order the verb declares them ([VerbSpec.params]),
 * or named — named wins, so a line stays readable when it skips one.
 */
data class Command(
    val verb: String,
    val positional: List<String> = emptyList(),
    val named: Map<String, String> = emptyMap()
) {
    /** The argument called [name], by name or by its declared position. */
    fun arg(name: String): String? {
        named[name]?.let { return it }
        val index = Verbs.byId(verb)?.params?.indexOfFirst { it.name == name } ?: -1
        return if (index >= 0) positional.getOrNull(index) else null
    }

    fun float(name: String): Float? = arg(name)?.replace(',', '.')?.toFloatOrNull()

    fun int(name: String): Int? = arg(name)?.trim()?.toIntOrNull()

    /** Back to the line it came from, quoting only what needs it. */
    fun write(): String = buildString {
        append(verb)
        positional.forEach { append(' ').append(quote(it)) }
        named.forEach { (k, v) -> append(' ').append(k).append('=').append(quote(v)) }
    }

    companion object {
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Null for a blank line; otherwise always a command, even of an unknown verb. */
        fun parse(raw: String): Command? {
            val tokens = tokenize(raw.trim())
            if (tokens.isEmpty()) return null
            val verb = tokens.first().value.lowercase()
            val positional = mutableListOf<String>()
            val named = linkedMapOf<String, String>()
            tokens.drop(1).forEach { token ->
                val eq = if (token.quotedFromStart) -1 else token.value.indexOf('=')
                if (eq > 0 && NAME.matches(token.value.substring(0, eq))) {
                    named[token.value.substring(0, eq).lowercase()] = token.value.substring(eq + 1)
                } else {
                    positional += token.value
                }
            }
            return Command(verb, positional, named)
        }

        private class Token(val value: String, val quotedFromStart: Boolean)

        /**
         * Splits on spaces, keeping "quoted text" together — and `name="quoted text"`
         * as one token, which is how a click on a two-word button is written.
         */
        private fun tokenize(line: String): List<Token> {
            val out = mutableListOf<Token>()
            val current = StringBuilder()
            var inQuotes = false
            var started = false
            var quotedFromStart = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '\\' && inQuotes && i + 1 < line.length -> {
                        current.append(line[i + 1]); i++
                    }
                    c == '"' -> {
                        if (!started) quotedFromStart = true
                        inQuotes = !inQuotes; started = true
                    }
                    c.isWhitespace() && !inQuotes -> {
                        if (started) out += Token(current.toString(), quotedFromStart)
                        current.clear(); started = false; quotedFromStart = false
                    }
                    else -> {
                        current.append(c); started = true
                    }
                }
                i++
            }
            if (started) out += Token(current.toString(), quotedFromStart)
            return out
        }

        private fun quote(value: String): String =
            if (value.isNotEmpty() && value.none { it.isWhitespace() || it == '"' || it == '\\' || it == '=' }) value
            else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
