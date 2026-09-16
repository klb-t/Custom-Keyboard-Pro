package com.example.core.discovery

import android.util.Base64
import com.example.core.predict.GenerationCut
import com.example.core.predict.StopCondition
import com.example.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** What a described call is given to work with. */
data class CallInput(
    val model: String = "",
    val prompt: String = "",
    /** BCP-47-ish, or empty for "the provider decides". */
    val language: String = "",
    /** Audio to transcribe, or a picture to read, when the call sends a file. */
    val file: File? = null,
    val fileMime: String = "application/octet-stream",
    /** A picture as base64, for providers that take it inside the JSON body. */
    val imageBase64: String = "",
    val imageMime: String = "image/jpeg",
    /** Everything the user set for this model — standard and model-specific alike. */
    val params: Map<String, String> = emptyMap()
) {
    fun values(apiKey: String): Map<String, String> = buildMap {
        // Params go in first so a provider's own defaults can be overridden by the
        // user, and never the other way round.
        putAll(params)
        put("model", model)
        put("prompt", prompt)
        put("language", language)
        put("imageBase64", imageBase64)
        put("imageMime", imageMime)
        put("audioMime", fileMime)
        put("key", apiKey)
    }
}

/** What a stream produced, and why it ended. */
data class StreamOutcome(val text: String, val stoppedBecause: String?)

/** What came back. Text, bytes, or both, plus the raw reply for when it went wrong. */
class CallResult(
    val text: String? = null,
    val bytes: ByteArray? = null,
    val mime: String = "",
    val raw: String = ""
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && (bytes == null || bytes.isEmpty())
}

/**
 * The one place that turns a [CallSpec] into an actual HTTP request.
 *
 * Everything the app does with a provider other than chat goes through here:
 * dictation, reading text out of a picture, making a picture, making a video. They
 * differ in a path, a body and where the answer sits, and all three of those are now
 * data — so this file is the whole of the code that used to be a growing `when` over
 * vendor names, and it does not mention a single vendor.
 *
 * What is *not* here is chat, on purpose. Streaming, message arrays and tool calls
 * are not expressible as a template without inventing a programming language, so
 * those three wire formats stay hand-written in [com.example.core.ai.AiClient]. The
 * line between "worth describing" and "worth coding" is exactly there.
 */
object CallEngine {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    suspend fun run(
        provider: ProviderSpec,
        capability: String,
        input: CallInput,
        apiKey: String
    ): Result<CallResult> = withContext(Dispatchers.IO) {
        runCatching {
            val spec = provider.capability(capability)
                ?: error("${provider.label} cannot ${AiCapability.label(capability).lowercase()}.")
            val call = spec.call
                ?: error("${provider.label} handles ${capability} in code, not as a described call.")
            val resolved = input.copy(
                model = input.model.ifBlank { spec.defaultModel }
            )
            execute(provider, call, resolved, apiKey)
        }
    }

    private suspend fun execute(
        provider: ProviderSpec,
        call: CallSpec,
        input: CallInput,
        apiKey: String
    ): CallResult {
        val values = input.values(apiKey)
        val url = resolveUrl(provider.baseUrl, Templates.fill(call.path, values), call, values)

        val request = Request.Builder().url(url)
        applyAuth(request, call, apiKey)
        call.headers.forEach { (name, value) -> request.addHeader(name, Templates.fill(value, values)) }

        val body = buildBody(call, input, values)
        when (call.method.uppercase()) {
            "GET" -> request.get()
            "DELETE" -> if (body == null) request.delete() else request.delete(body)
            "PUT" -> request.put(body ?: emptyBody())
            else -> request.post(body ?: emptyBody())
        }

        AppLogger.d("CallEngine", "${call.method} $url (${provider.id})")
        val first = send(request.build())

        val root = first.json ?: return literal(first, call)
        if (!call.isAsync) return extract(root, call, first)

        val polled = poll(provider, call, root, apiKey, values)
        return extract(polled, call, first)
    }

    // -----------------------------------------------------------------------
    // Streaming
    // -----------------------------------------------------------------------

    /**
     * Runs a described call and hands back tokens as they arrive.
     *
     * Everything above this waits for a whole reply, which is right for a
     * transcription and wrong for a suggestion. Cutting on a log-probability needs
     * the probabilities while they are still coming, a time budget only means
     * something if there is something to show when it expires, and a continuation
     * that appears all at once four seconds late is one nobody waits for.
     *
     * The parsing is in [SseReader] and the stopping is in [GenerationCut], both of
     * which are testable without a network. This function is the wire between them
     * and deliberately holds no judgement of its own.
     */
    suspend fun stream(
        provider: ProviderSpec,
        capability: String,
        input: CallInput,
        apiKey: String,
        condition: StopCondition,
        onToken: (String) -> Unit
    ): Result<StreamOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            val spec = provider.capability(capability)
                ?: error("${provider.label} cannot ${AiCapability.label(capability).lowercase()}.")
            val call = spec.call ?: error("${provider.label} has no described call to stream.")
            if (!call.stream) error("${provider.label} is not set up to stream this.")

