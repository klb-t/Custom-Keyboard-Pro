package com.example.core.discovery

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a provider can be asked to do.
 *
 * A provider used to mean "a thing that answers chat requests", which is why
 * dictation had one hardcoded endpoint and reading text from a picture had none at
 * all. But the three are the same problem wearing different hats: somewhere to send
 * something, a shape to send it in, and somewhere in the reply where the answer is.
 *
 * So capability becomes a field. One catalogue describes every provider, each saying
 * which of these it serves and how — and a provider that serves four of them is one
 * entry with four endpoints, not four entries in four lists.
 */
object AiCapability {
    /** Text in, text out, as a conversation — with tools, turns and a system prompt. */
    const val CHAT = "chat"

    /**
     * Text in, more of the same text out.
     *
     * Deliberately not [CHAT], though it usually reaches the same endpoint. Chat is a
     * conversation and earns hand-written code for its turns and tool calls;
     * continuing a sentence is one request with one answer, which is a description —
     * and being a description is what lets it stream, because [CallSpec] is where the
     * streaming fields live. The suggestion strip wants this one.
     */
    const val COMPLETE = "complete"

    /** Audio in, text out — dictation. */
    const val TRANSCRIBE = "transcribe"

    /** Picture in, text out. */
    const val OCR = "ocr"

    /** Text in, picture out. */
    const val IMAGE = "image"

    /** Text in, video out. */
    const val VIDEO = "video"

    /** Text in, audio out. */
    const val SPEECH = "speech"

    /** Text in, numbers out. Used for finding things, not for writing them. */
    const val EMBED = "embed"

    val ALL = listOf(CHAT, COMPLETE, TRANSCRIBE, OCR, IMAGE, VIDEO, SPEECH, EMBED)

    fun label(id: String): String = when (id) {
        CHAT -> "Writing and rewriting"
        COMPLETE -> "Finishing what you are typing"
        TRANSCRIBE -> "Dictation"
        OCR -> "Text from a picture"
        IMAGE -> "Making pictures"
        VIDEO -> "Making video"
        SPEECH -> "Reading aloud"
        EMBED -> "Search vectors"
        else -> id
    }
}

/** How the key is presented to the provider. */
object AuthStyle {
    /** `Authorization: Bearer <key>`, which is most of them. */
    const val BEARER = "bearer"

    /** A header the provider names, e.g. `x-api-key`. */
    const val HEADER = "header"

    /** A query parameter the provider names, e.g. `?key=`. */
    const val QUERY = "query"

    /** `Authorization: Token <key>` and friends — [CallSpec.authPrefix] says which word. */
    const val PREFIXED = "prefixed"

    /** The provider takes no key. Local servers, mostly. */
    const val NONE = "none"
}

/** What the body of the request is made of. */
object BodyKind {
    const val JSON = "json"

    /** A file plus fields — what nearly every transcription endpoint wants. */
    const val MULTIPART = "multipart"

    /** The file, raw, as the whole body. */
    const val BINARY = "binary"

    /** No body at all. */
    const val NONE = "none"
}

/**
 * One HTTP call, described rather than coded.
 *
 * This is the part that keeps "add a provider" from meaning "add a `when` branch".
 * Chat is the exception and stays in code — three wire formats with streaming,
 * message arrays and tool calls are not a template — but everything else in this app
 * is one request with placeholders in it and one place in the reply where the answer
 * is, and that is a description, not a program.
 *
 * Placeholders are `{{name}}` and are filled from the call's inputs: `model`,
 * `prompt`, `language`, `imageBase64`, `imageMime`, `key`, plus any parameter the
 * user set for the model. An unknown placeholder is left empty rather than left in,
 * so a template asking for something this call does not have degrades instead of
 * sending braces to a server.
 *
 * [resultPath] is a dotted path with `[n]` for arrays — `choices[0].message.content`,
 * `results.channels[0].alternatives[0].transcript`. The generality is cheap and it is
 * what lets a provider nobody has heard of be added from the settings screen.
 */
