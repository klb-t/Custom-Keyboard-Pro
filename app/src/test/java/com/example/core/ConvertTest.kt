package com.example.core

import com.example.core.convert.ConvertGraph
import com.example.core.convert.Kind
import com.example.core.convert.Luma
import com.example.core.convert.Lyric
import com.example.core.convert.Midi
import com.example.core.convert.Note
import com.example.core.convert.NoteText
import com.example.core.convert.PicturePrep
import com.example.core.convert.Rules
import com.example.core.convert.SingAlong
import com.example.core.convert.Spectro
import com.example.core.convert.Step
import com.example.core.convert.Synth
import com.example.core.convert.TimeAxis
import com.example.core.convert.Wav
import com.example.core.convert.Where
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** The converter's arithmetic: files, notes, spectrograms and the planning of paths. */
class ConvertTest {

    private val tune = listOf(
        Note(60, 0.0, 0.4, 100),
        Note(64, 0.5, 0.4, 80),
        Note(67, 1.0, 0.9, 60),
        Note(72, 1.0, 0.9, 60)
    )

    private fun ids(path: List<Step>?) = path?.map { it.id }

    // ------------------------------------------------------------------
    // MIDI
    // ------------------------------------------------------------------

    @Test
    fun `a MIDI file reads back the notes it was written with`() {
        for (bpm in listOf(60.0, 90.0, 120.0, 173.0)) {
            val back = Midi.read(Midi.write(tune, bpm))
            assertEquals(tune.size, back.size)
            tune.sortedWith(compareBy({ it.start }, { it.pitch })).zip(back.sortedWith(compareBy({ it.start }, { it.pitch })))
                .forEach { (a, b) ->
                    assertEquals(a.pitch, b.pitch)
                    assertEquals(a.velocity, b.velocity)
                    assertEquals("start at $bpm bpm", a.start, b.start, 0.005)
                    assertEquals("length at $bpm bpm", a.duration, b.duration, 0.005)
                }
        }
    }

    @Test
    fun `words ride along with the notes as lyric events`() {
        val words = listOf(Lyric(0.0, "Wlazł "), Lyric(0.5, "ko"), Lyric(1.0, "tek"))
        val song = Midi.readSong(Midi.write(tune, 100.0, lyrics = words))
        assertEquals(words.map { it.text }, song.lyrics.map { it.text })
        words.zip(song.lyrics).forEach { (a, b) -> assertEquals(a.at, b.at, 0.005) }
        assertEquals(tune.size, song.notes.size)
    }

