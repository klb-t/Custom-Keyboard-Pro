package com.example.io

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import com.example.core.clipboard.ClipStore
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.convert.ConvertGraph
import com.example.core.convert.Convertible
import com.example.core.convert.Kind
import com.example.core.convert.Luma
import com.example.core.convert.Midi
import com.example.core.convert.NoteText
import com.example.core.convert.PasteConversion
import com.example.core.convert.PicturePrep
import com.example.core.convert.Rules
import com.example.core.convert.SingAlong
import com.example.core.convert.Spectro
import com.example.core.convert.Step
import com.example.core.convert.Synth
import com.example.core.convert.TimeAxis
import com.example.core.convert.Wav
import com.example.core.convert.Where
import com.example.core.discovery.AiCapability
import com.example.core.discovery.CallEngine
import com.example.core.discovery.CallInput
import com.example.core.discovery.ProviderCatalog
import com.example.core.discovery.ProviderProfiles
import com.example.core.discovery.ProviderSpec
import com.example.core.io.Command
import com.example.core.speech.SpeechText
import com.example.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder

/** Something being converted: text, or bytes of a kind. */
sealed interface Item {
    val kind: Kind

    data class Text(val text: String) : Item {
        override val kind: Kind get() = Kind.TEXT
    }

    /** [madeBy] is the step that produced it, for the next step to take a hint from. */
    class Bytes(override val kind: Kind, val bytes: ByteArray, val mime: String, val madeBy: String = "") : Item
}

/**
 * Carries out a conversion the graph planned: fetches the input, runs each step,
 * delivers the result.
 *
 * Text results go into the field when there is one, and onto the clipboard as well;
 * everything else becomes a file on the clipboard, ready to paste or share. Steps that
 * run on the phone never send anything anywhere; steps that need a provider are only
 * planned when one is set up, and the plan — naming what gets sent where — is told to
 * the user before it runs.
 */
object ConvertRunner {

    private const val TAG = "Convert"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Whether a step can run at all right now: on the phone always, at a provider when one is set up. */
    fun available(step: Step, settings: Settings): Boolean = when (val w = step.where) {
        Where.Here, Where.Phone -> true
        is Where.Provider -> providerFor(step, settings) != null
    }

    /** Which provider a step would use: the reading-aloud one for speech when set, else the first set up. */
    private fun providerFor(step: Step, settings: Settings): ProviderSpec? {
        val w = step.where as? Where.Provider ?: return null
        fun ready(p: ProviderSpec) = !p.needsKey || ProviderProfiles.keyFor(p.id, settings).isNotBlank()
        // Reading pictures and recordings has its own chooser, shared with pasting.
        if (step.id == ConvertGraph.OCR) return PasteConversion.providerFor(Convertible.PICTURE, settings)
        if (step.id == ConvertGraph.TRANSCRIBE) return PasteConversion.providerFor(Convertible.RECORDING, settings)
        val described = ProviderCatalog.serving(w.capability, settings)
            .filter { ready(it) && it.capability(w.capability)?.call != null }
        if (step.id == ConvertGraph.SPEAK_PROVIDER) {
            described.firstOrNull { it.id == settings.ttsProvider }?.let { return it }
        }
        return described.firstOrNull()
    }