data class CallSpec(
    val method: String = "POST",
    /** Appended to the provider's base URL, unless it already starts with `http`. */
    val path: String = "",
    val auth: String = AuthStyle.BEARER,
    /** Header or query-parameter name, when [auth] needs one. */
    val authName: String = "",
    /** The word before the key for [AuthStyle.PREFIXED], e.g. "Token". */
    val authPrefix: String = "",
    val headers: Map<String, String> = emptyMap(),
    val bodyKind: String = BodyKind.JSON,
    /** JSON body with `{{placeholders}}`. Ignored when [bodyKind] is not JSON. */
    val bodyTemplate: String = "",
    /** Field name the file goes under, for multipart. */
    val fileField: String = "file",
    /** Extra multipart fields, values may hold placeholders. */
    val fields: Map<String, String> = emptyMap(),
    /** Where the answer is in the reply. Empty means "the whole body, as text". */
    val resultPath: String = "",
    /** The value at [resultPath] is a URL to fetch, not the answer itself. */
    val resultIsUrl: Boolean = false,
    /**
     * Asynchronous providers: where the reply puts the URL to poll, and what tells
     * us it has finished. Image and video generation are nearly always like this.
     */
    val pollUrlPath: String = "",
    /**
     * How to turn what [pollUrlPath] found into a URL, when it is an id rather than
     * a link. `{{poll}}` is the value that was found. Empty means it already is a URL.
     */
    val pollUrlTemplate: String = "",
    val pollStatusPath: String = "",
    val pollDoneValues: List<String> = emptyList(),
    val pollFailedValues: List<String> = emptyList(),
    val pollIntervalMs: Long = 2000L,
    val pollTimeoutMs: Long = 300_000L,

    // --- streaming ---------------------------------------------------------
    //
    // Asking for a stream is already expressible: the body template carries
    // "stream": true like any other field. What is not expressible is how to *read*
    // one back, because the providers put the token in different places and always
    // will. So the paths are data, and a provider that arrives next year with its own
    // arrangement is an edited entry rather than a release.

    /** Read the reply as server-sent events rather than waiting for all of it. */
    val stream: Boolean = false,
    /** Where one token sits in a streamed event. */
    val streamTextPath: String = "choices[0].delta.content",
    /** Where its log-probability sits, when the provider reports one. */
    val streamLogProbPath: String = "",
    /** The literal payload that ends the stream. */
    val streamDone: String = "[DONE]",
    /** Where a mid-stream failure explains itself. */
    val streamErrorPath: String = "error.message"
) {

    val isAsync: Boolean get() = pollUrlPath.isNotBlank() || pollStatusPath.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("method", method)
        put("path", path)
        put("auth", auth)
        if (authName.isNotBlank()) put("authName", authName)
        if (authPrefix.isNotBlank()) put("authPrefix", authPrefix)
        if (headers.isNotEmpty()) put("headers", JSONObject(headers.toMap()))
        put("bodyKind", bodyKind)
        if (bodyTemplate.isNotBlank()) put("bodyTemplate", bodyTemplate)
        if (fileField != "file") put("fileField", fileField)
        if (fields.isNotEmpty()) put("fields", JSONObject(fields.toMap()))
        if (resultPath.isNotBlank()) put("resultPath", resultPath)
        if (resultIsUrl) put("resultIsUrl", true)
        if (pollUrlPath.isNotBlank()) put("pollUrlPath", pollUrlPath)
        if (pollUrlTemplate.isNotBlank()) put("pollUrlTemplate", pollUrlTemplate)
        if (pollStatusPath.isNotBlank()) put("pollStatusPath", pollStatusPath)
        if (pollDoneValues.isNotEmpty()) put("pollDoneValues", JSONArray(pollDoneValues))
        if (pollFailedValues.isNotEmpty()) put("pollFailedValues", JSONArray(pollFailedValues))
        if (pollIntervalMs != 2000L) put("pollIntervalMs", pollIntervalMs)
        if (pollTimeoutMs != 300_000L) put("pollTimeoutMs", pollTimeoutMs)
        if (stream) {
            put("stream", true)
            put("streamTextPath", streamTextPath)
            if (streamLogProbPath.isNotBlank()) put("streamLogProbPath", streamLogProbPath)
            if (streamDone != "[DONE]") put("streamDone", streamDone)
            if (streamErrorPath != "error.message") put("streamErrorPath", streamErrorPath)
        }
    }

    companion object {
        fun fromJson(o: JSONObject): CallSpec = CallSpec(
            method = o.optString("method", "POST").uppercase(),
            path = o.optString("path"),
            auth = o.optString("auth", AuthStyle.BEARER).lowercase(),
            authName = o.optString("authName"),
            authPrefix = o.optString("authPrefix"),
            headers = o.optJSONObject("headers").toStringMap(),
            bodyKind = o.optString("bodyKind", BodyKind.JSON).lowercase(),
            bodyTemplate = o.optJSONObject("body")?.toString() ?: o.optString("bodyTemplate"),
            fileField = o.optString("fileField", "file").ifBlank { "file" },
            fields = o.optJSONObject("fields").toStringMap(),
            resultPath = o.optString("resultPath"),
            resultIsUrl = o.optBoolean("resultIsUrl", false),
            pollUrlPath = o.optString("pollUrlPath"),
            pollUrlTemplate = o.optString("pollUrlTemplate"),
            pollStatusPath = o.optString("pollStatusPath"),
            pollDoneValues = o.optJSONArray("pollDoneValues").toStringList(),
            pollFailedValues = o.optJSONArray("pollFailedValues").toStringList(),
            pollIntervalMs = o.optLong("pollIntervalMs", 2000L),
            pollTimeoutMs = o.optLong("pollTimeoutMs", 300_000L),
            stream = o.optBoolean("stream", false),
            streamTextPath = o.optString("streamTextPath")
                .ifBlank { "choices[0].delta.content" },
            streamLogProbPath = o.optString("streamLogProbPath"),
            streamDone = o.optString("streamDone").ifBlank { "[DONE]" },
            streamErrorPath = o.optString("streamErrorPath").ifBlank { "error.message" }
        )
    }
}

