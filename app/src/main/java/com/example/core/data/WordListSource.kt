package com.example.core.data

import android.content.Context
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.layout.LayoutJson
import com.example.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Somewhere a word list can be fetched from.
 *
 * Data, for the same reason the provider catalogue is: which list is *good* is a
 * judgement about a language, a register and a person, and hardcoding one answers it
 * for everybody. Somebody writing Polish legal drafts, somebody writing Kotlin, and
 * somebody texting want three different lists, and none of them is wrong.
 */
data class WordListSource(
    val id: String,
    /** Two-letter language tag. The downloaded file is stored under this name. */
    val language: String,
    val label: String,
    val url: String,
    /** What it is, where it came from, and under what licence. Shown before downloading. */
    val note: String = "",
    val licence: String = "",
    /** Roughly how many words, so the size of the download is not a surprise. */
    val words: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("language", language)
        put("label", label)
        put("url", url)
        if (note.isNotBlank()) put("note", note)
        if (licence.isNotBlank()) put("licence", licence)
        if (words != 0) put("words", words)
    }

    companion object {
        fun fromJson(o: JSONObject): WordListSource? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: return null
            val language = o.optString("language").takeIf { it.isNotBlank() } ?: return null
            return WordListSource(
                id = id,
                language = language,
                label = o.optString("label").ifBlank { id },
                url = url,
                note = o.optString("note"),
                licence = o.optString("licence"),
                words = o.optInt("words", 0)
            )
        }
    }
}

/**
 * Fetching and replacing word lists.
 *
 * The bundled lists make the keyboard work on the day it is installed, offline and
 * without asking anybody for anything. These exist so nobody is stuck with them: a
 * larger list, a different language, or a file of somebody's own trade vocabulary
 * simply replaces the one in the APK for that language.
 *
 * Nothing is fetched on its own. Every download here is something the user asked for
 * by tapping it, which is the same rule the provider catalogue follows.
 */
object WordLists {

    private const val ASSET = "wordlists.json"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun parseList(raw: String): List<WordListSource> = try {
        val arr = JSONArray(LayoutJson.stripCodeFenceArray(raw))
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { WordListSource.fromJson(it) } }
    } catch (e: Exception) {
        emptyList()
    }

    private var bundled: List<WordListSource> = emptyList()
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        bundled = try {
            parseList(context.assets.open(ASSET).bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            AppLogger.e("WordLists", "could not read the bundled word-list sources", e)
            emptyList()
        }
    }

    /** The user's own entries first, so an address they typed always wins. */
    fun all(settings: Settings = SettingsStore.current): List<WordListSource> {
        val merged = LinkedHashMap<String, WordListSource>()
        (parseList(settings.wordListSourcesJson) + bundled).forEach { merged.putIfAbsent(it.id, it) }
        return merged.values.toList()
    }

    fun forLanguage(language: String, settings: Settings = SettingsStore.current): List<WordListSource> =
        all(settings).filter { it.language.equals(language, ignoreCase = true) }

    /**
     * Downloads [source] and puts it where [BundledDictionary] will prefer it.
     *
     * Written to a temporary file and moved into place only once it has parsed into
     * something with words in it. A half-written list is worse than the bundled one:
     * it would look like the download worked and quietly suggest nothing.
     */
    suspend fun download(context: Context, source: WordListSource): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!source.url.startsWith("https://")) error("A word list must be fetched over https.")
                val request = Request.Builder().url(source.url).get().build()
                val text = client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("${response.code} ${response.message}: ${body.take(200)}")
                    }
                    body
                }

                val words = BundledDictionary.parse(text)
                if (words.size < MINIMUM) {
                    error("That address returned ${words.size} words, which is too few to be a list.")
                }

                val target = BundledDictionary.downloadedFile(context, source.language)
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, "${source.language}.part")
                temporary.writeText(text)
                if (!temporary.renameTo(target)) {
                    target.writeText(text)
                    temporary.delete()
                }
                BundledDictionary.invalidate(source.language)
                AppLogger.d("WordLists", "installed ${words.size} words for ${source.language}")
                words.size
            }
        }

    /** Goes back to the list packaged in the APK, for a language that has one. */
    fun revert(context: Context, language: String): Boolean {
        val file = BundledDictionary.downloadedFile(context, language)
        val removed = !file.isFile || file.delete()
        BundledDictionary.invalidate(language)
        return removed
    }

    fun isReplaced(context: Context, language: String): Boolean =
        BundledDictionary.downloadedFile(context, language).isFile

    fun addCustom(source: WordListSource) {
        SettingsStore.update { s ->
            val existing = parseList(s.wordListSourcesJson).filterNot { it.id == source.id }
            val arr = JSONArray().apply { (existing + source).forEach { put(it.toJson()) } }
            s.copy(wordListSourcesJson = arr.toString())
        }
    }

    /** Below this it is not a word list, it is an error page that happened to parse. */
    private const val MINIMUM = 50
}
