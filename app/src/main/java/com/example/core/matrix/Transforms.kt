package com.example.core.matrix

import com.example.core.discovery.AiCapability

/** How an output follows from an input. */
enum class Mapping {
    /** The same input always gives the same output, by calculation. */
    DETERMINISTIC,

    /** Chance is part of it: run twice, get two answers. */
    STOCHASTIC,

    /** An estimate of something the input only suggests — reading, recognising, detecting. */
    INFERENTIAL,

    /** Made up to fit the input: what the input does not say is invented. */
    GENERATIVE
}

/** Whether the input can be had back from the output. */
enum class Invertibility {
    EXACT,

    /** Only given something else as well — the layout a picture was drawn in, the phase. */
    CONDITIONAL,

    /** Something like it can be had back, not it. */
    APPROXIMATE,
    IMPOSSIBLE
}

/** What happens to some part of the information on the way through. */
enum class ChangeKind {
    PRESERVED,
    DISCARDED,

    /** Many values become fewer: frames, bins, semitones. */
    AGGREGATED,

    /** Added by the method itself rather than taken from the input: a voice, a phase, a font. */
    SYNTHESIZED,

    /** Taken from somewhere other than the input: a database, a model's knowledge. */
    EXTERNALLY_ADDED
}

data class Change(val kind: ChangeKind, val what: String)

/** A rough amount, for comparing ways rather than predicting them. */
enum class Level(val weight: Double) { NONE(0.0), LOW(1.0), MEDIUM(3.0), HIGH(9.0) }

/**
 * What a way of doing a transform costs. Null is "not known", which is scored as
 * middling and shown as unknown — never as free.
 */
data class Cost(
    val latency: Level? = null,
    val compute: Level? = null,
    val energy: Level? = null,
    val network: Level? = null,
    val money: Level? = null
)

/** Where a transform is carried out, which decides what it exposes. */
enum class Site {
    /** Arithmetic in the app. Offline, private. */
    APP,

    /** A service of the phone's own — its speech engine, its decoders. */
    PHONE,

    /** Somewhere else, reached over the network: the content leaves the phone. */
    PROVIDER
}

/**
 * What a transform does to information — the meaning of an edge in the graph,
 * independent of who carries it out. "Write down what is said" is one transform
 * whether the phone does it or a provider does; how much it costs and where the
 * recording goes are facts about each [Implementation], not about the transform.
 */
data class TransformSpec(
    val id: String,
    val from: String,
    val to: String,
    val label: String,
    val mapping: Mapping,
    val changes: List<Change>,
    val invertibility: Invertibility,
    /** The transform that undoes it, as far as [invertibility] allows. */
    val inverse: String? = null,
    /** What has to be true for the output to mean what it says. */
    val assumptions: List<String> = emptyList(),
    /** Parameters that describe the input and have to come from somewhere: the input's own metadata, the user, or an assumption. */
    val requires: List<String> = emptyList(),
    /** Facts the input must be known to have — checked against the actual input, never assumed. */
    val needsFacts: Set<String> = emptySet(),
    /**
     * Facts about the input that make this transform meaningless — reading text out of
     * a spectrogram, transcribing sine tones. Asking for the way explicitly overrides
     * it: hiding text in a spectrogram is reading a picture of text as one, on purpose.
     */
    val pointlessFor: Set<String> = emptySet(),
    /** Facts known about what this produces, for the next step to check. */
    val produces: Set<String> = emptySet(),
    /** Whether a run reports how sure it is. False is not "sure": it is "does not say". */
    val reportsConfidence: Boolean = false
)

/** One way of carrying out a transform. */
data class Implementation(
    val id: String,
    val transform: String,
    val site: Site,
    val label: String,
    val cost: Cost,
    /** For [Site.PROVIDER]: the capability a provider must serve — never a provider's name. */
    val capability: String? = null,
    val version: String = "1"
) {
    val leavesDevice: Boolean get() = site == Site.PROVIDER
}

/**
 * The transforms IO Matrix knows, and the ways it can carry each out. Every one of
 * them came from a real use: converting what was copied, reading a spectrogram,
 * steering with the phone. A new one is an entry here and an executor; the planner,
 * the provenance and the settings pick it up without changes of their own.
 */
object Transforms {

    private fun d(kind: ChangeKind, vararg what: String) = what.map { Change(kind, it) }
    private val D = ChangeKind.DISCARDED
    private val A = ChangeKind.AGGREGATED
    private val S = ChangeKind.SYNTHESIZED
    private val P = ChangeKind.PRESERVED

