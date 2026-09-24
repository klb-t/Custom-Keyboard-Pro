package com.example.core.convert

import com.example.core.discovery.AiCapability

/** What something is, as far as converting it goes. */
enum class Kind {
    TEXT, IMAGE, AUDIO, MIDI, VIDEO;

    companion object {
        fun parse(raw: String?): Kind? = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
            ?: when (raw?.trim()?.lowercase()) {
                "picture", "photo", "png", "jpg", "jpeg", "webp", "spectrogram" -> IMAGE
                "sound", "speech", "voice", "wav", "mp3", "recording" -> AUDIO
                "music", "mid", "melody" -> MIDI
                "words", "transcript", "txt", "notes" -> TEXT
                "movie", "film", "mp4" -> VIDEO
                else -> null
            }

        /** From a MIME type, the way the clipboard and files describe themselves. */
        fun ofMime(mime: String?): Kind? {
            val m = mime?.lowercase() ?: return null
            return when {
                m == "audio/midi" || m == "audio/x-midi" || m == "audio/mid" -> MIDI
                m.startsWith("image/") -> IMAGE
                m.startsWith("audio/") -> AUDIO
                m.startsWith("video/") -> VIDEO
                m.startsWith("text/") -> TEXT
                else -> null
            }
        }
    }
}

/** Where a step runs, which decides whether it can run at all and what it costs. */
sealed interface Where {
    /** Arithmetic in the app. Free, offline, private. */
    data object Here : Where

    /** The phone's own services — its speech engine. Free, offline where installed. */
    data object Phone : Where

    /** A provider that serves this capability, with a key. Sends the content away. */
    data class Provider(val capability: String) : Where
}

/** One conversion the app knows how to do. */
data class Step(
    val id: String,
    val from: Kind,
    val to: Kind,
    val where: Where,
    val label: String,
    /** Rough price of the step: time, money, privacy. The planner minimises the sum. */
    val cost: Int
)

/**
 * Everything into everything, as a graph.
 *
 * Each step turns one kind of thing into another — a picture into text, text into
 * speech, notes into sound — and a conversion nobody wrote is a path through steps
 * somebody did: a spectrogram picture heard as a tune is picture → notes → sound; a
 * sung recording shown as note names is sound → notes → text. The planner finds the
 * cheapest path among the steps that can run right now, preferring what happens on
 * the phone to what has to be sent away. A new step makes every path through it
 * possible at once, which is the only way "everything to everything" stays finite.
 *
 * Some steps reinterpret rather than convert: text read as note names, notes written
 * out as names. Those are only right when the content really is notes, so whether
 * they can be used is the caller's decision about the content at hand ([Rules]), not
 * the graph's.
 */
object ConvertGraph {

    const val OCR = "ocr"
    const val TRANSCRIBE = "transcribe"
    const val SPEAK = "speak"
    const val SPEAK_PROVIDER = "speak_provider"
    const val DRAW = "draw"
    const val TYPESET = "typeset"
    const val FILM = "film"
    const val SPECTROGRAM = "spectrogram"
    const val SPECTROGRAM_NOTES = "spectrogram_notes"
    const val SONIFY = "sonify"
    const val PIANO_ROLL = "piano_roll"
    const val SYNTH = "synth"
    const val NOTES_IN = "notes_in"
    const val NOTES_OUT = "notes_out"
    const val PITCH = "pitch"
    const val SOUNDTRACK = "soundtrack"
    const val FRAME = "frame"

    val STEPS: List<Step> = listOf(
        Step(OCR, Kind.IMAGE, Kind.TEXT, Where.Provider(AiCapability.OCR), "read the text in the picture", 5),
        Step(TRANSCRIBE, Kind.AUDIO, Kind.TEXT, Where.Provider(AiCapability.TRANSCRIBE), "write down what is said", 5),
        Step(SPEAK, Kind.TEXT, Kind.AUDIO, Where.Phone, "read it aloud to a file", 3),
        Step(SPEAK_PROVIDER, Kind.TEXT, Kind.AUDIO, Where.Provider(AiCapability.SPEECH), "read it aloud with a provider's voice", 6),
        Step(TYPESET, Kind.TEXT, Kind.IMAGE, Where.Here, "set the text as a picture", 3),
        Step(DRAW, Kind.TEXT, Kind.IMAGE, Where.Provider(AiCapability.IMAGE), "make a picture of what it describes", 8),
        Step(FILM, Kind.TEXT, Kind.VIDEO, Where.Provider(AiCapability.VIDEO), "make a video of what it describes", 12),
        Step(SPECTROGRAM, Kind.AUDIO, Kind.IMAGE, Where.Here, "draw its spectrogram", 1),
        Step(SPECTROGRAM_NOTES, Kind.IMAGE, Kind.MIDI, Where.Here, "read the picture as a spectrogram of notes", 1),
        Step(SONIFY, Kind.IMAGE, Kind.AUDIO, Where.Here, "play the picture as a spectrogram", 1),
        Step(PIANO_ROLL, Kind.MIDI, Kind.IMAGE, Where.Here, "draw the notes as a spectrogram", 1),
        Step(SYNTH, Kind.MIDI, Kind.AUDIO, Where.Here, "play the notes", 1),
        Step(NOTES_IN, Kind.TEXT, Kind.MIDI, Where.Here, "notes from their names (C4 E4 G4)", 1),
        Step(NOTES_OUT, Kind.MIDI, Kind.TEXT, Where.Here, "the notes' names", 1),
        Step(PITCH, Kind.AUDIO, Kind.MIDI, Where.Here, "the melody in the sound", 1),
        Step(SOUNDTRACK, Kind.VIDEO, Kind.AUDIO, Where.Here, "take the sound out of the video", 1),
        Step(FRAME, Kind.VIDEO, Kind.IMAGE, Where.Here, "take a frame out of the video", 2)
    )

