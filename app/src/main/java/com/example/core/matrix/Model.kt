package com.example.core.matrix

/**
 * What a piece of information is — its meaning, apart from any format it is kept in
 * or any way it travels.
 *
 * A PNG is not a type; it is how a [Types.PICTURE] is kept. A spectrogram is not a
 * type either; it is how a [Types.TIME_FREQUENCY] field is shown — and a picture
 * that looks like one is only a picture until something reads it as one, under
 * assumptions that are part of what comes out.
 */
data class DataType(val id: String, val label: String, val help: String)

object Types {
    val TEXT = DataType("text", "Text", "Words: what is written or said.")
    val PICTURE = DataType(
        "picture", "Picture",
        "What something looks like: a field of colour, meaning nothing more until it is read as something."
    )
    val AUDIO = DataType("audio", "Sound", "Pressure over time: a recording, a voice, music.")
    val TIME_FREQUENCY = DataType(
        "time_frequency", "Time–frequency field",
        "How much of each frequency there is at each moment — what a spectrogram or a scalogram shows."
    )
    val NOTES = DataType("notes", "Notes", "Pitches with their times, lengths and loudness, and the words sung to them.")
    val VIDEO = DataType("video", "Video", "Pictures and sound over time.")
    val ACCELERATION = DataType(
        "acceleration", "Acceleration",
        "Force on the phone along its three axes, gravity included, many times a second."
    )
    val CONTROL = DataType("control", "Control value", "One or a few numbers over time: a steering axis, a fader, a knob.")
    val EVENT = DataType("event", "Event", "That something happened, and when.")

    val ALL = listOf(TEXT, PICTURE, AUDIO, TIME_FREQUENCY, NOTES, VIDEO, ACCELERATION, CONTROL, EVENT)

    fun byId(id: String?): DataType? = ALL.firstOrNull { it.id == id?.trim()?.lowercase() }
}

/**
 * How information of a type is laid out to be kept or sent. The same bytes can be
 * more than one: "C4 E4 G4" is plain text, and it is also notes written out — which
 * one it is is a question about the content, answered by trying ([Interpretation]).
 */
data class Representation(
    val id: String,
    /** The [DataType] this is a way of keeping. */
    val type: String,
    val label: String,
    val mime: String? = null,
    val extension: String? = null,
    /** Keeping something this way loses part of it (JPEG, MP3); null when that is not known. */
    val lossy: Boolean? = false,
    /** Only ever held in memory — it has no file form of its own. */
    val inMemory: Boolean = false
)

object Representations {
    val UTF8 = Representation("utf8", "text", "Plain text", "text/plain", "txt")
    val NOTATION = Representation("notation", "notes", "Note names (C4 E4 G4)", "text/plain", "txt")
    val SMF = Representation("smf", "notes", "MIDI file", "audio/midi", "mid")
    val PNG = Representation("png", "picture", "PNG", "image/png", "png")
    val JPEG = Representation("jpeg", "picture", "JPEG", "image/jpeg", "jpg", lossy = true)
    val WEBP = Representation("webp", "picture", "WebP", "image/webp", "webp", lossy = null)
    val GIF = Representation("gif", "picture", "GIF", "image/gif", "gif", lossy = null)
    val WAV = Representation("wav", "audio", "WAV", "audio/wav", "wav")
    val MP3 = Representation("mp3", "audio", "MP3", "audio/mpeg", "mp3", lossy = true)
    val AAC = Representation("aac", "audio", "AAC", "audio/mp4", "m4a", lossy = true)
    val OGG = Representation("ogg", "audio", "Ogg", "audio/ogg", "ogg", lossy = true)
    val MP4 = Representation("mp4", "video", "MP4", "video/mp4", "mp4", lossy = true)
    val WEBM = Representation("webm", "video", "WebM", "video/webm", "webm", lossy = true)
    val FIELD = Representation("field", "time_frequency", "Magnitudes by moment and frequency", inMemory = true)
    val SAMPLES = Representation("samples", "acceleration", "Readings as they arrive", inMemory = true)
    val VALUES = Representation("values", "control", "Values as they arrive", inMemory = true)
    val EVENTS = Representation("events", "event", "Events as they happen", inMemory = true)

    val ALL = listOf(UTF8, NOTATION, SMF, PNG, JPEG, WEBP, GIF, WAV, MP3, AAC, OGG, MP4, WEBM, FIELD, SAMPLES, VALUES, EVENTS)

    fun byId(id: String?): Representation? = ALL.firstOrNull { it.id == id?.trim()?.lowercase() }

    /** The file forms a type can be written as, in order of preference. */
    fun writable(type: String): List<Representation> = when (type) {
        "text" -> listOf(UTF8)
        "notes" -> listOf(SMF, NOTATION)
        "picture" -> listOf(PNG, JPEG, WEBP)
        "audio" -> listOf(WAV)
        else -> emptyList()
    }

