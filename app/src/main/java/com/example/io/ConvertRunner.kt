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
import com.example.core.convert.Convertible
import com.example.core.convert.Field
import com.example.core.convert.Luma
import com.example.core.convert.Midi
import com.example.core.convert.NoteText
import com.example.core.convert.PasteConversion
import com.example.core.convert.PicturePrep
import com.example.core.convert.Png
import com.example.core.convert.SingAlong
import com.example.core.convert.Song
import com.example.core.convert.Spectro
import com.example.core.convert.Synth
import com.example.core.convert.Wav
import com.example.core.discovery.AiCapability
import com.example.core.discovery.CallEngine
import com.example.core.discovery.CallInput
import com.example.core.discovery.ProviderCatalog
import com.example.core.discovery.ProviderProfiles
import com.example.core.discovery.ProviderSpec
import com.example.core.io.Command
import com.example.core.matrix.Change
import com.example.core.matrix.ChangeKind
import com.example.core.matrix.Choice
import com.example.core.matrix.DataType
import com.example.core.matrix.Goal
import com.example.core.matrix.Goals
import com.example.core.matrix.Implementation
import com.example.core.matrix.Interpret
import com.example.core.matrix.Interpretation
import com.example.core.matrix.Mapping
import com.example.core.matrix.Params
import com.example.core.matrix.Planner
import com.example.core.matrix.Policy
import com.example.core.matrix.Provenance
import com.example.core.matrix.Record
import com.example.core.matrix.Representation
import com.example.core.matrix.Representations
import com.example.core.matrix.Site
import com.example.core.matrix.Source
import com.example.core.matrix.Transforms
import com.example.core.matrix.Transport
import com.example.core.matrix.Transports
import com.example.core.matrix.Types
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

/** A value on its way through a conversion, in whatever form the last step left it. */
sealed interface Value {
    data class Text(val text: String) : Value

    /** Bytes of a file form — as it arrived, or as a provider sent it. */
    class Encoded(val bytes: ByteArray, val representation: Representation, val mime: String) : Value

    class Raster(val luma: Luma) : Value
    class Pcm(val samples: ShortArray, val rate: Int) : Value
    class TimeFreq(val field: Field) : Value
    class Music(val song: Song) : Value
}

/** A value with what it is, where it came from, and what is known about it. */
class Datum(val type: DataType, val value: Value, val provenance: Provenance, val facts: Set<String> = emptySet())

/**
 * Carries out a conversion the planner chose: fetches the input, takes it the most
 * specific way it can be taken, runs each step, writes the result in the form asked
 * for — with its history in it where the form has room — and delivers it.
 *
 * Every step appends a [Record] saying what it did, with what parameters, which of
 * them were given, carried in the input, or assumed, and what it threw away or made
 * up. The plan, and where it sends anything, is told before it runs.
 */
object ConvertRunner {

    private const val TAG = "Convert"

    /** What arrived, before anyone decided what it is. */
    private class Arrival(
        val value: Value,
        val transport: Transport,
        val interpretations: List<Interpretation>,
        /** The history the file brought with it, when IO Matrix made it before. */
        val earlier: Provenance?
    )
    /** The keyword a file's history is written under. */
    const val HISTORY_KEY = "io-matrix"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ------------------------------------------------------------------
    // Who can do what
    // ------------------------------------------------------------------

    /** The provider a provider-run implementation would use: the ones set up for pasting first, for speech the reading-aloud one. */
    private fun providerFor(impl: Implementation, settings: Settings): ProviderSpec? {
        val capability = impl.capability ?: return null
        when (capability) {
            AiCapability.OCR -> return PasteConversion.providerFor(Convertible.PICTURE, settings)
            AiCapability.TRANSCRIBE -> return PasteConversion.providerFor(Convertible.RECORDING, settings)
        }
        val described = ProviderCatalog.serving(capability, settings).filter {
            (!it.needsKey || ProviderProfiles.keyFor(it.id, settings).isNotBlank()) && it.capability(capability)?.call != null
        }
        if (capability == AiCapability.SPEECH) described.firstOrNull { it.id == settings.ttsProvider }?.let { return it }
        return described.firstOrNull()
    }