    val ALL: List<TransformSpec> = listOf(
        // Reading and writing words.
        TransformSpec(
            "ocr", "picture", "text", "read the text in the picture", Mapping.INFERENTIAL,
            d(D, "layout", "typography", "everything that is not text"), Invertibility.APPROXIMATE, inverse = "typeset",
            assumptions = listOf("the picture shows text"), pointlessFor = setOf("spectrogram"), reportsConfidence = false
        ),
        TransformSpec(
            "transcribe", "audio", "text", "write down what is said", Mapping.INFERENTIAL,
            d(D, "voice", "intonation", "timing", "sounds that are not speech"), Invertibility.APPROXIMATE, inverse = "speak",
            assumptions = listOf("the sound is speech"), pointlessFor = setOf("tones")
        ),
        TransformSpec(
            "speak", "text", "audio", "read it aloud", Mapping.DETERMINISTIC,
            d(S, "voice", "intonation", "timing"), Invertibility.APPROXIMATE, inverse = "transcribe", produces = setOf("speech")
        ),
        TransformSpec(
            "typeset", "text", "picture", "set the text as a picture", Mapping.DETERMINISTIC,
            d(S, "font", "layout"), Invertibility.CONDITIONAL, inverse = "ocr", produces = setOf("text_picture")
        ),
        TransformSpec(
            "draw", "text", "picture", "make a picture of what it describes", Mapping.GENERATIVE,
            d(S, "everything the text does not say"), Invertibility.IMPOSSIBLE
        ),
        TransformSpec(
            "film", "text", "video", "make a video of what it describes", Mapping.GENERATIVE,
            d(S, "everything the text does not say"), Invertibility.IMPOSSIBLE
        ),

        // Sound, time and frequency.
        TransformSpec(
            "stft", "audio", "time_frequency", "measure its frequencies moment by moment (short-time Fourier)",
            Mapping.DETERMINISTIC, d(D, "phase") + d(A, "time into frames", "frequency into rows"),
            Invertibility.APPROXIMATE, inverse = "resynthesize"
        ),
        TransformSpec(
            "cwt", "audio", "time_frequency", "measure its frequencies with wavelets (Morlet scalogram)",
            Mapping.DETERMINISTIC, d(D, "phase") + d(A, "time into frames", "frequency into scales"),
            Invertibility.APPROXIMATE, inverse = "resynthesize"
        ),
        TransformSpec(
            "render_field", "time_frequency", "picture", "draw it as a spectrogram",
            Mapping.DETERMINISTIC, d(D, "levels more than 80 dB below the loudest", "precision beyond 256 shades", "absolute level"),
            Invertibility.CONDITIONAL, inverse = "read_field", produces = setOf("spectrogram")
        ),
        TransformSpec(
            "read_field", "picture", "time_frequency", "read the picture as a spectrogram",
            Mapping.INFERENTIAL, d(D, "colour", "everything outside the chosen area"),
            Invertibility.CONDITIONAL, inverse = "render_field",
            assumptions = listOf("the picture is a spectrogram", "brighter means louder (or darker, when inverted)"),
            requires = listOf("time", "scale", "low", "high", "seconds"), pointlessFor = setOf("text_picture")
        ),
        TransformSpec(
            "resynthesize", "time_frequency", "audio", "play the field: a sine for every row",
            Mapping.DETERMINISTIC, d(S, "phase"), Invertibility.APPROXIMATE, inverse = "stft", produces = setOf("tones")
        ),
        TransformSpec(
            "extract_notes", "time_frequency", "notes", "find the notes in it",
            Mapping.INFERENTIAL, d(A, "frequency into semitones") + d(D, "timbre", "tuning between semitones", "what is quieter than the threshold"),
            Invertibility.APPROXIMATE, inverse = "notes_field"
        ),
        TransformSpec(
            "notes_field", "notes", "time_frequency", "the field pure tones of these notes would make",
            Mapping.DETERMINISTIC, d(S, "pure tones without overtones") + d(D, "words"), Invertibility.CONDITIONAL, inverse = "extract_notes"
        ),
        TransformSpec(
            "synth", "notes", "audio", "play the notes", Mapping.DETERMINISTIC,
            d(S, "timbre") + d(D, "words"), Invertibility.APPROXIMATE, produces = setOf("tones")
        ),
        TransformSpec(
            "lyrics", "notes", "text", "the words sung", Mapping.DETERMINISTIC,
            d(D, "the music"), Invertibility.IMPOSSIBLE, needsFacts = setOf("lyrics")
        ),

        // Video.
        TransformSpec(
            "soundtrack", "video", "audio", "take the sound out of the video", Mapping.DETERMINISTIC,
            d(D, "the pictures"), Invertibility.IMPOSSIBLE
        ),
        TransformSpec(
            "frame", "video", "picture", "take one frame out of the video", Mapping.DETERMINISTIC,
            d(D, "sound", "movement", "every other frame"), Invertibility.IMPOSSIBLE
        ),

        // Streams: the engine's inputs as data, before they are reduced to events.
        TransformSpec(
            "tilt", "acceleration", "control", "the angle the phone is tilted at", Mapping.DETERMINISTIC,
            d(D, "movement other than gravity's direction") + d(A, "three axes into one angle"), Invertibility.IMPOSSIBLE,
            assumptions = listOf("the phone is not accelerating much, so gravity dominates")
        ),
        TransformSpec(
            "detect_gesture", "acceleration", "event", "notice a shake, a flip or a held tilt", Mapping.INFERENTIAL,
            d(A, "a stretch of readings into one moment") + d(D, "the readings themselves"), Invertibility.IMPOSSIBLE,
            assumptions = listOf("the thresholds suit how this person moves the phone")
        ),
        TransformSpec(
            "shape", "control", "control", "calibrate, scale, dead zone, curve, smooth", Mapping.DETERMINISTIC,
            d(D, "small movements inside the dead zone", "jitter smoothed away") + d(P, "direction"),
            Invertibility.APPROXIMATE
        ),
        TransformSpec(
            "threshold", "control", "event", "an event when the value crosses a level", Mapping.DETERMINISTIC,
            d(A, "a value into a crossing") + d(D, "the value between crossings"), Invertibility.IMPOSSIBLE
        )
    )

