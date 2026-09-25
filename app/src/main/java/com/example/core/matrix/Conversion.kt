package com.example.core.matrix

import com.example.core.convert.NoteText

/** What "to=…" asks for: a type, perhaps a particular form of it, perhaps a way to get there. */
data class Goal(
    val type: DataType,
    /** Null leaves the form to the result: a provider's MP3 stays an MP3. */
    val representation: Representation? = null,
    /** Transforms the word itself implies: a scalogram is a picture made by wavelets. */
    val through: List<String> = emptyList()
)

object Goals {
    fun parse(raw: String?): Goal? = when (raw?.trim()?.lowercase()) {
        "text", "words", "transcript", "txt" -> Goal(Types.TEXT, Representations.UTF8)
        "notes", "note names", "names", "notation" -> Goal(Types.NOTES, Representations.NOTATION)
        "midi", "mid", "melody", "music" -> Goal(Types.NOTES, Representations.SMF)
        "image", "picture", "photo" -> Goal(Types.PICTURE)
        "png" -> Goal(Types.PICTURE, Representations.PNG)
        "jpg", "jpeg" -> Goal(Types.PICTURE, Representations.JPEG)
        "webp" -> Goal(Types.PICTURE, Representations.WEBP)
        "spectrogram", "waterfall" -> Goal(Types.PICTURE, Representations.PNG, through = listOf("render_field"))
        "scalogram", "wavelets" -> Goal(Types.PICTURE, Representations.PNG, through = listOf("cwt", "render_field"))
        "audio", "sound", "speech", "voice", "recording" -> Goal(Types.AUDIO)
        "wav" -> Goal(Types.AUDIO, Representations.WAV)
        "video", "film", "movie", "mp4" -> Goal(Types.VIDEO)
        else -> Types.byId(raw)?.let { Goal(it) }
    }
}

/** The ways of taking what arrived, most specific first. */
object Interpret {

    /** Whether text is a melody written as note names rather than prose. */
    fun looksLikeNotes(text: String): Boolean {
        val tokens = text.split(Regex("[\\s,|;]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val musical = tokens.count { t -> NoteText.parse(t).isNotEmpty() || t.all { it == '-' } || t.startsWith("-:") }
        return musical * 10 >= tokens.size * 8
    }

    /** Text as note names when it reads as them, and always as text. */
    fun text(s: String): List<Interpretation> = buildList {
        if (looksLikeNotes(s)) {
            add(Interpretation(Types.NOTES, Representations.NOTATION, "note names", inferred = true, facts = setOf("notation")))
        }
        add(Interpretation(Types.TEXT, Representations.UTF8))
    }

    /** A file as what its form says it is, with what its own history tells about it. */
    fun file(representation: Representation, facts: Set<String> = emptySet()): List<Interpretation> =
        listOfNotNull(Types.byId(representation.type)?.let { Interpretation(it, representation, facts = facts) })

    /**
     * What a history says about the thing it describes: a picture whose last step was
     * drawing a field is a spectrogram, one whose last step was setting text is a
     * picture of text.
     */
    fun factsFrom(history: Provenance?): Set<String> =
        history?.records?.lastOrNull()?.let { Transforms.byId(it.transform)?.produces }.orEmpty()

    /** The parameters of the last step in [history] that was [transform] — how a picture was drawn, say. */
    fun carried(history: Provenance?, transform: String): Map<String, String> =
        history?.records?.lastOrNull { it.transform == transform }?.parameters
            ?: history?.earlier?.let { carried(it, transform) }
            ?: emptyMap()
}

/** Where a parameter's value came from — which is what makes a result a fact or an assumption. */
enum class Given { USER, CARRIED, DEFAULT }

object Params {
    /**
     * For each of [names]: the user's value, else the one the input carried with it,
     * else the default — and which it was. Only defaults are assumptions.
     */
    fun resolve(
        names: List<String>,
        user: (String) -> String?,
        carried: Map<String, String>,
        defaults: Map<String, String>
    ): Map<String, Pair<String, Given>> = names.associateWith { n ->
        user(n)?.takeIf { it.isNotBlank() }?.let { it to Given.USER }
            ?: carried[n]?.let { it to Given.CARRIED }
            ?: (defaults[n].orEmpty() to Given.DEFAULT)
    }

    fun values(resolved: Map<String, Pair<String, Given>>): Map<String, String> = resolved.mapValues { it.value.first }

    fun assumed(resolved: Map<String, Pair<String, Given>>): List<String> =
        resolved.filterValues { it.second == Given.DEFAULT }.keys.toList()
}