    /** Runs "convert to=… source=…", telling [host] what happens. */
    fun run(context: Context, command: Command, host: PerformerHost) {
        val rawTarget = command.arg("to")
        val noteNames = Rules.wantsNoteNames(rawTarget)
        val target = if (noteNames) Kind.TEXT else Kind.parse(rawTarget)
        if (target == null) {
            host.notice("Convert: to what? text, notes, image, audio, midi, video — or wav, png, jpg")
            return
        }
        val format = Rules.format(rawTarget)
        val settings = SettingsStore.current
        val source = command.arg("source")?.lowercase() ?: "auto"
        // Text from the field is read here, on the main thread, where the field lives.
        val typed = if (source != "clipboard") {
            host.readable(if (source == "auto") "selection" else source)?.text?.takeIf { it.isNotBlank() }
        } else null
        scope.launch {
            try {
                var input: Item = typed?.let { Item.Text(it) }
                    ?: withContext(Dispatchers.IO) { fromClipboard(context, settings) }
                    ?: run {
                        host.notice("Convert: nothing to convert — copy something first, or select text")
                        return@launch
                    }
                Kind.parse(command.arg("from"))?.let { forced ->
                    if (input is Item.Bytes && forced != input.kind) {
                        val b = input as Item.Bytes
                        input = Item.Bytes(forced, b.bytes, b.mime)
                    }
                }

                val requested = Rules.ids(command.arg("use")) + if (noteNames) listOf(ConvertGraph.NOTES_OUT) else emptyList()
                val avoided = Rules.ids(command.arg("avoid")).toSet()
                val isNotes = (input as? Item.Text)?.let { Rules.looksLikeNotes(it.text) } ?: false
                fun allowed(available: (Step) -> Boolean): (Step) -> Boolean = { step ->
                    Rules.allows(step, input.kind, isNotes, requested.toSet(), avoided, available)
                }
                val through = requested.mapNotNull { ConvertGraph.byId(it) }
                fun planWith(available: (Step) -> Boolean) =
                    if (through.isEmpty()) ConvertGraph.plan(input.kind, target, allowed(available))
                    else ConvertGraph.planThrough(input.kind, target, through, allowed(available))

                val path = planWith { available(it, settings) }
                if (path == null) {
                    val ideal = planWith { true }
                    host.notice(
                        if (ideal == null) "No way from ${input.kind.name.lowercase()} to ${target.name.lowercase()} yet"
                        else "That needs a provider set up to " + ideal.filter { !available(it, settings) }
                            .joinToString(" and ") { it.label }
                    )
                    return@launch
                }
                val reformat = format != null && !matchesFormat(input, format)
                if (path.isEmpty() && !reformat) {
                    host.notice("It already is ${target.name.lowercase()}")
                    return@launch
                }
                val sentTo = path.mapNotNull { s -> providerFor(s, settings)?.label }.distinct()
                host.notice(
                    "Converting: " + (if (path.isEmpty()) "${input.kind.name.lowercase()} → $format" else ConvertGraph.describe(path)) +
                        if (sentTo.isEmpty()) " (on the phone)" else " (sent to ${sentTo.joinToString()})"
                )
                var item: Item = input
                path.forEachIndexed { i, step ->
                    val next = path.getOrNull(i + 1)
                    val current = item
                    item = withContext(Dispatchers.IO) { runStep(context, step, next, current, command, settings) }
                }
                if (format != null && !matchesFormat(item, format)) {
                    val current = item
                    item = withContext(Dispatchers.IO) { reencode(context, current, format) }
                }
                deliver(context, item, host)
            } catch (e: Exception) {
                AppLogger.e(TAG, "conversion failed", e)
                host.notice("Conversion failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------------
    // In and out
    // ------------------------------------------------------------------

    private fun fromClipboard(context: Context, settings: Settings): Item? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        item.uri?.let { uri ->
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                ?: clip.description.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) }.firstOrNull() }
            val limit = settings.clipboardMaxFileMb.coerceAtLeast(1) * 1024L * 1024L
            val bytes = context.contentResolver.openInputStream(uri)?.use { s ->
                val read = s.readBytes()
                require(read.size <= limit) { "that file is over the size limit (${settings.clipboardMaxFileMb} MB)" }
                read
            } ?: return null
            val kind = Kind.ofMime(mime) ?: sniff(bytes) ?: error("not sure what that file is — say from=image, audio, midi or video")
            if (kind == Kind.TEXT) return Item.Text(String(bytes, Charsets.UTF_8))
            return Item.Bytes(kind, bytes, mime ?: "application/octet-stream")
        }
        return item.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }?.let { Item.Text(it) }
    }

    /** What a file is by its first bytes, for files that arrive without a type. */
    private fun sniff(b: ByteArray): Kind? {
        fun starts(vararg sig: Int) = b.size >= sig.size && sig.indices.all { (b[it].toInt() and 0xFF) == sig[it] }
        fun ascii(at: Int, s: String) = b.size >= at + s.length && String(b, at, s.length, Charsets.US_ASCII) == s
        return when {
            ascii(0, "MThd") -> Kind.MIDI
            starts(0x89, 0x50, 0x4E, 0x47) || starts(0xFF, 0xD8, 0xFF) || ascii(0, "GIF8") ||
                (ascii(0, "RIFF") && ascii(8, "WEBP")) -> Kind.IMAGE
            (ascii(0, "RIFF") && ascii(8, "WAVE")) || ascii(0, "ID3") || ascii(0, "OggS") || ascii(0, "fLaC") ||
                starts(0xFF, 0xFB) -> Kind.AUDIO
            ascii(4, "ftyp") -> Kind.VIDEO
            else -> null
        }
    }

    private suspend fun deliver(context: Context, item: Item, host: PerformerHost) = withContext(Dispatchers.Main) {
        when (item) {
            is Item.Text -> {
                if (item.text.isBlank()) {
                    host.notice("Converted, but it came out empty")
                    return@withContext
                }
                host.commit(item.text)
                host.copy(item.text, "Converted")
                host.notice("Done — in the field and on the clipboard")
            }
            is Item.Bytes -> {
                val file = withContext(Dispatchers.IO) {
                    File(ClipStore.dir(context), "converted_${System.currentTimeMillis()}.${extension(item)}")
                        .apply { writeBytes(item.bytes) }
                }
                val uri: Uri = ClipStore.shareUri(context, file)
                val clip = ClipData(ClipDescription("Converted", arrayOf(item.mime)), ClipData.Item(uri))
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(clip)
                host.notice(
                    "Done — ${file.name} is on the clipboard, ready to paste",
                    actionLabel = "Share"
                ) {
                    runCatching {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType(item.mime)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }

    private fun extension(item: Item.Bytes): String {
        val m = item.mime.lowercase()
        return when (item.kind) {
            Kind.MIDI -> "mid"
            Kind.IMAGE -> when { "png" in m -> "png"; "webp" in m -> "webp"; "gif" in m -> "gif"; else -> "jpg" }
            Kind.AUDIO -> when { "wav" in m -> "wav"; "ogg" in m -> "ogg"; "mp4" in m || "aac" in m -> "m4a"; else -> "mp3" }
            Kind.VIDEO -> if ("webm" in m) "webm" else "mp4"
            Kind.TEXT -> "txt"
        }
    }

    private fun matchesFormat(item: Item, format: String): Boolean {
        val m = (item as? Item.Bytes)?.mime?.lowercase() ?: return false
        return when (format) {
            "wav" -> item.kind == Kind.AUDIO && "wav" in m
            "png" -> item.kind == Kind.IMAGE && "png" in m
            "jpg" -> item.kind == Kind.IMAGE && ("jpeg" in m || "jpg" in m)
            "webp" -> item.kind == Kind.IMAGE && "webp" in m
            else -> true
        }
    }

    /** The same thing in another file format: any sound as WAV, any picture as PNG, JPEG or WebP. */
    private fun reencode(context: Context, item: Item, format: String): Item = when (format) {
        "wav" -> {
            require(item.kind == Kind.AUDIO) { "only sound becomes a WAV" }
            val (pcm, rate) = decodeAudio(context, item)
            Item.Bytes(Kind.AUDIO, Wav.write(pcm, rate), "audio/wav")
        }
        else -> {
            require(item.kind == Kind.IMAGE) { "only a picture becomes a $format" }
            val bmp = BitmapFactory.decodeByteArray(bytes(item), 0, bytes(item).size) ?: error("that picture could not be read")
            val out = ByteArrayOutputStream()
            val (codec, mime) = when (format) {
                "jpg" -> Bitmap.CompressFormat.JPEG to "image/jpeg"
                "webp" -> Bitmap.CompressFormat.WEBP to "image/webp"
                else -> Bitmap.CompressFormat.PNG to "image/png"
            }
            bmp.compress(codec, 92, out)
            bmp.recycle()
            Item.Bytes(Kind.IMAGE, out.toByteArray(), mime)
        }
    }

    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------

    /** The spectrogram layout the command describes, on top of [base]. */
    private fun layout(command: Command, base: Spectro.Layout = Spectro.Layout()): Spectro.Layout {
        fun flag(name: String): Boolean? = command.arg(name)?.trim()?.lowercase()?.let { it in setOf("on", "true", "yes", "keep") }
        val low = command.float("low")?.toDouble() ?: base.lowHz
        val high = command.float("high")?.toDouble() ?: base.highHz
        require(low > 0 && high > low) { "the frequency range needs low above 0 and high above low" }
        return base.copy(
            time = TimeAxis.parse(command.arg("time")) ?: if (flag("waterfall") == true) TimeAxis.DOWN else base.time,
            frequencyFlipped = flag("flip") ?: base.frequencyFlipped,
            logFrequency = when (command.arg("scale")?.lowercase()) {
                "linear", "lin" -> false
                "log", "logarithmic", "musical" -> true
                else -> base.logFrequency
            },
            lowHz = low,
            highHz = high,
            seconds = command.float("seconds")?.toDouble()?.takeIf { it > 0 } ?: base.seconds,
            threshold = command.float("threshold")?.coerceIn(0.01f, 1f) ?: base.threshold,
            minNote = command.float("min_note")?.toDouble() ?: base.minNote,
            harmonics = flag("harmonics") ?: base.harmonics,
            voices = command.int("voices")?.coerceAtLeast(0) ?: base.voices
        )
    }

    private fun picture(command: Command, item: Item): Luma =
        PicturePrep.prepare(luma(bytes(item)), command.arg("area"), command.arg("invert"))

    private suspend fun runStep(context: Context, step: Step, next: Step?, item: Item, command: Command, settings: Settings): Item {
        val bpm = command.float("bpm")?.toDouble()?.takeIf { it > 0 } ?: 120.0
        return when (step.id) {
            ConvertGraph.OCR -> Item.Text(PasteConversion.pictureToText(context, bytes(item), mime(item), settings))
            ConvertGraph.TRANSCRIBE ->
                Item.Text(PasteConversion.recordingToText(context, bytes(item), mime(item), settings, command.arg("language").orEmpty()))
            ConvertGraph.SPEAK -> speak(context, text(item))
            ConvertGraph.SPEAK_PROVIDER -> fromProvider(step, text(item), settings, Kind.AUDIO, "audio/mpeg")
            ConvertGraph.DRAW -> fromProvider(step, text(item), settings, Kind.IMAGE, "image/png")
            ConvertGraph.FILM -> fromProvider(step, text(item), settings, Kind.VIDEO, "video/mp4")
            ConvertGraph.TYPESET -> {
                // Read as a spectrogram next, it wants light marks on dark and one long
                // line, so the words go by in time; as a picture, a page.
                val forSound = next?.id == ConvertGraph.SONIFY || next?.id == ConvertGraph.SPECTROGRAM_NOTES
                val light = when (command.arg("ink")?.lowercase()) {
                    "light", "white" -> true
                    "dark", "black" -> false
                    else -> forSound
                }
                typeset(text(item), light, oneLine = forSound, size = command.float("size") ?: if (forSound) 120f else 44f)
            }
            ConvertGraph.SPECTROGRAM -> {
                val (pcm, rate) = decodeAudio(context, item)
                val shape = layout(command, Spectro.Layout(seconds = pcm.size / rate.toDouble()))
                png(Spectro.of(pcm, rate, shape, command.int("width") ?: 1200, command.int("height") ?: 600))
            }
            ConvertGraph.SPECTROGRAM_NOTES -> {
                val image = picture(command, item)
                val shape = layout(command, Spectro.Layout(seconds = defaultSeconds(item, image)))
                Item.Bytes(Kind.MIDI, Midi.write(Spectro.toNotes(image, shape), bpm), "audio/midi")
            }
            ConvertGraph.SONIFY -> {
                val image = picture(command, item)
                // Text hidden in sound is looked for with an ordinary spectrogram app,
                // which shows frequency linearly — so that is how it is written.
                val fromText = (item as? Item.Bytes)?.madeBy == ConvertGraph.TYPESET
                val base = if (fromText) Spectro.Layout(logFrequency = false, lowHz = 500.0, highHz = 6000.0) else Spectro.Layout()
                val shape = layout(command, base.copy(seconds = defaultSeconds(item, image)))
                Item.Bytes(Kind.AUDIO, Wav.write(Spectro.sonify(image, shape), 22050), "audio/wav")
            }
            ConvertGraph.PIANO_ROLL -> {
                val notes = Midi.read(bytes(item))
                val end = notes.maxOfOrNull { it.start + it.duration } ?: 1.0
                val shape = layout(command, Spectro.Layout(seconds = end + 0.5))
                png(Spectro.draw(notes, shape, command.int("width") ?: 1200, command.int("height") ?: 600))
            }
            ConvertGraph.PITCH -> pitch(context, item, command, settings, bpm)
            ConvertGraph.SYNTH -> Item.Bytes(Kind.AUDIO, Wav.write(Synth.render(Midi.read(bytes(item))), 22050), "audio/wav")
            ConvertGraph.NOTES_IN -> {
                val notes = NoteText.parse(text(item), bpm)
                require(notes.isNotEmpty()) { "no note names in that text — write them like C4 E4 G4" }
                Item.Bytes(Kind.MIDI, Midi.write(notes, bpm), "audio/midi")
            }
            ConvertGraph.NOTES_OUT -> {
                val song = Midi.readSong(bytes(item))
                val names = NoteText.write(song.notes, bpm)
                val words = song.lyrics.joinToString("") { it.text }.trim()
                Item.Text(if (words.isEmpty()) names else "$names\n\n$words")
            }
            ConvertGraph.SOUNDTRACK -> {
                val (pcm, rate) = decodeAudio(context, item)
                Item.Bytes(Kind.AUDIO, Wav.write(pcm, rate), "audio/wav")
            }
            ConvertGraph.FRAME -> frame(context, item, command.float("at"))
            else -> error("no way to run “${step.id}”")
        }
    }

    /** How long a picture lasts unless told: one set from text goes by at a readable pace. */
    private fun defaultSeconds(item: Item, image: Luma): Double =
        if ((item as? Item.Bytes)?.madeBy == ConvertGraph.TYPESET) (image.width / 150.0).coerceIn(1.0, 120.0) else 10.0

    private fun text(item: Item) = (item as? Item.Text)?.text ?: error("expected text")
    private fun bytes(item: Item) = (item as? Item.Bytes)?.bytes ?: error("expected a file")
    private fun mime(item: Item) = (item as? Item.Bytes)?.mime ?: "application/octet-stream"

    /**
     * The phone's own voice into a file — in pieces, since engines take a few thousand
     * characters at a time, joined back into one recording.
     */
    private suspend fun speak(context: Context, text: String): Item {
        val limit = runCatching { android.speech.tts.TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(4000)
        val pieces = SpeechText.chunks(text, (limit - 100).coerceIn(200, 3000))
        require(pieces.isNotEmpty()) { "there is nothing to say" }
        val raw = mutableListOf<ByteArray>()
        for (piece in pieces) {
            val file = File(context.cacheDir, "speak_${System.nanoTime()}.wav")
            try {
                val ok = withContext(Dispatchers.Main) { Speaker.synthesize(context, piece.text, file) }
                require(ok && file.length() > 0) { "the phone's speech engine made no audio" }
                raw += file.readBytes()
            } finally {
                file.delete()
            }
        }
        if (raw.size == 1) return Item.Bytes(Kind.AUDIO, raw[0], "audio/wav")
        val parts = raw.map { Wav.read(it) ?: error("the speech engine's files cannot be joined; convert a shorter text") }
        val (pcm, rate) = Wav.join(parts)!!
        return Item.Bytes(Kind.AUDIO, Wav.write(pcm, rate), "audio/wav")
    }

    /**
     * The melody in a recording: its spectrogram read at a semitone's resolution, the
     * overtones folded into the notes they belong to, one note at a time unless told
     * otherwise — and with lyrics=on, the words sung laid over the notes, karaoke-style.
     */
    private suspend fun pitch(context: Context, item: Item, command: Command, settings: Settings, bpm: Double): Item {
        val (pcm, rate) = decodeAudio(context, item)
        require(pcm.isNotEmpty()) { "that recording is empty" }
        val shape = layout(command, Spectro.Layout(threshold = 0.75f, harmonics = false, voices = 1, highHz = 2093.0))
            .copy(seconds = pcm.size / rate.toDouble(), time = TimeAxis.RIGHT, frequencyFlipped = false)
        val frames = (pcm.size / (rate * 0.02)).toInt().coerceIn(20, 6000)
        val semitones = Spectro.pitches(shape).count().coerceAtLeast(1)
        val image = Spectro.of(pcm, rate, shape, width = frames, height = (semitones * 6).coerceIn(60, 1200), window = Spectro.windowFor(rate))
        val notes = Spectro.toNotes(image, shape)
        val lyrics = if (command.arg("lyrics")?.lowercase() in setOf("on", "true", "yes")) {
            val step = ConvertGraph.byId(ConvertGraph.TRANSCRIBE)!!
            require(available(step, settings)) { "words need a provider set up to write down what is said" }
            val words = PasteConversion.recordingToText(context, bytes(item), mime(item), settings, command.arg("language").orEmpty())
            SingAlong.lay(words.split(Regex("\\s+")), notes)
        } else emptyList()
        return Item.Bytes(Kind.MIDI, Midi.write(notes, bpm, lyrics = lyrics), "audio/midi")
    }

    private suspend fun fromProvider(step: Step, prompt: String, settings: Settings, kind: Kind, mime: String): Item {
        val capability = (step.where as Where.Provider).capability
        val p = providerFor(step, settings) ?: error("nothing is set up to ${AiCapability.label(capability).lowercase()}")
        val params = if (capability == AiCapability.SPEECH && p.id == settings.ttsProvider && settings.ttsCloudVoice.isNotBlank()) {
            mapOf("voice" to settings.ttsCloudVoice)
        } else emptyMap()
        val model = if (capability == AiCapability.SPEECH && p.id == settings.ttsProvider) settings.ttsModel else ""
        val result = CallEngine.run(
            p, capability,
            CallInput(model = model, prompt = prompt, params = params),
            ProviderProfiles.keyFor(p.id, settings)
        ).getOrThrow()
        val bytes = result.bytes
            ?: result.text?.trim()?.let { t ->
                when {
                    // Some providers answer with where to fetch the result from.
                    t.startsWith("http://") || t.startsWith("https://") -> withContext(Dispatchers.IO) { java.net.URL(t).readBytes() }
                    else -> runCatching { Base64.decode(t.substringAfter("base64,"), Base64.DEFAULT) }.getOrNull()
                }
            }
            ?: error("${p.label} sent nothing back")
        return Item.Bytes(kind, bytes, result.mime.takeIf { it.contains('/') && !it.contains("json") } ?: mime)
    }

    private fun typeset(text: String, light: Boolean, oneLine: Boolean, size: Float): Item {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = if (light) Color.WHITE else Color.BLACK
        }
        val pad = (size / 2).toInt()
        val bmp = if (oneLine) {
            val line = text.replace(Regex("\\s+"), " ").trim()
            val w = (paint.measureText(line).toInt() + 2 * pad).coerceIn(1, 16_000)
            val fm = paint.fontMetricsInt
            val h = (fm.descent - fm.ascent + 2 * pad).coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                c.drawColor(if (light) Color.BLACK else Color.WHITE)
                c.drawText(line, pad.toFloat(), (pad - fm.ascent).toFloat(), paint)
            }
        } else {
            val width = 1080
            val layout = staticLayout(text, paint, width - 2 * pad)
            val h = (layout.height + 2 * pad).coerceIn(1, 16_000)
            Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                c.drawColor(if (light) Color.BLACK else Color.WHITE)
                c.translate(pad.toFloat(), pad.toFloat())
                layout.draw(c)
            }
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return Item.Bytes(Kind.IMAGE, out.toByteArray(), "image/png", madeBy = ConvertGraph.TYPESET)
    }

    private fun staticLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()

    private fun luma(bytes: ByteArray): Luma {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        var sample = 1
        while (options.outWidth / sample > 2000 || options.outHeight / sample > 2000) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("that picture could not be read")
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return Luma(w, h, FloatArray(w * h) { i ->
            val c = px[i]
            (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
        })
    }

    private fun png(image: Luma): Item {
        val px = IntArray(image.width * image.height) { i ->
            val v = (image.values[i] * 255).toInt().coerceIn(0, 255)
            Color.rgb(v, v, v)
        }
        val bmp = Bitmap.createBitmap(px, image.width, image.height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return Item.Bytes(Kind.IMAGE, out.toByteArray(), "image/png")
    }

    /** One frame of a video, at [atSeconds] or halfway through. */
    private fun frame(context: Context, item: Item, atSeconds: Float?): Item {
        val file = File.createTempFile("frame", ".video", context.cacheDir)
        val retriever = MediaMetadataRetriever()
        try {
            file.writeBytes(bytes(item))
            retriever.setDataSource(file.path)
            val lengthMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = atSeconds?.let { (it * 1_000_000).toLong() } ?: (lengthMs * 500)
            val bmp = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: error("no picture at that moment of the video")
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            return Item.Bytes(Kind.IMAGE, out.toByteArray(), "image/png")
        } finally {
            runCatching { retriever.release() }
            file.delete()
        }
    }

    /**
     * Any recording or video the phone can play, as mono samples: WAV read directly,
     * anything else through the phone's own decoders. Capped at ten minutes.
     */
    private fun decodeAudio(context: Context, item: Item): Pair<ShortArray, Int> {
        val data = bytes(item)
        Wav.read(data)?.let { return it }
        val file = File.createTempFile("decode", ".audio", context.cacheDir)
        try {
            file.writeBytes(data)
            val extractor = MediaExtractor()
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run {
                extractor.release()
                error("there is no sound in that file")
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val out = ShortArrayBuilder()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val cap = 600 * 48000
            try {
                while (!outputDone && out.size < cap) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            while (shorts.remaining() >= channels) {
                                var sum = 0
                                repeat(channels) { sum += shorts.get().toInt() }
                                out.add((sum / channels).toShort())
                            }
                            codec.releaseOutputBuffer(o, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                        }
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                extractor.release()
            }
            return out.toArray() to rate
        } finally {
            file.delete()
        }
    }

    /** Samples collected without boxing each one — minutes of sound are millions of them. */
    private class ShortArrayBuilder {
        private var data = ShortArray(1 shl 16)
        var size = 0
            private set

        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }
}