    /** Whether an implementation can run now: the phone's always, a provider's when one is set up. */
    fun ready(impl: Implementation, settings: Settings): Boolean = when (impl.site) {
        Site.APP, Site.PHONE -> true
        Site.PROVIDER -> providerFor(impl, settings) != null
    }

    private fun names(raw: String?): List<String> =
        raw.orEmpty().split(Regex("[\\s,;+>]+")).filter { it.isNotBlank() }.mapNotNull { n ->
            Transforms.resolve(n)?.let { (t, impl) -> impl?.id ?: t.id }
        }

    private fun on(command: Command, name: String) = command.arg(name)?.trim()?.lowercase() in setOf("on", "true", "yes")

    // ------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------

    /** Runs "convert to=… source=…", telling [host] what happens. */
    fun run(context: Context, command: Command, host: PerformerHost) {
        val goal = Goals.parse(command.arg("to"))
        if (goal == null) {
            host.notice("Convert: to what? text, notes, midi, image, spectrogram, scalogram, audio, video — or wav, png, jpg")
            return
        }
        val settings = SettingsStore.current
        val source = command.arg("source")?.lowercase() ?: "auto"
        // Text from the field is read here, on the main thread, where the field lives.
        val typed = if (source != "clipboard") {
            host.readable(if (source == "auto") "selection" else source)?.text?.takeIf { it.isNotBlank() }
        } else null
        val typedFrom = when (source) {
            "auto", "selection" -> Transports.SELECTION
            "screen", "page" -> Transports.SCREEN
            else -> Transports.FIELD
        }
        scope.launch {
            try {
                val arrival = typed?.let { textArrival(it, typedFrom) }
                    ?: withContext(Dispatchers.IO) { fromClipboard(context, settings, command.arg("from")) }
                if (arrival == null) {
                    host.notice("Convert: nothing to convert — copy something first, or select text")
                    return@launch
                }
                convert(context, arrival, goal, command, settings, host)
            } catch (e: Exception) {
                AppLogger.e(TAG, "conversion failed", e)
                host.notice("Conversion failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun convert(context: Context, arrival: Arrival, goal: Goal, command: Command, settings: Settings, host: PerformerHost) {
        val policy = Policy(
            allowLeavingDevice = !settings.convertLocalOnly,
            through = names(command.arg("use")) + goal.through,
            avoid = names(command.arg("avoid")).toSet()
        )
        val plan = Planner.plan(arrival.interpretations, goal.type, { ready(it, settings) }, policy)
        val route = plan.best
        if (route == null) {
            val what = arrival.interpretations.firstOrNull()?.type?.label?.lowercase() ?: "that"
            host.notice(
                if (plan.blocked.isEmpty()) "No way from $what to ${goal.type.label.lowercase()} yet"
                else "Not now: " + plan.blocked.take(2).joinToString("; ")
            )
            return
        }
        var datum = start(arrival, route.start, command)
        if (route.steps.isEmpty() && goal.representation?.let { matches(datum.value, it) } != false) {
            host.notice("It already is ${goal.type.label.lowercase()}")
            return
        }
        val expert = settings.expertMode
        val sentTo = route.steps.filter { it.implementation.leavesDevice }
            .mapNotNull { providerFor(it.implementation, settings)?.label }.distinct()
        val others = plan.routes.size - 1
        host.notice(
            "Converting: " + (listOf(route.start.type.id) + route.steps.map { it.transform.to }).joinToString(" → ") +
                (if (sentTo.isEmpty()) " (on the phone)" else " (sent to ${sentTo.joinToString()})") +
                when {
                    others <= 0 -> ""
                    expert -> " · also: " + plan.routes.drop(1).take(2).joinToString(" | ") { r -> r.steps.joinToString("+") { it.transform.id } }
                    else -> " · $others other way${if (others == 1) "" else "s"}"
                }
        )
        route.steps.forEachIndexed { i, choice ->
            val current = datum
            datum = withContext(Dispatchers.IO) { execute(context, choice, route.steps.getOrNull(i + 1), current, command, settings) }
        }
        if (on(command, "lyrics")) datum = withLyrics(context, arrival, datum, command, settings)
        val out = withContext(Dispatchers.IO) { encode(context, datum, goal, command) }
        deliver(context, out, host, expert)
    }

    /** The input taken as [how] — note names parsed into notes, everything else as it is. */
    private fun start(arrival: Arrival, how: Interpretation, command: Command): Datum {
        val source = Source(
            transport = arrival.transport.id,
            type = how.type.id,
            representation = how.representation.id,
            interpretedAs = if (how.inferred) how.how else "",
            inferred = how.inferred
        )
        val value = arrival.value
        val taken = when {
            how.representation == Representations.NOTATION && value is Value.Text ->
                Value.Music(Song(NoteText.parse(value.text, bpm(command))))
            value is Value.Encoded && how.representation == Representations.SMF ->
                Value.Music(Midi.readSong(value.bytes))
            else -> value
        }
        return Datum(how.type, taken, Provenance(source, earlier = arrival.earlier), how.facts)
    }

    private fun bpm(command: Command) = command.float("bpm")?.toDouble()?.takeIf { it > 0 } ?: 120.0

    // ------------------------------------------------------------------
    // In
    // ------------------------------------------------------------------

    private fun textArrival(text: String, from: Transport) = Arrival(Value.Text(text), from, Interpret.text(text), null)

    private fun fromClipboard(context: Context, settings: Settings, forced: String?): Arrival? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        val uri = item.uri ?: return item.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
            ?.let { textArrival(it, Transports.CLIPBOARD) }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: clip.description.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) }.firstOrNull() }
        val limit = settings.clipboardMaxFileMb.coerceAtLeast(1) * 1024L * 1024L
        val bytes = context.contentResolver.openInputStream(uri)?.use { s ->
            val read = s.readBytes()
            require(read.size <= limit) { "that file is over the size limit (${settings.clipboardMaxFileMb} MB)" }
            read
        } ?: return null
        val said = Representations.ofMime(mime)
        // What the bytes are beats what the label says, when the label is vague.
        val representation = Representations.sniff(bytes)?.takeIf { said == null || said.id in setOf("image", "audio", "video") || said.type == it.type }
            ?: said
        val type = Goals.parse(forced)?.type ?: Types.byId(forced)
        if (representation == null && type == null) error("not sure what that file is — say from=image, audio, midi or video")
        if ((type ?: Types.byId(representation?.type)) == Types.TEXT) {
            return textArrival(String(bytes, Charsets.UTF_8), Transports.CLIPBOARD)
        }
        val form: Representation = representation?.takeIf { type == null || it.type == type.id }
            ?: type?.let { t -> Representations.writable(t.id).firstOrNull() ?: Representation(t.id, t.id, t.label, mime) }
            ?: error("not sure what that file is — say from=image, audio, midi or video")
        val earlier = history(bytes, form)
        val facts = Interpret.factsFrom(earlier) + if (form == Representations.SMF && runCatching { Midi.readSong(bytes).lyrics.isNotEmpty() }.getOrDefault(false)) setOf("lyrics") else emptySet()
        return Arrival(Value.Encoded(bytes, form, mime ?: form.mime ?: "application/octet-stream"), Transports.CLIPBOARD, Interpret.file(form, facts), earlier)
    }

    /** The history a file carries, when IO Matrix wrote one into it. */
    fun history(bytes: ByteArray, form: Representation? = Representations.sniff(bytes)): Provenance? = when (form) {
        Representations.PNG -> Provenance.fromJson(Png.texts(bytes)[HISTORY_KEY])
        Representations.WAV -> Provenance.fromJson(Wav.comment(bytes))
        Representations.SMF -> runCatching { Provenance.fromJson(Midi.readSong(bytes).meta) }.getOrNull()
        else -> null
    }

    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------

    private class Outcome(
        val value: Value,
        val parameters: Map<String, String> = emptyMap(),
        val assumed: List<String> = emptyList(),
        val extra: List<Change> = emptyList(),
        val note: String? = null
    )

    private suspend fun execute(context: Context, c: Choice, next: Choice?, d: Datum, command: Command, settings: Settings): Datum {
        val t = c.transform
        val o: Outcome = when (t.id) {
            "ocr" -> {
                val (bytes, mime) = pictureBytes(d.value)
                Outcome(Value.Text(PasteConversion.pictureToText(context, bytes, mime, settings)))
            }
            "transcribe" -> {
                val (bytes, mime) = soundBytes(d.value)
                Outcome(Value.Text(PasteConversion.recordingToText(context, bytes, mime, settings, command.arg("language").orEmpty())))
            }
            "speak" -> if (c.implementation.site == Site.PROVIDER) {
                Outcome(fromProvider(c.implementation, text(d.value), settings, "audio/mpeg", Representations.MP3))
            } else Outcome(speakHere(context, text(d.value)))
            "draw" -> Outcome(fromProvider(c.implementation, text(d.value), settings, "image/png", Representations.PNG))
            "film" -> Outcome(fromProvider(c.implementation, text(d.value), settings, "video/mp4", Representations.MP4))
            "typeset" -> {
                // Read as a spectrogram next, it wants light marks on dark and one long
                // line, so the words go by in time; as a picture, a page.
                val forSound = next?.transform?.id == "read_field"
                val light = when (command.arg("ink")?.lowercase()) {
                    "light", "white" -> true
                    "dark", "black" -> false
                    else -> forSound
                }
                val size = command.float("size") ?: if (forSound) 120f else 44f
                Outcome(
                    Value.Raster(typeset(text(d.value), light, forSound, size)),
                    mapOf("ink" to if (light) "light" else "dark", "size" to size.toString(), "lines" to if (forSound) "one" else "wrapped")
                )
            }
            "stft", "cwt" -> analyse(context, t.id, next, d, command)
            "render_field" -> {
                val field = field(d.value)
                val layout = Spectro.withParams(field.axis, mapOfNotNull("time" to command.arg("time"), "flip" to command.arg("flip")))
                Outcome(Value.Raster(Spectro.render(field, layout)), Spectro.params(layout) + ("method" to field.method))
            }
            "read_field" -> readField(context, d, command)
            "resynthesize" -> {
                val rate = 22050
                Outcome(Value.Pcm(Spectro.resynthesize(field(d.value), rate), rate), mapOf("rate" to rate.toString(), "range_db" to "60"))
            }
            "extract_notes" -> extractNotes(d, command)
            "notes_field" -> {
                val song = song(d.value)
                val end = song.notes.maxOfOrNull { it.start + it.duration } ?: 1.0
                val axis = Spectro.withParams(Spectro.Layout(seconds = end + 0.5), mapOfNotNull(
                    "low" to command.arg("low"), "high" to command.arg("high"), "scale" to command.arg("scale")
                ))
                val frames = command.int("width") ?: 1200
                val bins = command.int("height") ?: 600
                Outcome(Value.TimeFreq(Spectro.fieldOf(song.notes, axis, frames, bins)), Spectro.params(axis) + mapOf("frames" to "$frames", "bins" to "$bins"))
            }
            "synth" -> {
                val rate = 22050
                Outcome(Value.Pcm(Synth.render(song(d.value).notes, rate), rate), mapOf("rate" to rate.toString()))
            }
            "lyrics" -> Outcome(Value.Text(song(d.value).lyrics.joinToString("") { it.text }.trim()))
            "soundtrack" -> {
                val (pcm, rate) = decodeAudio(context, encoded(d.value).bytes)
                Outcome(Value.Pcm(pcm, rate))
            }
            "frame" -> {
                val at = command.float("at")
                Outcome(frame(context, encoded(d.value).bytes, at), mapOf("at" to (at?.toString() ?: "middle")), if (at == null) listOf("at") else emptyList())
            }
            else -> error("no way to run “${t.id}”")
        }
        val record = Record(
            transform = t.id,
            implementation = c.implementation.id,
            site = c.implementation.site,
            mapping = t.mapping,
            changes = t.changes + o.extra,
            parameters = o.parameters,
            assumed = o.assumed,
            assumptions = t.assumptions,
            note = o.note
        )
        return Datum(Types.byId(t.to)!!, o.value, d.provenance.then(record), t.produces)
    }

    private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
        pairs.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()

    /**
     * Sound into a field, shaped by what comes next: notes want semitones told apart
     * and moments 20 ms long; a picture wants a picture's width and height.
     */
    private fun analyse(context: Context, method: String, next: Choice?, d: Datum, command: Command): Outcome {
        val (pcm, rate) = pcm(context, d.value)
        require(pcm.isNotEmpty()) { "that recording is empty" }
        val forNotes = next?.transform?.id == "extract_notes"
        val seconds = pcm.size / rate.toDouble()
        val axis = Spectro.withParams(
            Spectro.Layout(highHz = if (forNotes) 2093.0 else 4186.0, seconds = seconds),
            mapOfNotNull("low" to command.arg("low"), "high" to command.arg("high"), "scale" to command.arg("scale"))
        ).let { if (it.highHz >= rate / 2.0) it.copy(highHz = rate / 2.0 * 0.95) else it }
        val semitones = Spectro.pitches(axis).count().coerceAtLeast(1)
        val frames = if (forNotes) (pcm.size / (rate * 0.02)).toInt().coerceIn(20, 6000) else command.int("width") ?: 1200
        val bins = if (forNotes) (semitones * 6).coerceIn(60, 1200) else command.int("height") ?: 600
        val params = Spectro.params(axis) + mapOf("frames" to "$frames", "bins" to "$bins", "rate" to "$rate")
        return if (method == "cwt") {
            val omega = command.float("omega")?.toDouble()?.takeIf { it > 0 } ?: 6.0
            Outcome(Value.TimeFreq(Spectro.cwt(pcm, rate, axis, frames, bins, omega)), params + ("omega" to omega.toString()))
        } else {
            val window = command.int("window")?.takeIf { it > 0 && (it and (it - 1)) == 0 } ?: if (forNotes) Spectro.windowFor(rate) else 2048
            Outcome(Value.TimeFreq(Spectro.stft(pcm, rate, axis, frames, bins, window)), params + ("window" to "$window"))
        }
    }

    /**
     * A picture read as a spectrogram. Each layout parameter is the user's, or the one
     * the picture's own history says it was drawn with, or else a default — and a
     * default is recorded as an assumption, because that is what it is.
     */
    private fun readField(context: Context, d: Datum, command: Command): Outcome {
        val raw = raster(context, d.value)
        val picture = PicturePrep.prepare(raw, command.arg("area"), command.arg("invert"))
        val names = listOf("time", "flip", "scale", "low", "high", "seconds")
        val fromText = d.facts.contains("text_picture")
        val base = if (fromText) {
            // Text hidden in sound is looked for with an ordinary spectrogram app, which
            // shows frequency linearly; and it goes by at a readable pace.
            Spectro.Layout(logFrequency = false, lowHz = 500.0, highHz = 6000.0, seconds = (picture.width / 150.0).coerceIn(1.0, 120.0))
        } else Spectro.Layout()
        val resolved = Params.resolve(names, { command.arg(it) }, Interpret.carried(d.provenance, "render_field"), Spectro.params(base))
        val layout = Spectro.withParams(base, Params.values(resolved))
        val area = PicturePrep.area(command.arg("area"))
        val inverted = PicturePrep.invert(command.arg("invert"), raw.let { a -> area?.let { a.crop(it[0], it[1], it[2], it[3]) } ?: a })
        val params = Params.values(resolved) +
            mapOfNotNull("area" to command.arg("area")) + ("inverted" to inverted.toString())
        // A picture this app set from text is read that way on purpose: nothing about
        // its layout is a guess then.
        return Outcome(
            Value.TimeFreq(Spectro.read(picture, layout)),
            params,
            if (fromText) emptyList() else Params.assumed(resolved)
        )
    }

    /** Notes out of a field: a melody when it was measured from sound, everything when it was drawn. */
    private fun extractNotes(d: Datum, command: Command): Outcome {
        val field = field(d.value)
        val base = if (field.acoustic) Spectro.Layout(threshold = 0.75f, harmonics = false, voices = 1) else Spectro.Layout()
        val reading = Spectro.withParams(base, mapOfNotNull(
            "threshold" to command.arg("threshold"), "min_note" to command.arg("min_note"),
            "harmonics" to command.arg("harmonics"), "voices" to command.arg("voices")
        ))
        val extra = buildList {
            if (!reading.harmonics) add(Change(ChangeKind.DISCARDED, "overtones as notes of their own"))
            if (reading.voices > 0) add(Change(ChangeKind.DISCARDED, "all but the loudest ${reading.voices} at once"))
        }
        return Outcome(
            Value.Music(Song(Spectro.notes(field, reading))),
            mapOf(
                "threshold" to reading.threshold.toString(), "min_note" to reading.minNote.toString(),
                "harmonics" to if (reading.harmonics) "keep" else "fold", "voices" to reading.voices.toString(),
                "levels" to field.scale.name.lowercase()
            ),
            extra = extra
        )
    }

    /**
     * The words sung, laid over the melody — a join of two branches from the same
     * recording, which the planner's single paths do not describe yet: it is written
     * into the history as its own step, saying so.
     */
    private suspend fun withLyrics(context: Context, arrival: Arrival, d: Datum, command: Command, settings: Settings): Datum {
        val song = (d.value as? Value.Music)?.song ?: return d
        val original = arrival.value
        require(original is Value.Encoded && original.representation.type == "audio") { "words need a recording as the input" }
        val impl = Transforms.implementation("transcribe.provider")!!
        require(!settings.convertLocalOnly) { "words need transcription, which would send the recording off the phone" }
        require(ready(impl, settings)) { "words need a provider set up to write down what is said" }
        val words = withContext(Dispatchers.IO) {
            PasteConversion.recordingToText(context, original.bytes, original.mime, settings, command.arg("language").orEmpty())
        }
        val lyrics = SingAlong.lay(words.split(Regex("\\s+")), song.notes)
        val t = Transforms.byId("transcribe")!!
        val record = Record(
            "transcribe", impl.id, impl.site, t.mapping,
            t.changes + Change(ChangeKind.AGGREGATED, "words spread over the notes' starts"),
            assumptions = t.assumptions + "the words fall evenly on the notes",
            note = "joined: the words of the same recording, laid over the melody"
        )
        return Datum(d.type, Value.Music(song.copy(lyrics = lyrics)), d.provenance.then(record), d.facts + "lyrics")
    }

    // ------------------------------------------------------------------
    // Values, as the next step needs them
    // ------------------------------------------------------------------

    private fun text(v: Value) = (v as? Value.Text)?.text ?: error("expected text")
    private fun field(v: Value) = (v as? Value.TimeFreq)?.field ?: error("expected a time–frequency field")
    private fun song(v: Value) = (v as? Value.Music)?.song ?: error("expected notes")
    private fun encoded(v: Value) = v as? Value.Encoded ?: error("expected a file")

    private fun raster(context: Context, v: Value): Luma = when (v) {
        is Value.Raster -> v.luma
        is Value.Encoded -> luma(v.bytes)
        else -> error("expected a picture")
    }

    private fun pictureBytes(v: Value): Pair<ByteArray, String> = when (v) {
        is Value.Encoded -> v.bytes to v.mime
        is Value.Raster -> png(v.luma) to "image/png"
        else -> error("expected a picture")
    }

    private fun soundBytes(v: Value): Pair<ByteArray, String> = when (v) {
        is Value.Encoded -> v.bytes to v.mime
        is Value.Pcm -> Wav.write(v.samples, v.rate) to "audio/wav"
        else -> error("expected a sound")
    }

    private fun pcm(context: Context, v: Value): Pair<ShortArray, Int> = when (v) {
        is Value.Pcm -> v.samples to v.rate
        is Value.Encoded -> decodeAudio(context, v.bytes)
        else -> error("expected a sound")
    }

    private fun matches(v: Value, r: Representation): Boolean = when (v) {
        is Value.Text -> r == Representations.UTF8
        is Value.Encoded -> v.representation.id == r.id
        else -> false
    }

    // ------------------------------------------------------------------
    // Out
    // ------------------------------------------------------------------

    private sealed interface Out {
        val provenance: Provenance

        class Text(val text: String, override val provenance: Provenance) : Out
        class File(val bytes: ByteArray, val mime: String, val extension: String, override val provenance: Provenance) : Out
    }

    /** A record for writing the result in a form that loses something, or in a different notation. */
    private fun encodeRecord(what: String, changes: List<Change>) =
        Record("encode", "encode.app", Site.APP, Mapping.DETERMINISTIC, changes, mapOf("as" to what))

    /** The result written in the form the goal asks for, with its history inside where the form has room. */
    private fun encode(context: Context, d: Datum, goal: Goal, command: Command): Out {
        var p = d.provenance
        fun history() = p.toJson().toString()
        return when (d.type) {
            Types.TEXT -> Out.Text(text(d.value), p)
            Types.NOTES -> {
                val song = song(d.value)
                if (goal.representation == Representations.NOTATION) {
                    p = p.then(encodeRecord("note names", buildList {
                        add(Change(ChangeKind.AGGREGATED, "timing to quarter beats"))
                        add(Change(ChangeKind.DISCARDED, "loudness"))
                        if (song.lyrics.isNotEmpty()) add(Change(ChangeKind.DISCARDED, "words"))
                    }))
                    Out.Text(NoteText.write(song.notes, bpm(command)), p)
                } else {
                    Out.File(Midi.write(song.notes, bpm(command), lyrics = song.lyrics, meta = history()), "audio/midi", "mid", p)
                }
            }
            Types.PICTURE -> {
                val v = d.value
                val want = goal.representation ?: (v as? Value.Encoded)?.representation?.takeIf { it.type == "picture" } ?: Representations.PNG
                if (v is Value.Encoded && v.representation.id == want.id) {
                    val bytes = if (want == Representations.PNG) Png.withText(v.bytes, HISTORY_KEY, history()) else v.bytes
                    Out.File(bytes, v.mime, want.extension ?: "png", p)
                } else {
                    val luma = raster(context, v)
                    if (want.lossy == true) p = p.then(encodeRecord(want.label, listOf(Change(ChangeKind.DISCARDED, "fine detail"))))
                    val bytes = when (want) {
                        Representations.JPEG -> compress(luma, Bitmap.CompressFormat.JPEG)
                        Representations.WEBP -> compress(luma, Bitmap.CompressFormat.WEBP)
                        else -> Png.withText(png(luma), HISTORY_KEY, history())
                    }
                    Out.File(bytes, want.mime ?: "image/png", want.extension ?: "png", p)
                }
            }
            Types.AUDIO -> when (val v = d.value) {
                is Value.Pcm -> Out.File(Wav.write(v.samples, v.rate, history()), "audio/wav", "wav", p)
                is Value.Encoded -> if (goal.representation == Representations.WAV && v.representation != Representations.WAV) {
                    val (pcm, rate) = decodeAudio(context, v.bytes)
                    Out.File(Wav.write(pcm, rate, history()), "audio/wav", "wav", p)
                } else Out.File(v.bytes, v.mime, v.representation.extension ?: "audio", p)
                else -> error("expected a sound")
            }
            Types.VIDEO -> {
                val v = encoded(d.value)
                Out.File(v.bytes, v.mime, v.representation.extension ?: "mp4", p)
            }
            else -> error("${d.type.label} has no file form yet — ask for a picture of it (to=spectrogram)")
        }
    }

    private suspend fun deliver(context: Context, out: Out, host: PerformerHost, expert: Boolean) = withContext(Dispatchers.Main) {
        val tail = if (expert) " · " + out.provenance.summary() else originsNote(out.provenance)
        when (out) {
            is Out.Text -> {
                if (out.text.isBlank()) {
                    host.notice("Converted, but it came out empty$tail")
                    return@withContext
                }
                host.commit(out.text)
                host.copy(out.text, "Converted")
                host.notice("Done — in the field and on the clipboard$tail")
            }
            is Out.File -> {
                val file = withContext(Dispatchers.IO) {
                    File(ClipStore.dir(context), "converted_${System.currentTimeMillis()}.${out.extension}").apply { writeBytes(out.bytes) }
                }
                val uri: Uri = ClipStore.shareUri(context, file)
                val clip = ClipData(ClipDescription("Converted", arrayOf(out.mime)), ClipData.Item(uri))
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(clip)
                host.notice("Done — ${file.name} is on the clipboard$tail", actionLabel = "Share") {
                    runCatching {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType(out.mime)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }

    /** For everyone, not only experts: whether any of it is an estimate or made up. */
    private fun originsNote(p: Provenance): String {
        val o = p.origins
        return when {
            com.example.core.matrix.Origin.GENERATED in o -> " (partly made up by the method)"
            com.example.core.matrix.Origin.INFERRED in o -> " (estimated, not measured)"
            else -> ""
        }
    }

    /** "Where did this come from" for whatever is on the clipboard: its history, if it carries one. */
    fun explain(context: Context, host: PerformerHost) {
        scope.launch {
            val details = runCatching {
                withContext(Dispatchers.IO) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val uri = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return@withContext null
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
                    history(bytes)?.details()
                }
            }.getOrNull()
            if (details == null) {
                host.notice("What is on the clipboard carries no history — it was not made here, or its form has no room for one")
                return@launch
            }
            host.notice(details.lineSequence().first() + " …", actionLabel = "Copy all") { host.copy(details, "History") }
        }
    }

    // ------------------------------------------------------------------
    // The platform's part
    // ------------------------------------------------------------------

    /**
     * The phone's own voice into a file — in pieces, since engines take a few thousand
     * characters at a time, joined back into one recording.
     */
    private suspend fun speakHere(context: Context, text: String): Value {
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
        val parts = raw.map { Wav.read(it) }
        if (parts.all { it != null }) {
            val (pcm, rate) = Wav.join(parts.filterNotNull())!!
            return Value.Pcm(pcm, rate)
        }
        require(raw.size == 1) { "the speech engine's files cannot be joined; convert a shorter text" }
        return Value.Encoded(raw[0], Representations.WAV, "audio/wav")
    }

    private suspend fun fromProvider(impl: Implementation, prompt: String, settings: Settings, mime: String, form: Representation): Value {
        val capability = impl.capability ?: error("${impl.id} names no capability")
        val p = providerFor(impl, settings) ?: error("nothing is set up to ${AiCapability.label(capability).lowercase()}")
        val speechProvider = capability == AiCapability.SPEECH && p.id == settings.ttsProvider
        val params = if (speechProvider && settings.ttsCloudVoice.isNotBlank()) mapOf("voice" to settings.ttsCloudVoice) else emptyMap()
        val result = CallEngine.run(
            p, capability,
            CallInput(model = if (speechProvider) settings.ttsModel else "", prompt = prompt, params = params),
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
        val said = result.mime.takeIf { it.contains('/') && !it.contains("json") }
        val actual = Representations.sniff(bytes) ?: said?.let { Representations.ofMime(it) } ?: form
        return Value.Encoded(bytes, actual, said ?: actual.mime ?: mime)
    }

    private fun typeset(text: String, light: Boolean, oneLine: Boolean, size: Float): Luma {
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
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - 2 * pad)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            val h = (layout.height + 2 * pad).coerceIn(1, 16_000)
            Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                c.drawColor(if (light) Color.BLACK else Color.WHITE)
                c.translate(pad.toFloat(), pad.toFloat())
                layout.draw(c)
            }
        }
        return lumaOf(bmp).also { bmp.recycle() }
    }

    private fun lumaOf(bmp: Bitmap): Luma {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return Luma(w, h, FloatArray(w * h) { i ->
            val c = px[i]
            (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
        })
    }

    private fun luma(bytes: ByteArray): Luma {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        var sample = 1
        while (options.outWidth / sample > 2000 || options.outHeight / sample > 2000) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("that picture could not be read")
        return lumaOf(bmp).also { bmp.recycle() }
    }

    private fun bitmapOf(image: Luma): Bitmap {
        val px = IntArray(image.width * image.height) { i ->
            val v = (image.values[i] * 255).toInt().coerceIn(0, 255)
            Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(px, image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    private fun compress(image: Luma, format: Bitmap.CompressFormat): ByteArray {
        val bmp = bitmapOf(image)
        val out = ByteArrayOutputStream()
        bmp.compress(format, if (format == Bitmap.CompressFormat.PNG) 100 else 92, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun png(image: Luma): ByteArray = compress(image, Bitmap.CompressFormat.PNG)

    /** One frame of a video, at [atSeconds] or halfway through. */
    private fun frame(context: Context, bytes: ByteArray, atSeconds: Float?): Value {
        val file = File.createTempFile("frame", ".video", context.cacheDir)
        val retriever = MediaMetadataRetriever()
        try {
            file.writeBytes(bytes)
            retriever.setDataSource(file.path)
            val lengthMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = atSeconds?.let { (it * 1_000_000).toLong() } ?: (lengthMs * 500)
            val bmp = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: error("no picture at that moment of the video")
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            return Value.Encoded(out.toByteArray(), Representations.PNG, "image/png")
        } finally {
            runCatching { retriever.release() }
            file.delete()
        }
    }

    /**
     * Any recording or video the phone can play, as mono samples: WAV read directly,
     * anything else through the phone's own decoders. Capped at ten minutes.
     */
    private fun decodeAudio(context: Context, data: ByteArray): Pair<ShortArray, Int> {
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
