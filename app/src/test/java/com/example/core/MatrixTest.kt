package com.example.core

import com.example.core.convert.Luma
import com.example.core.convert.Lyric
import com.example.core.convert.Midi
import com.example.core.convert.Note
import com.example.core.convert.Png
import com.example.core.convert.Spectro
import com.example.core.convert.Synth
import com.example.core.convert.TimeAxis
import com.example.core.convert.Wav
import com.example.core.matrix.Given
import com.example.core.matrix.Goals
import com.example.core.matrix.Implementation
import com.example.core.matrix.Interpret
import com.example.core.matrix.Interpretation
import com.example.core.matrix.Invertibility
import com.example.core.matrix.Mapping
import com.example.core.matrix.Params
import com.example.core.matrix.Plan
import com.example.core.matrix.Planner
import com.example.core.matrix.Policy
import com.example.core.matrix.Representations
import com.example.core.matrix.Site
import com.example.core.matrix.Transforms
import com.example.core.matrix.Types
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * The IO Matrix model: types apart from formats, transforms apart from who carries
 * them out, and a planner that keeps every way it finds. Several of these are
 * written to break a wrong idea of what things are rather than to catch a bug.
 */
class MatrixTest {

    // ------------------------------------------------------------------
    // Planning
    // ------------------------------------------------------------------

    private val phoneOnly: (Implementation) -> Boolean = { it.site != Site.PROVIDER }

    private fun providers(vararg transforms: String): (Implementation) -> Boolean =
        { it.site != Site.PROVIDER || it.transform in transforms }

    private fun ids(plan: Plan) = plan.best?.steps?.map { it.transform.id }
    private fun impls(plan: Plan) = plan.best?.steps?.map { it.implementation.id }

    private fun from(type: String, facts: Set<String> = emptySet()) =
        listOf(Interpretation(Types.byId(type)!!, Representations.writable(type).firstOrNull() ?: Representations.FIELD, facts = facts))

    @Test
    fun `a spectrogram picture becomes notes by being read as a field first`() {
        val plan = Planner.plan(from("picture"), Types.NOTES, phoneOnly)
        assertEquals(listOf("read_field", "extract_notes"), ids(plan))
    }

    @Test
    fun `a picture heard is the field played, not rounded to notes`() {
        assertEquals(listOf("read_field", "resynthesize"), ids(Planner.plan(from("picture"), Types.AUDIO, phoneOnly)))
        assertEquals(
            listOf("read_field", "extract_notes", "synth"),
            ids(Planner.plan(from("picture"), Types.AUDIO, phoneOnly, Policy(avoid = setOf("resynthesize"))))
        )
    }

    @Test
    fun `a picture is not text until something reads it, and that needs a reader`() {
        assertEquals(listOf("ocr"), ids(Planner.plan(from("picture"), Types.TEXT, providers("ocr"))))
        val none = Planner.plan(from("picture"), Types.TEXT, phoneOnly)
        assertNull(none.best)
        assertTrue(none.blocked.toString(), none.blocked.any { "provider" in it })
    }

    @Test
    fun `a spectrogram this app drew is never read for text`() {
        // Its history says what it is: reading words out of it would be nonsense.
        val plan = Planner.plan(from("picture", setOf("spectrogram")), Types.TEXT, providers("ocr"))
        assertNull(plan.best)
    }

    @Test
    fun `text out of a recording is what was said, never a detour through a picture`() {
        assertEquals(listOf("transcribe"), ids(Planner.plan(from("audio"), Types.TEXT, providers("transcribe", "ocr"))))
        // No transcription, but a picture reader: drawing the sound and reading text
        // off the drawing is a path in the graph and nonsense in the world.
        assertNull(Planner.plan(from("audio"), Types.TEXT, providers("ocr")).best)
    }

    @Test
    fun `the phone's voice comes first and a provider's is there to be chosen`() {
        val plan = Planner.plan(Interpret.text("Good morning"), Types.AUDIO, providers("speak"))
        assertEquals(listOf("speak.phone"), impls(plan))
        assertTrue(plan.routes.any { r -> r.steps.map { it.implementation.id } == listOf("speak.provider") })
        assertEquals(listOf("speak.provider"), impls(Planner.plan(Interpret.text("Good morning"), Types.AUDIO, providers("speak"), Policy(through = listOf("speak.provider")))))
    }