/**
 * One thing a provider can do, and how to ask it.
 *
 * Exactly one of [wire] and [call] carries the answer. [wire] names a request shape
 * the app implements in code — the three chat formats, and dictation's near-universal
 * multipart shape — and [call] describes the request outright for everything else.
 * Both exist because the first four are worth having hand-written and the rest are
 * not: a template that had to express streaming and tool calls would be a programming
 * language with a JSON syntax.
 */
data class CapabilitySpec(
    val wire: String = "",
    val call: CallSpec? = null,
    val defaultModel: String = "",
    /** Overrides the provider's own, when this capability lists models elsewhere. */
    val modelsPath: String = "",
    /** Models this capability is known to serve, when the provider publishes no list. */
    val models: List<String> = emptyList(),
    val notes: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (wire.isNotBlank()) put("wire", wire)
        call?.let { put("call", it.toJson()) }
        if (defaultModel.isNotBlank()) put("defaultModel", defaultModel)
        if (modelsPath.isNotBlank()) put("modelsPath", modelsPath)
        if (models.isNotEmpty()) put("models", JSONArray(models))
        if (notes.isNotBlank()) put("notes", notes)
    }

    companion object {
        fun fromJson(o: JSONObject): CapabilitySpec = CapabilitySpec(
            wire = o.optString("wire").lowercase(),
            call = o.optJSONObject("call")?.let { CallSpec.fromJson(it) },
            defaultModel = o.optString("defaultModel"),
            modelsPath = o.optString("modelsPath"),
            models = o.optJSONArray("models").toStringList(),
            notes = o.optString("notes")
        )
    }
}

internal fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return buildMap { keys().forEach { put(it, optString(it)) } }
}

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
}