    fun byId(id: String): Step? = STEPS.firstOrNull { it.id == id.trim().lowercase() }

    /**
     * The cheapest way from [from] to [to] using only steps [usable] allows; empty when
     * nothing needs doing, null when there is no way.
     */
    fun plan(from: Kind, to: Kind, usable: (Step) -> Boolean = { true }): List<Step>? {
        if (from == to) return emptyList()
        val best = mutableMapOf(from to 0)
        val via = mutableMapOf<Kind, Step>()
        val open = mutableSetOf(from)
        val done = mutableSetOf<Kind>()
        while (open.isNotEmpty()) {
            val here = open.minBy { best.getValue(it) }
            open -= here
            done += here
            if (here == to) break
            // In the order they are listed, so a tie goes to the step listed first.
            STEPS.filter { it.from == here && it.to !in done && usable(it) }.forEach { s ->
                val c = best.getValue(here) + s.cost
                if (c < (best[s.to] ?: Int.MAX_VALUE)) {
                    best[s.to] = c
                    via[s.to] = s
                    open += s.to
                }
            }
        }
        if (to !in via) return null
        val path = mutableListOf<Step>()
        var at = to
        while (at != from) {
            val s = via.getValue(at)
            path.add(0, s)
            at = s.from
        }
        return path
    }

    /**
     * The cheapest way that goes through [through], in that order — "picture to sound,
     * but by way of notes" — or null when there is none.
     */
    fun planThrough(from: Kind, to: Kind, through: List<Step>, usable: (Step) -> Boolean = { true }): List<Step>? {
        val path = mutableListOf<Step>()
        var at = from
        for (s in through) {
            path += plan(at, s.from, usable) ?: return null
            path += s
            at = s.to
        }
        path += plan(at, to, usable) ?: return null
        return path
    }

    /** "picture → text → notes", for telling the user what is about to happen. */
    fun describe(path: List<Step>): String =
        if (path.isEmpty()) "nothing to convert"
        else (listOf(path.first().from) + path.map { it.to }).joinToString(" → ") { it.name.lowercase() }
}

/**
 * Which steps may be used for this content and this request — the part of planning
 * that depends on what is being converted, kept apart from the graph so it can be
 * tested without a phone.
 */
object Rules {

    /** Whether text is a melody written as note names rather than prose. */
    fun looksLikeNotes(text: String): Boolean {
        val tokens = text.split(Regex("[\\s,|;]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val musical = tokens.count { t -> NoteText.parse(t).isNotEmpty() || t.all { it == '-' } || t.startsWith("-:") }
        return musical * 10 >= tokens.size * 8
    }

    /**
     * Whether [step] may be used: never when [avoided], always when [requested] (given
     * [available] says it can run), and the reinterpreting steps only when the content
     * is what they read — text into notes only when the text is notes, notes into
     * names only when the input was a MIDI file to begin with.
     */
    fun allows(
        step: Step,
        input: Kind,
        inputIsNotes: Boolean,
        requested: Set<String>,
        avoided: Set<String>,
        available: (Step) -> Boolean
    ): Boolean {
        if (step.id in avoided) return false
        if (!available(step)) return false
        if (step.id in requested) return true
        return when (step.id) {
            ConvertGraph.NOTES_IN -> inputIsNotes
            ConvertGraph.NOTES_OUT -> input == Kind.MIDI
            else -> true
        }
    }

    /** "use=pitch,notes_out" or "avoid=typeset" into step ids, ignoring what does not exist. */
    fun ids(raw: String?): List<String> =
        raw.orEmpty().split(Regex("[\\s,;+>]+")).mapNotNull { ConvertGraph.byId(it)?.id }

    /** What a "to=" asks for besides a kind: a file format, or note names. */
    fun format(raw: String?): String? = when (val r = raw?.trim()?.lowercase()) {
        "wav", "png", "jpg", "jpeg", "webp" -> if (r == "jpeg") "jpg" else r
        else -> null
    }

    /** "to=notes" means note names: text, by way of reading notes out. */
    fun wantsNoteNames(raw: String?): Boolean = raw?.trim()?.lowercase() in setOf("notes", "note names", "names")
}