    @Test
    fun `note names are played as a melody, and prose is spoken`() {
        assertEquals(listOf("synth"), ids(Planner.plan(Interpret.text("C4 E4 G4 C5:2"), Types.AUDIO, phoneOnly)))
        assertEquals(listOf("speak"), ids(Planner.plan(Interpret.text("Hello there"), Types.AUDIO, phoneOnly)))
    }

    @Test
    fun `note names into MIDI is a change of form, not a conversion`() {
        val plan = Planner.plan(Interpret.text("C4 E4 G4"), Types.NOTES, phoneOnly)
        assertEquals(emptyList<String>(), ids(plan))
        assertEquals(Types.NOTES, plan.best!!.start.type)
    }

    @Test
    fun `made-up content is never a hidden default`() {
        assertEquals(listOf("typeset"), ids(Planner.plan(Interpret.text("a red boat"), Types.PICTURE, providers("draw"))))
        assertEquals(listOf("draw"), ids(Planner.plan(Interpret.text("a red boat"), Types.PICTURE, providers("draw"), Policy(through = listOf("draw")))))
        val video = Planner.plan(Interpret.text("a red boat"), Types.VIDEO, providers("film"))
        assertNull(video.best)
        assertTrue(video.blocked.toString(), video.blocked.any { "use=film" in it })
    }

    @Test
    fun `text hidden in sound is asked for, and then reading a picture of text is the point`() {
        assertNull(Planner.plan(Interpret.text("hello"), Types.AUDIO, phoneOnly, Policy(avoid = setOf("speak"))).best)
        assertEquals(
            listOf("typeset", "read_field", "resynthesize"),
            ids(Planner.plan(Interpret.text("hello"), Types.AUDIO, phoneOnly, Policy(through = listOf("typeset"))))
        )
    }

    @Test
    fun `a video gives up its sound for words`() {
        assertEquals(listOf("soundtrack", "transcribe"), ids(Planner.plan(from("video"), Types.TEXT, providers("transcribe", "ocr"))))
        assertEquals(listOf("soundtrack", "stft", "extract_notes"), ids(Planner.plan(from("video"), Types.NOTES, phoneOnly)))
    }

    @Test
    fun `the words of a song are there only when the song has words`() {
        assertEquals(listOf("lyrics"), ids(Planner.plan(from("notes", setOf("lyrics")), Types.TEXT, phoneOnly)))
        assertNull(Planner.plan(from("notes"), Types.TEXT, phoneOnly).best)
    }

    @Test
    fun `a spectrogram and a scalogram are two ways to the same kind of picture`() {
        val spectrogram = Goals.parse("spectrogram")!!
        val scalogram = Goals.parse("scalogram")!!
        assertEquals(listOf("stft", "render_field"), ids(Planner.plan(from("audio"), spectrogram.type, phoneOnly, Policy(through = spectrogram.through))))
        assertEquals(listOf("cwt", "render_field"), ids(Planner.plan(from("audio"), scalogram.type, phoneOnly, Policy(through = scalogram.through))))
        // And both are kept as ways to a picture of a sound, the cheaper first.
        val plan = Planner.plan(from("audio"), Types.PICTURE, phoneOnly)
        assertTrue(plan.routes.map { r -> r.steps.first().transform.id }.containsAll(listOf("stft", "cwt")))
        assertEquals("stft", plan.best!!.steps.first().transform.id)
    }

    @Test
    fun `a waterfall is redrawn in another layout by way of its field`() {
        val goal = Goals.parse("spectrogram")!!
        assertEquals(listOf("read_field", "render_field"), ids(Planner.plan(from("picture"), goal.type, phoneOnly, Policy(through = goal.through))))
    }

    @Test
    fun `on the phone only, what needs a provider says so`() {
        val plan = Planner.plan(from("picture"), Types.TEXT, providers("ocr"), Policy(allowLeavingDevice = false))
        assertNull(plan.best)
        assertTrue(plan.blocked.toString(), plan.blocked.any { "off the phone" in it })
    }

    // ------------------------------------------------------------------
    // The model itself
    // ------------------------------------------------------------------

