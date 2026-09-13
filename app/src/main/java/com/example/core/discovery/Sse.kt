package com.example.core.discovery

import org.json.JSONObject

/** One thing that arrived on a stream. */
sealed interface Chunk {
    /** A piece of text, with its log-probability when the provider reports one. */
    data class Token(val text: String, val logP: Double? = null) : Chunk

    /** The provider said it had finished. */
    data object Done : Chunk

    /** The provider said something went wrong, mid-stream. */
    data class Failed(val message: String) : Chunk
}

/**
 * Reading a server-sent event stream, one line at a time.
 *
 * Separate from the transport on purpose, and for the usual reason: everything that
 * can be wrong about parsing a stream can be wrong without a network, and this is the
 * only way to find that out on a machine with no phone attached.
 *
 * Where the token sits inside an event is *data*, because the providers disagree and
 * always will. OpenAI-shaped streams put it at `choices[0].delta.content`, Anthropic's
 * at `delta.text`, and something released next year will put it somewhere else — which
 * should be an edited catalogue entry rather than a release.
 */
class SseReader(
    private val textPath: String = "choices[0].delta.content",
    private val logProbPath: String = "",
    private val doneMarker: String = "[DONE]",
    private val errorPath: String = "error.message"
) {

    constructor(spec: CallSpec) : this(
        textPath = spec.streamTextPath,
        logProbPath = spec.streamLogProbPath,
        doneMarker = spec.streamDone,
        errorPath = spec.streamErrorPath
    )

    /**
     * Feeds one line, and returns what it turned out to be.
     *
     * Null means "nothing complete yet", which covers comments, the other SSE fields
     * nobody here needs, and the blank lines between events.
     */
    fun feed(line: String): Chunk? {
        val trimmed = line.trimEnd('\r')
        // ':' opens a comment. Some providers send them as keep-alives, and reading
        // one as data would put a colon in the middle of somebody's sentence.
        if (trimmed.startsWith(":")) return null
        if (trimmed.isBlank()) return null
        if (!trimmed.startsWith("data:")) return null

        val payload = trimmed.removePrefix("data:").removePrefix(" ")
        if (payload == doneMarker) return Chunk.Done
        if (payload.isBlank()) return null

        // SSE says an event ends at the blank line, and several providers never send
        // one. Parsing each data line on its own is more forgiving, and a token that
        // only arrives when the *next* token does is a token that arrives late — which
        // on a suggestion strip is the difference between useful and not.
        val event = runCatching { JSONObject(payload) }.getOrNull() ?: return null

        JsonPath.string(event, errorPath)?.takeIf { it.isNotBlank() }?.let {
            return Chunk.Failed(it)
        }

        val text = JsonPath.string(event, textPath)
        // An empty string is a real event — the first chunk of an OpenAI stream
        // carries a role and no content — and must not be mistaken for the end.
        if (text.isNullOrEmpty()) return null

        return Chunk.Token(text, logProb(event))
    }

    private fun logProb(event: JSONObject): Double? {
        if (logProbPath.isBlank()) return null
        return when (val value = JsonPath.get(event, logProbPath)) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
    }
}