    private val cheap = Cost(Level.LOW, Level.LOW, Level.LOW, Level.NONE, Level.NONE)
    private val work = Cost(Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, Level.NONE, Level.NONE)
    private val remote = Cost(Level.MEDIUM, Level.NONE, Level.LOW, Level.MEDIUM, null)

    private fun here(t: String, label: String = "in the app", cost: Cost = cheap) = Implementation("$t.app", t, Site.APP, label, cost)
    private fun away(t: String, capability: String) =
        Implementation("$t.provider", t, Site.PROVIDER, "a provider set up to ${AiCapability.label(capability).lowercase()}", remote, capability)

    val IMPLEMENTATIONS: List<Implementation> = listOf(
        away("ocr", AiCapability.OCR),
        away("transcribe", AiCapability.TRANSCRIBE),
        Implementation("speak.phone", "speak", Site.PHONE, "the phone's own voice", Cost(Level.MEDIUM, Level.LOW, Level.LOW, Level.NONE, Level.NONE)),
        away("speak", AiCapability.SPEECH),
        here("typeset"),
        away("draw", AiCapability.IMAGE),
        away("film", AiCapability.VIDEO),
        here("stft"),
        here("cwt", cost = work),
        here("render_field"),
        here("read_field"),
        here("resynthesize", cost = work),
        here("extract_notes"),
        here("notes_field"),
        here("synth"),
        here("lyrics"),
        Implementation("soundtrack.phone", "soundtrack", Site.PHONE, "the phone's decoders", cheap),
        Implementation("frame.phone", "frame", Site.PHONE, "the phone's decoders", cheap),
        here("tilt"),
        here("detect_gesture"),
        here("shape"),
        here("threshold")
    )

    fun byId(id: String?): TransformSpec? = ALL.firstOrNull { it.id == id?.trim()?.lowercase() }

    fun implementation(id: String?): Implementation? = IMPLEMENTATIONS.firstOrNull { it.id == id?.trim()?.lowercase() }

    fun implementationsOf(transform: String): List<Implementation> = IMPLEMENTATIONS.filter { it.transform == transform }

    /** Names a user may write for a transform or one way of doing it: "ocr", "speak.phone", "stft". */
    fun resolve(name: String): Pair<TransformSpec, Implementation?>? {
        implementation(name)?.let { impl -> return byId(impl.transform)!! to impl }
        return byId(name)?.let { it to null }
    }
}