    /** What a MIME type says it is; null when it says nothing this app can use. */
    fun ofMime(mime: String?): Representation? {
        val m = mime?.lowercase()?.substringBefore(';')?.trim() ?: return null
        return when {
            m == "audio/midi" || m == "audio/x-midi" || m == "audio/mid" -> SMF
            m == "image/png" -> PNG
            m == "image/jpeg" || m == "image/jpg" -> JPEG
            m == "image/webp" -> WEBP
            m == "image/gif" -> GIF
            m.startsWith("image/") -> PNG.copy(id = "image", label = "Picture ($m)", mime = m, extension = null, lossy = null)
            m == "audio/wav" || m == "audio/x-wav" || m == "audio/wave" -> WAV
            m == "audio/mpeg" || m == "audio/mp3" -> MP3
            m == "audio/mp4" || m == "audio/aac" || m == "audio/m4a" || m == "audio/x-m4a" -> AAC
            m == "audio/ogg" || m == "audio/opus" -> OGG
            m.startsWith("audio/") -> MP3.copy(id = "audio", label = "Sound ($m)", mime = m, extension = null, lossy = null)
            m == "video/mp4" -> MP4
            m == "video/webm" -> WEBM
            m.startsWith("video/") -> MP4.copy(id = "video", label = "Video ($m)", mime = m, extension = null, lossy = null)
            m.startsWith("text/") -> UTF8
            else -> null
        }
    }

    /** What a file is by its first bytes, for files that arrive without a type. */
    fun sniff(b: ByteArray): Representation? {
        fun starts(vararg sig: Int) = b.size >= sig.size && sig.indices.all { (b[it].toInt() and 0xFF) == sig[it] }
        fun ascii(at: Int, s: String) = b.size >= at + s.length && String(b, at, s.length, Charsets.US_ASCII) == s
        return when {
            ascii(0, "MThd") -> SMF
            starts(0x89, 0x50, 0x4E, 0x47) -> PNG
            starts(0xFF, 0xD8, 0xFF) -> JPEG
            ascii(0, "GIF8") -> GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> WEBP
            ascii(0, "RIFF") && ascii(8, "WAVE") -> WAV
            ascii(0, "ID3") || starts(0xFF, 0xFB) || starts(0xFF, 0xF3) -> MP3
            ascii(0, "OggS") -> OGG
            ascii(4, "ftyp") -> if (ascii(8, "M4A")) AAC else MP4
            else -> null
        }
    }
}

/** How information arrives or leaves: the path, not the content. */
data class Transport(
    val id: String,
    val label: String,
    /** Whether what goes this way leaves the phone; null when it depends on where it is pointed. */
    val leavesDevice: Boolean?
)

object Transports {
    val FIELD = Transport("field", "The text field", false)
    val SELECTION = Transport("selection", "The selected text", false)
    val CLIPBOARD = Transport("clipboard", "The clipboard", false)
    val FILE = Transport("file", "A file", false)
    val SCREEN = Transport("screen", "The screen, read by accessibility", false)
    val MICROPHONE = Transport("microphone", "The microphone", false)
    val CAMERA = Transport("camera", "The camera", false)
    val SENSOR = Transport("sensor", "A sensor", false)
    val SYSTEM = Transport("system", "The system's announcements", false)
    val PROVIDER = Transport("provider", "A provider's API", true)
    val UDP = Transport("udp", "UDP", null)
    val TCP = Transport("tcp", "TCP", null)
    val BLE = Transport("ble", "Bluetooth LE", null)
    val INTENT = Transport("intent", "Another app, by intent", false)
    val MEMORY = Transport("memory", "Inside the app", false)

    val ALL = listOf(FIELD, SELECTION, CLIPBOARD, FILE, SCREEN, MICROPHONE, CAMERA, SENSOR, SYSTEM, PROVIDER, UDP, TCP, BLE, INTENT, MEMORY)

    fun byId(id: String?): Transport? = ALL.firstOrNull { it.id == id?.trim()?.lowercase() }
}

/**
 * One way of taking what arrived: as [representation] of [type]. Plain text that
 * reads as note names has two — as text, and as notes — and the more specific one
 * comes first, because a string that parses as a melody almost certainly is one.
 */
data class Interpretation(
    val type: DataType,
    val representation: Representation,
    /** "as written", or how it was read ("read as note names"). */
    val how: String = "as written",
    /** True when taking it this way is a judgement about the content, not a fact about the file. */
    val inferred: Boolean = false,
    /** What is known to be true of the content: "lyrics", "notation"… — for transforms that need it. */
    val facts: Set<String> = emptySet()
)