            val resolved = input.copy(model = input.model.ifBlank { spec.defaultModel })
            val values = resolved.values(apiKey)
            val url = resolveUrl(provider.baseUrl, Templates.fill(call.path, values), call, values)

            val request = Request.Builder().url(url)
            applyAuth(request, call, apiKey)
            request.addHeader("Accept", "text/event-stream")
            call.headers.forEach { (name, value) -> request.addHeader(name, Templates.fill(value, values)) }
            request.post(buildBody(call, resolved, values) ?: emptyBody())

            AppLogger.d("CallEngine", "streaming from ${provider.id}")
            val reader = SseReader(call)
            val cut = GenerationCut(condition)
            var failure: String? = null

            client.newCall(request.build()).execute().use { response ->
                val body = response.body ?: error("The provider opened a stream with nothing in it.")
                if (!response.isSuccessful) {
                    error("${response.code} ${response.message}: ${body.string().take(300)}")
                }
                val source = body.source()
                while (true) {
                    // A blocking read that the coroutine's cancellation cannot
                    // interrupt is how a cancelled suggestion keeps costing money, so
                    // the loop checks between lines.
                    if (!isActive) break
                    val line = source.readUtf8Line() ?: break
                    when (val chunk = reader.feed(line)) {
                        null -> Unit
                        is Chunk.Done -> break
                        is Chunk.Failed -> {
                            failure = chunk.message
                            break
                        }
                        is Chunk.Token -> {
                            val before = cut.text.length
                            val keepGoing = cut.accept(chunk.text, chunk.logP)
                            // Report only what was kept: the last token is often
                            // trimmed by a stop string or the hard cap, and echoing
                            // the raw token would show text that is not in the result.
                            cut.text.drop(before).takeIf { it.isNotEmpty() }?.let(onToken)
                            if (!keepGoing) break
                        }
                    }
                }
            }