    @Test
    fun `a cut off MIDI file gives what it has instead of throwing`() {
        val whole = Midi.write(tune)
        for (cut in listOf(10, 20, 30)) {
            val partial = Midi.readSong(whole.copyOf(whole.size - cut))
            assertTrue(partial.notes.size <= tune.size)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `something that is not MIDI is refused plainly`() {
        Midi.read("hello, this is not a MIDI file at all".toByteArray())
    }

    // ------------------------------------------------------------------
    // Notes as text
    // ------------------------------------------------------------------

    @Test
    fun `note names are read with lengths and rests`() {
        val notes = NoteText.parse("C4 E4 G4 C5:2 - G4", 120.0)
        assertEquals(listOf(60, 64, 67, 72, 67), notes.map { it.pitch })
        assertEquals(listOf(0.0, 0.5, 1.0, 1.5, 3.0), notes.map { it.start })
        assertEquals(0.95, notes[3].duration, 1e-9)
    }

    @Test
    fun `chords are written with a plus and share one length`() {
        val notes = NoteText.parse("C4+E4+G4:2 A3", 120.0)
        assertEquals(listOf(60, 64, 67, 57), notes.map { it.pitch })
        assertEquals(listOf(0.0, 0.0, 0.0, 1.0), notes.map { it.start })
    }

    @Test
    fun `text written from notes reads back as it was written`() {
        listOf(
            "C4 E4 G4 C5:2 - G4",
            "C4+E4+G4:2 -:0.5 A3",
            "- - C#4:0.5 D4:1.5 Bb3",
            "A4"
        ).forEach { line ->
            assertEquals(line.replace("Bb3", "A#3"), NoteText.write(NoteText.parse(line, 96.0), 96.0))
        }
    }

    @Test
    fun `note names and numbers agree`() {
        assertEquals(69, NoteText.pitchOf("A4"))
        assertEquals(0, NoteText.pitchOf("C-1"))
        assertEquals(58, NoteText.pitchOf("Bb3"))
        assertEquals(61, NoteText.pitchOf("c#4"))
        assertNull(NoteText.pitchOf("H4"))
        assertEquals("C#4", NoteText.name(61))
        assertEquals("C-1", NoteText.name(0))
        (0..127).forEach { assertEquals(it, NoteText.pitchOf(NoteText.name(it))) }
    }

    // ------------------------------------------------------------------
    // WAV
    // ------------------------------------------------------------------

    @Test
    fun `a WAV file reads back its samples and rate`() {
        val samples = ShortArray(1000) { (it * 37 % 20000 - 10000).toShort() }
        val (back, rate) = Wav.read(Wav.write(samples, 16000))!!
        assertEquals(16000, rate)
        assertArrayEquals(samples, back)
    }

    @Test
    fun `a WAV written while streaming, with no size, is read to the end`() {
        val samples = ShortArray(500) { it.toShort() }
        val bytes = Wav.write(samples, 8000)
        // The data chunk's size, as a recorder that never went back to fill it in leaves it.
        for (i in 40..43) bytes[i] = 0
        assertArrayEquals(samples, Wav.read(bytes)!!.first)
    }

    @Test
    fun `pieces of speech join into one recording`() {
        val a = ShortArray(3) { 1 } to 22050
        val b = ShortArray(2) { 2 } to 22050
        val (joined, rate) = Wav.join(listOf(a, b))!!
        assertEquals(22050, rate)
        assertArrayEquals(shortArrayOf(1, 1, 1, 2, 2), joined)
    }

    // ------------------------------------------------------------------
    // Spectrograms
    // ------------------------------------------------------------------

    @Test
    fun `the transform finds a tone in its bin`() {
        val n = 64
        val re = DoubleArray(n) { cos(2 * Math.PI * 8 * it / n) }
        val im = DoubleArray(n)
        Spectro.fft(re, im)
        val mag = DoubleArray(n) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        assertEquals(32.0, mag[8], 1e-6)
        assertEquals(32.0, mag[56], 1e-6)
        assertTrue(mag.filterIndexed { i, _ -> i != 8 && i != 56 }.all { it < 1e-6 })
    }

    @Test
    fun `frequency and position along the axis are inverses`() {
        listOf(Spectro.Layout(), Spectro.Layout(logFrequency = false, lowHz = 0.001, highHz = 8000.0)).forEach { l ->
            listOf(0.0, 0.25, 0.5, 1.0).forEach { f ->
                assertEquals(f, Spectro.positionOf(Spectro.frequencyAt(f, l), l), 1e-9)
            }
        }
    }

    @Test
    fun `notes drawn as a spectrogram read back whichever way the picture runs`() {
        val notes = listOf(Note(57, 0.5, 1.0, 110), Note(69, 2.0, 0.5, 90), Note(76, 2.0, 1.5, 70), Note(62, 4.0, 2.0, 100))
        for (time in TimeAxis.entries) for (flipped in listOf(false, true)) for (log in listOf(true, false)) {
            // A linear axis spends its rows evenly, so low semitones share one; the range
            // is narrowed until every note here has rows of its own.
            val layout = if (log) Spectro.Layout(time = time, frequencyFlipped = flipped, seconds = 8.0)
            else Spectro.Layout(time = time, frequencyFlipped = flipped, logFrequency = false, lowHz = 200.0, highHz = 2000.0, seconds = 8.0)
            val (w, h) = if (time.vertical) 400 to 600 else 600 to 400
            val back = Spectro.toNotes(Spectro.draw(notes, layout, w, h), layout)
            val what = "$time flipped=$flipped log=$log"
            assertEquals(what, notes.map { it.pitch }.sorted(), back.map { it.pitch }.sorted())
            notes.forEach { n ->
                val b = back.first { it.pitch == n.pitch }
                assertEquals("$what start of ${n.pitch}", n.start, b.start, 0.03)
                assertEquals("$what length of ${n.pitch}", n.duration, b.duration, 0.03)
            }
        }
    }

    @Test
    fun `a waterfall picture is read down the page`() {
        // Time runs down: the first second of four is the top quarter of the rows.
        val layout = Spectro.Layout(time = TimeAxis.DOWN, seconds = 4.0)
        val picture = Spectro.draw(listOf(Note(69, 0.0, 1.0)), layout, 300, 400)
        val lit = (0 until picture.height).filter { y -> (0 until picture.width).any { x -> picture[x, y] > 0.5f } }
        assertEquals(0, lit.first())
        assertEquals(99, lit.last())
    }

    @Test
    fun `overtones fold into the note they belong to`() {
        // C3 with its octave and twelfth above, as a sung C3 shows, and an unrelated E4.
        val stack = listOf(Note(48, 0.0, 1.0), Note(60, 0.0, 1.0), Note(67, 0.0, 1.0), Note(64, 2.0, 1.0))
        val layout = Spectro.Layout(seconds = 4.0)
        val picture = Spectro.draw(stack, layout)
        assertEquals(listOf(48, 60, 67, 64), Spectro.toNotes(picture, layout).map { it.pitch })
        assertEquals(listOf(48, 64), Spectro.toNotes(picture, layout.copy(harmonics = false)).map { it.pitch })
    }

    @Test
    fun `a melody keeps the loudest note at each moment`() {
        val layout = Spectro.Layout(seconds = 2.0, voices = 1)
        val picture = Spectro.draw(listOf(Note(60, 0.0, 1.0, 127), Note(64, 0.0, 1.0, 30)), layout)
        assertEquals(listOf(60), Spectro.toNotes(picture, layout).map { it.pitch })
    }

    @Test
    fun `a played tone is found at its pitch`() {
        val rate = 22050
        val pcm = Synth.render(listOf(Note(69, 0.1, 1.0, 110)), rate)
        val layout = Spectro.Layout(seconds = pcm.size / rate.toDouble(), threshold = 0.75f, harmonics = false, voices = 1)
        val picture = Spectro.of(pcm, rate, layout, width = 80, height = 450, window = Spectro.windowFor(rate))
        val notes = Spectro.toNotes(picture, layout)
        val longest = notes.maxByOrNull { it.duration }
        assertNotNull(longest)
        assertEquals(69, longest!!.pitch)
        assertTrue("too short: ${longest.duration}", longest.duration > 0.7)
        assertEquals(0.1, longest.start, 0.08)
    }

    @Test
    fun `semitones too close for the picture get no rows rather than shared ones`() {
        val coarse = Spectro.Layout(logFrequency = false, lowHz = 55.0, highHz = 4186.0)
        val bands = Spectro.bands(coarse, 100)
        val used = bands.filterNotNull().flatMap { it.toList() }
        assertEquals(used.size, used.toSet().size)
        // 42 Hz a row: A1 has row 0 to itself, and the next row is already G2.
        assertNotNull(bands.first())
        assertNull(bands[1])
        assertTrue(bands.count { it == null } >= 9)
        assertNotNull(bands.last())
    }

    @Test
    fun `a picture played as sound shows the picture again`() {
        val rate = 22050
        val layout = Spectro.Layout(seconds = 1.5)
        val picture = Spectro.draw(listOf(Note(69, 0.3, 0.8)), layout, 150, 400)
        val pcm = Spectro.sonify(picture, layout, rate)
        assertEquals((1.5 * rate).toInt(), pcm.size)
        val heard = Spectro.of(pcm, rate, layout.copy(threshold = 0.75f, voices = 1), 60, 450, Spectro.windowFor(rate))
        val notes = Spectro.toNotes(heard, layout.copy(threshold = 0.75f, voices = 1))
        assertEquals(69, notes.maxByOrNull { it.duration }?.pitch)
    }

    @Test
    fun `silence makes a dark picture and no notes`() {
        val picture = Spectro.of(ShortArray(5000), 22050, Spectro.Layout(), 40, 100, 1024)
        assertTrue(picture.values.all { it == 0f })
        assertTrue(Spectro.toNotes(picture, Spectro.Layout()).isEmpty())
    }

    @Test
    fun `the window is a power of two about a sixth of a second long`() {
        for (rate in listOf(8000, 16000, 22050, 44100, 48000)) {
            val w = Spectro.windowFor(rate)
            assertEquals(0, w and (w - 1))
            assertTrue(w >= rate / 6 || w == 16384)
            assertTrue(w / 2 < rate / 6 || w == 512)
        }
    }

    // ------------------------------------------------------------------
    // Pictures
    // ------------------------------------------------------------------

    @Test
    fun `the spectrogram's area is given in percent or in fractions`() {
        assertArrayEquals(floatArrayOf(0.1f, 0.05f, 0.95f, 0.9f), PicturePrep.area("10,5,95,90"), 1e-6f)
        assertArrayEquals(floatArrayOf(0.1f, 0f, 1f, 1f), PicturePrep.area("0.1 0 1 1"), 1e-6f)
        assertNull(PicturePrep.area("10,5,95"))
        assertNull(PicturePrep.area("90,5,10,95"))
        assertNull(PicturePrep.area(null))
    }

    @Test
    fun `a crop keeps exactly the part inside the edges`() {
        val picture = Luma(4, 2, floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f))
        val right = picture.crop(0.5f, 0f, 1f, 1f)
        assertEquals(2, right.width)
        assertEquals(2, right.height)
        assertArrayEquals(floatArrayOf(2f, 3f, 6f, 7f), right.values, 0f)
    }

    @Test
    fun `mostly light pictures are inverted unless told otherwise`() {
        val page = Luma(2, 1, floatArrayOf(0.9f, 0.8f))
        val night = Luma(2, 1, floatArrayOf(0.1f, 0.2f))
        assertTrue(PicturePrep.invert(null, page))
        assertFalse(PicturePrep.invert(null, night))
        assertFalse(PicturePrep.invert("off", page))
        assertTrue(PicturePrep.invert("on", night))
        assertArrayEquals(floatArrayOf(0.1f, 0.2f), PicturePrep.prepare(page, null, "auto").values, 1e-6f)
    }

    @Test
    fun `words are spread over the notes they are sung to`() {
        val notes = (0 until 6).map { Note(60 + it, it * 0.5, 0.4) }
        val lyrics = SingAlong.lay(listOf("one", "two", "three"), notes)
        assertEquals(listOf(0.0, 1.0, 2.0), lyrics.map { it.at })
        assertEquals("one two three", lyrics.joinToString("") { it.text })
        assertTrue(SingAlong.lay(emptyList(), notes).isEmpty())
        assertTrue(SingAlong.lay(listOf("a"), emptyList()).isEmpty())
    }

    // ------------------------------------------------------------------
    // Planning
    // ------------------------------------------------------------------

    /** Plans the way the runner does: [providers] says which provider steps are set up. */
    private fun plan(
        from: Kind,
        to: Kind,
        providers: Set<String> = emptySet(),
        isNotes: Boolean = false,
        use: String? = null,
        avoid: String? = null
    ): List<Step>? {
        val requested = Rules.ids(use)
        val avoided = Rules.ids(avoid).toSet()
        val usable: (Step) -> Boolean = { step ->
            Rules.allows(step, from, isNotes, requested.toSet(), avoided) { s ->
                s.where !is Where.Provider || s.id in providers
            }
        }
        val through = requested.mapNotNull { ConvertGraph.byId(it) }
        return if (through.isEmpty()) ConvertGraph.plan(from, to, usable) else ConvertGraph.planThrough(from, to, through, usable)
    }

    @Test
    fun `the user's example - a spectrogram picture into MIDI - needs nothing but the phone`() {
        assertEquals(listOf("spectrogram_notes"), ids(plan(Kind.IMAGE, Kind.MIDI)))
    }

    @Test
    fun `a picture heard is the picture played, not rounded to notes`() {
        assertEquals(listOf("sonify"), ids(plan(Kind.IMAGE, Kind.AUDIO)))
        assertEquals(listOf("spectrogram_notes", "synth"), ids(plan(Kind.IMAGE, Kind.AUDIO, avoid = "sonify")))
    }

    @Test
    fun `text out of a picture is reading it, never the names of notes in it`() {
        assertEquals(listOf("ocr"), ids(plan(Kind.IMAGE, Kind.TEXT, providers = setOf("ocr"))))
        assertNull(plan(Kind.IMAGE, Kind.TEXT))
    }

    @Test
    fun `text out of a recording is what was said, and its notes only when asked`() {
        assertEquals(listOf("transcribe"), ids(plan(Kind.AUDIO, Kind.TEXT, providers = setOf("transcribe"))))
        assertNull(plan(Kind.AUDIO, Kind.TEXT))
        assertEquals(listOf("pitch", "notes_out"), ids(plan(Kind.AUDIO, Kind.TEXT, use = "notes_out")))
    }

    @Test
    fun `prose is spoken and a melody is played`() {
        assertEquals(listOf("speak"), ids(plan(Kind.TEXT, Kind.AUDIO)))
        assertEquals(listOf("notes_in", "synth"), ids(plan(Kind.TEXT, Kind.AUDIO, isNotes = true)))
    }

    @Test
    fun `the phone is preferred to a provider, and a provider can be asked for`() {
        assertEquals(listOf("speak"), ids(plan(Kind.TEXT, Kind.AUDIO, providers = setOf("speak_provider"))))
        assertEquals(listOf("speak_provider"), ids(plan(Kind.TEXT, Kind.AUDIO, providers = setOf("speak_provider"), use = "speak_provider")))
        assertEquals(listOf("typeset"), ids(plan(Kind.TEXT, Kind.IMAGE, providers = setOf("draw"))))
        assertEquals(listOf("draw"), ids(plan(Kind.TEXT, Kind.IMAGE, providers = setOf("draw"), use = "draw")))
    }

    @Test
    fun `text hidden in sound goes through a picture of it`() {
        assertEquals(listOf("typeset", "sonify"), ids(plan(Kind.TEXT, Kind.AUDIO, use = "typeset")))
    }

    @Test
    fun `a video gives up its sound and its pictures`() {
        assertEquals(listOf("soundtrack"), ids(plan(Kind.VIDEO, Kind.AUDIO)))
        assertEquals(listOf("frame"), ids(plan(Kind.VIDEO, Kind.IMAGE)))
        assertEquals(listOf("soundtrack", "transcribe"), ids(plan(Kind.VIDEO, Kind.TEXT, providers = setOf("transcribe", "ocr"))))
        assertEquals(listOf("soundtrack", "pitch"), ids(plan(Kind.VIDEO, Kind.MIDI)))
    }

    @Test
    fun `a MIDI file shows its note names and its picture`() {
        assertEquals(listOf("notes_out"), ids(plan(Kind.MIDI, Kind.TEXT)))
        assertEquals(listOf("piano_roll"), ids(plan(Kind.MIDI, Kind.IMAGE)))
        assertEquals(listOf("synth"), ids(plan(Kind.MIDI, Kind.AUDIO)))
    }

    @Test
    fun `nothing to do is an empty plan and an impossible one is none`() {
        assertEquals(emptyList<Step>(), plan(Kind.AUDIO, Kind.AUDIO))
        assertNull(plan(Kind.IMAGE, Kind.VIDEO))
        assertEquals(listOf("film"), ids(plan(Kind.TEXT, Kind.VIDEO, providers = setOf("film"))))
    }

    @Test
    fun `the graph's steps are distinct and each one says what it does`() {
        assertEquals(ConvertGraph.STEPS.size, ConvertGraph.STEPS.map { it.id }.toSet().size)
        ConvertGraph.STEPS.forEach { s ->
            assertTrue(s.id, s.label.isNotBlank())
            assertTrue(s.id, s.cost > 0)
            assertTrue(s.id, s.from != s.to)
        }
        assertEquals("audio → midi → text", ConvertGraph.describe(listOfNotNull(ConvertGraph.byId("pitch"), ConvertGraph.byId("notes_out"))))
    }

    @Test
    fun `melodies written as text are told apart from prose`() {
        assertTrue(Rules.looksLikeNotes("C4 E4 G4 - C5:2"))
        assertTrue(Rules.looksLikeNotes("C4+E4+G4:2, A3 | F#3"))
        assertFalse(Rules.looksLikeNotes("Hello world"))
        assertFalse(Rules.looksLikeNotes("A4 paper is the size of this page"))
        assertFalse(Rules.looksLikeNotes(""))
    }

    @Test
    fun `steps are named in any separator and unknown ones are ignored`() {
        assertEquals(listOf("pitch", "notes_out"), Rules.ids("pitch, notes_out"))
        assertEquals(listOf("typeset", "sonify"), Rules.ids("typeset>sonify teleport"))
        assertTrue(Rules.ids(null).isEmpty())
    }

    @Test
    fun `what to convert to is understood in plain words and formats`() {
        assertEquals(Kind.IMAGE, Kind.parse("jpg"))
        assertEquals(Kind.AUDIO, Kind.parse("wav"))
        assertEquals(Kind.MIDI, Kind.parse("midi"))
        assertEquals(Kind.TEXT, Kind.parse("notes"))
        assertNull(Kind.parse("smell"))
        assertEquals("wav", Rules.format("wav"))
        assertEquals("jpg", Rules.format("jpeg"))
        assertNull(Rules.format("audio"))
        assertTrue(Rules.wantsNoteNames("notes"))
        assertFalse(Rules.wantsNoteNames("text"))
        assertEquals(Kind.MIDI, Kind.ofMime("audio/midi"))
        assertEquals(Kind.AUDIO, Kind.ofMime("audio/ogg"))
        assertNull(Kind.ofMime("application/pdf"))
    }

    @Test
    fun `a note's frequency is where the pitch scale puts it`() {
        assertEquals(440.0, Synth.frequency(69), 1e-9)
        assertEquals(60.0, com.example.core.convert.Pitch.of(Synth.frequency(60)), 1e-9)
        assertTrue(abs(Synth.frequency(81) - 880.0) < 1e-9)
    }
}