    @Test
    fun `every transform connects known types and can be carried out somehow`() {
        Transforms.ALL.forEach { t ->
            assertNotNull(t.from, Types.byId(t.from))
            assertNotNull(t.to, Types.byId(t.to))
            assertTrue("${t.id} has no implementation", Transforms.implementationsOf(t.id).isNotEmpty())
            t.inverse?.let { assertNotNull("${t.id}'s inverse ${t.inverse} does not exist", Transforms.byId(it)) }
            if (t.mapping == Mapping.GENERATIVE) assertEquals(t.id, Invertibility.IMPOSSIBLE, t.invertibility)
        }
        assertEquals(Transforms.ALL.size, Transforms.ALL.map { it.id }.toSet().size)
        assertEquals(Transforms.IMPLEMENTATIONS.size, Transforms.IMPLEMENTATIONS.map { it.id }.toSet().size)
        Transforms.IMPLEMENTATIONS.forEach { i ->
            assertNotNull(i.id, Transforms.byId(i.transform))
            // Capability before vendor: a provider step names what it needs, never who.
            if (i.site == Site.PROVIDER) assertNotNull(i.id, i.capability)
        }
    }

    @Test
    fun `a format is never a type`() {
        Representations.ALL.forEach { r -> assertNotNull(r.id, Types.byId(r.type)) }
        assertNull(Types.byId("png"))
        assertNull(Types.byId("waterfall"))
        assertNull(Types.byId("spectrogram"))
        assertEquals("picture", Representations.ofMime("image/jpeg")!!.type)
        assertEquals("notes", Representations.ofMime("audio/midi")!!.type)
        assertEquals("audio", Representations.ofMime("audio/flac")!!.type)
        assertNull(Representations.ofMime("application/pdf"))
        assertEquals(Representations.SMF, Representations.sniff("MThd\u0000\u0000\u0000\u0006".toByteArray()))
        assertEquals(Representations.PNG, Representations.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }

    @Test
    fun `the same text can be two things, and the specific reading comes first`() {
        val notes = Interpret.text("C4 E4 G4 - C5:2")
        assertEquals(listOf("notes", "text"), notes.map { it.type.id })
        assertTrue(notes.first().inferred)
        assertEquals(listOf("text"), Interpret.text("A4 paper is the size of this page").map { it.type.id })
        assertFalse(Interpret.looksLikeNotes(""))
    }

    @Test
    fun `what to convert to is a type, sometimes with a form and a way`() {
        assertEquals(Types.NOTES to Representations.SMF, Goals.parse("midi")!!.let { it.type to it.representation })
        assertEquals(Types.NOTES to Representations.NOTATION, Goals.parse("notes")!!.let { it.type to it.representation })
        assertEquals(Types.PICTURE to Representations.JPEG, Goals.parse("jpg")!!.let { it.type to it.representation })
        assertEquals(Types.AUDIO, Goals.parse("audio")!!.type)
        assertNull(Goals.parse("audio")!!.representation)
        assertEquals(listOf("cwt", "render_field"), Goals.parse("scalogram")!!.through)
        assertNull(Goals.parse("smell"))
    }

    @Test
    fun `a parameter given beats one carried, and only defaults are assumptions`() {
        val r = Params.resolve(
            listOf("time", "low", "seconds"),
            user = { if (it == "time") "down" else null },
            carried = mapOf("time" to "right", "low" to "80"),
            defaults = mapOf("time" to "right", "low" to "55", "seconds" to "10")
        )
        assertEquals("down" to Given.USER, r["time"])
        assertEquals("80" to Given.CARRIED, r["low"])
        assertEquals("10" to Given.DEFAULT, r["seconds"])
        assertEquals(listOf("seconds"), Params.assumed(r))
    }

    @Test
    fun `a layout survives being written down and read back`() {
        val layout = Spectro.Layout(time = TimeAxis.UP, frequencyFlipped = true, logFrequency = false, lowHz = 100.0, highHz = 5000.0, seconds = 7.5)
        assertEquals(layout, Spectro.withParams(Spectro.Layout(), Spectro.params(layout)))
    }

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    @Test
    fun `drawing a field and reading the drawing gives the field back, whichever way it runs`() {
        val field = Spectro.stft(Synth.render(listOf(Note(69, 0.1, 0.5), Note(76, 0.4, 0.5)), 22050), 22050, Spectro.Layout(), 60, 90)
        for (time in TimeAxis.entries) for (flip in listOf(false, true)) {
            val layout = Spectro.Layout(time = time, frequencyFlipped = flip, seconds = field.seconds)
            val back = Spectro.read(Spectro.render(field, layout), layout)
            assertArrayEquals("$time $flip", field.values, back.values, 0f)
        }
    }

    @Test
    fun `wavelets find a tone at its pitch`() {
        val rate = 22050
        val pcm = Synth.render(listOf(Note(69, 0.2, 1.0, 110)), rate)
        val axis = Spectro.Layout(highHz = 2093.0)
        val field = Spectro.cwt(pcm, rate, axis, 80, 300)
        assertEquals("cwt", field.method)
        val notes = Spectro.notes(field, Spectro.Layout(threshold = 0.75f, harmonics = false, voices = 1))
        val longest = notes.maxByOrNull { it.duration }
        assertEquals(69, longest?.pitch)
        assertTrue("too short: ${longest?.duration}", longest!!.duration > 0.6)
    }

    @Test
    fun `the inverse transform undoes the transform`() {
        val re = DoubleArray(16) { (it * 7 % 5).toDouble() }
        val im = DoubleArray(16)
        val original = re.copyOf()
        Spectro.fft(re, im)
        Spectro.inverseFft(re, im)
        assertArrayEquals(original, re, 1e-9)
    }

    // ------------------------------------------------------------------
    // Files that carry their history
    // ------------------------------------------------------------------

    /** The smallest real PNG: one grey pixel. */
    private fun tinyPng(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            val body = type.toByteArray() + data
            out.write(byteArrayOf(0, 0, (data.size shr 8).toByte(), data.size.toByte()))
            out.write(body)
            val crc = CRC32().apply { update(body) }.value
            out.write(byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte()))
        }
        chunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0))
        val deflater = Deflater().apply { setInput(byteArrayOf(0, 0x80.toByte())); finish() }
        val buf = ByteArray(64)
        val n = deflater.deflate(buf)
        chunk("IDAT", buf.copyOf(n))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test
    fun `a PNG keeps a note of where it came from`() {
        val png = tinyPng()
        val marked = Png.withText(png, "io-matrix", "{\"io\":1,\"é\":\"ü\"}")
        assertEquals("{\"io\":1,\"\\u00e9\":\"\\u00fc\"}", Png.texts(marked)["io-matrix"])
        assertTrue(Png.texts(png).isEmpty())
        // Still a PNG that ends where a PNG ends.
        assertTrue(Png.isPng(marked))
        assertEquals("IEND", String(marked, marked.size - 8, 4))
        val notPng = "hello".toByteArray()
        assertArrayEquals(notPng, Png.withText(notPng, "k", "v"))
    }

    @Test
    fun `a WAV keeps a note and its sound`() {
        val samples = ShortArray(100) { (it * 300).toShort() }
        val wav = Wav.write(samples, 16000, "made by io — ok")
        assertEquals("made by io \\u2014 ok", Wav.comment(wav))
        assertArrayEquals(samples, Wav.read(wav)!!.first)
        assertNull(Wav.comment(Wav.write(samples, 16000)))
    }

    @Test
    fun `a MIDI file keeps a note that is never taken for words`() {
        val song = Midi.readSong(Midi.write(listOf(Note(60, 0.0, 0.5)), meta = "{\"io\":1}"))
        assertEquals("{\"io\":1}", song.meta)
        assertTrue(song.lyrics.isEmpty())
        val sung = Midi.readSong(Midi.write(listOf(Note(60, 0.0, 0.5)), lyrics = listOf(Lyric(0.0, "la")), meta = "x"))
        assertEquals(listOf("la"), sung.lyrics.map { it.text })
    }

    @Test
    fun `a luma picture reads back as brightness`() {
        val picture = Luma(2, 2, floatArrayOf(0f, 0.25f, 0.5f, 1f))
        val field = Spectro.read(picture, Spectro.Layout(seconds = 2.0))
        assertEquals(2, field.frames)
        assertEquals(com.example.core.convert.FieldScale.BRIGHTNESS, field.scale)
        assertFalse(field.acoustic)
        // Time across, low at the bottom: the first moment is the left column, bottom first.
        assertEquals(0.5f, field[0, 0], 0f)
        assertEquals(0f, field[0, 1], 0f)
    }
}