            failure?.let { error("The provider stopped: $it") }
            StreamOutcome(cut.text, cut.stoppedBecause)
        }.onFailure {
            AppLogger.d("CallEngine", "stream from ${provider.id} ended: ${it.message}")
        }
    }

    // -----------------------------------------------------------------------
    // Pieces
    // -----------------------------------------------------------------------

    private fun resolveUrl(
        baseUrl: String,
        path: String,
        call: CallSpec,
        values: Map<String, String>
    ): String {
        val full = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path
        if (call.auth != AuthStyle.QUERY || call.authName.isBlank()) return full
        val key = values["key"].orEmpty()
        if (key.isBlank()) return full
        val separator = if (full.contains('?')) "&" else "?"
        return "$full$separator${call.authName}=$key"
    }

    private fun applyAuth(request: Request.Builder, call: CallSpec, apiKey: String) {
        if (apiKey.isBlank() && call.auth != AuthStyle.NONE) return
        when (call.auth) {
            AuthStyle.BEARER -> request.addHeader("Authorization", "Bearer $apiKey")
            AuthStyle.HEADER -> if (call.authName.isNotBlank()) request.addHeader(call.authName, apiKey)
            AuthStyle.PREFIXED -> if (call.authName.isNotBlank()) {
                request.addHeader(call.authName, "${call.authPrefix} $apiKey".trim())
            }
            AuthStyle.QUERY -> Unit // resolveUrl already put it in the query string
            else -> Unit
        }
    }

    private fun buildBody(call: CallSpec, input: CallInput, values: Map<String, String>): RequestBody? =
        when (call.bodyKind) {
            BodyKind.NONE -> null
            BodyKind.BINARY -> {
                val file = input.file ?: error("This call sends a file, and none was given.")
                file.asRequestBody(input.fileMime.toMediaTypeOrNull())
            }
            BodyKind.MULTIPART -> {
                val file = input.file ?: error("This call sends a file, and none was given.")
                MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                    addFormDataPart(
                        call.fileField, file.name,
                        file.asRequestBody(input.fileMime.toMediaTypeOrNull())
                    )
                    call.fields.forEach { (name, template) ->
                        val filled = Templates.fill(template, values)
                        // An empty field is worse than a missing one: several
                        // providers read "" as "this language", not "no preference".
                        if (filled.isNotBlank()) addFormDataPart(name, filled)
                    }
                }.build()
            }
            else -> {
                val json = if (call.bodyTemplate.isBlank()) JSONObject()
                else Templates.fillJson(call.bodyTemplate, values)
                json.toString().toRequestBody(JSON_MEDIA)
            }
        }

    private fun emptyBody(): RequestBody = "".toRequestBody(JSON_MEDIA)

    private class Reply(val code: Int, val bytes: ByteArray, val mime: String) {
        val text: String by lazy { String(bytes, Charsets.UTF_8) }
        val json: JSONObject? by lazy {
            if (!looksLikeJson) null
            else runCatching { JSONObject(text) }.getOrNull()
                ?: runCatching { JSONObject().put("items", JSONArray(text)) }.getOrNull()
        }
        val looksLikeJson: Boolean
            get() = mime.contains("json") || text.trimStart().startsWith("{") ||
                text.trimStart().startsWith("[")
    }

    private fun send(request: Request): Reply = client.newCall(request).execute().use { response ->
        val bytes = response.body?.bytes() ?: ByteArray(0)
        val mime = response.body?.contentType()?.toString().orEmpty()
        val reply = Reply(response.code, bytes, mime)
        if (!response.isSuccessful) {
            error("${response.code} ${response.message}: ${reply.text.take(400)}")
        }
        reply
    }

    /** The provider answered with the thing itself — audio, a picture — not JSON. */
    private fun literal(reply: Reply, call: CallSpec): CallResult =
        if (call.resultPath.isBlank() && !reply.looksLikeJson) {
            CallResult(bytes = reply.bytes, mime = reply.mime)
        } else {
            CallResult(text = reply.text, mime = reply.mime, raw = reply.text)
        }

    private suspend fun poll(
        provider: ProviderSpec,
        call: CallSpec,
        first: JSONObject,
        apiKey: String,
        values: Map<String, String>
    ): JSONObject {
        val found = JsonPath.string(first, call.pollUrlPath)
            ?: error("The provider accepted the job but did not say where to watch it: " +
                first.toString().take(300))
        val pollUrl = when {
            found.startsWith("http") -> found
            call.pollUrlTemplate.isNotBlank() ->
                resolveUrl(
                    provider.baseUrl,
                    Templates.fill(call.pollUrlTemplate, values + ("poll" to found)),
                    call,
                    values
                )
            else -> error("'$found' is not somewhere this can be watched.")
        }

        val deadline = System.currentTimeMillis() + call.pollTimeoutMs
        var last: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            delay(call.pollIntervalMs.coerceAtLeast(250L))
            val request = Request.Builder().url(pollUrl).get()
            applyAuth(request, call, apiKey)
            call.headers.forEach { (n, v) -> request.addHeader(n, Templates.fill(v, values)) }
            val reply = send(request.build())
            val root = reply.json ?: error("Watching the job returned something that is not JSON.")
            last = root

            val status = JsonPath.string(root, call.pollStatusPath).orEmpty()
            if (call.pollFailedValues.any { it.equals(status, ignoreCase = true) }) {
                error("The provider gave up: $status. ${reply.text.take(300)}")
            }
            if (call.pollDoneValues.any { it.equals(status, ignoreCase = true) }) return root
            // Some providers publish the result before they publish a status for it.
            if (call.pollStatusPath.isBlank() && JsonPath.get(root, call.resultPath) != null) return root
        }
        error(
            "Gave up waiting after ${call.pollTimeoutMs / 1000}s. " +
                "Last seen: ${last?.toString()?.take(200).orEmpty()}"
        )
    }

    private fun extract(root: JSONObject, call: CallSpec, first: Reply): CallResult {
        val value = JsonPath.get(root, call.resultPath)
            ?: error("Nothing at '${call.resultPath}' in the reply: ${root.toString().take(400)}")

        val text = when (value) {
            is String -> value
            else -> value.toString()
        }
        if (call.resultIsUrl) {
            val fetched = send(Request.Builder().url(text).get().build())
            return CallResult(bytes = fetched.bytes, mime = fetched.mime, raw = first.text)
        }
        // Anything that is not a URL and decodes cleanly as base64 of a sensible size
        // is the thing itself rather than a description of it — which is how every
        // picture endpoint that does not hand back a link answers.
        decodeBase64(text)?.let { return CallResult(bytes = it, mime = "", raw = first.text) }
        return CallResult(text = text, raw = first.text)
    }

    private fun decodeBase64(value: String): ByteArray? {
        if (value.length < 512) return null
        if (!value.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }) {
            return null
        }
        return runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()
}
