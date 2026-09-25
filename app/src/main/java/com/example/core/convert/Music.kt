package com.example.core.convert

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** One note: which, when, how long, how loud. Seconds, so no tempo is assumed. */
data class Note(val pitch: Int, val start: Double, val duration: Double, val velocity: Int = 90)

/** A word sung at a moment, in seconds — what karaoke files carry beside the notes. */
data class Lyric(val at: Double, val text: String)

/** Notes and the words sung to them, and what the file says about where it came from. */
data class Song(val notes: List<Note>, val lyrics: List<Lyric> = emptyList(), val meta: String? = null)

/**
 * Standard MIDI files, written and read — type 0, one track, the subset every
 * sequencer and phone opens. Enough to be the meeting point of everything musical
 * the converter does: a spectrogram becomes notes, notes become sound, text names
 * notes, a sung recording becomes a melody with its words, and a file from elsewhere
 * is read back into notes.
 */
object Midi {

    /** What starts the text event IO Matrix writes its history into. */
    const val META_MARK = "io-matrix:"

    fun write(
        notes: List<Note>,
        bpm: Double = 120.0,
        ticksPerQuarter: Int = 480,
        lyrics: List<Lyric> = emptyList(),
        /** Written as a text event at the start, marked so it is never taken for words. */
        meta: String? = null
    ): ByteArray {
        val ticksPerSecond = ticksPerQuarter * bpm / 60.0
        // At the same tick: notes end, then the word is shown, then notes start — the
        // order karaoke players expect, so a word lights up with its note.
        class Ev(val tick: Long, val order: Int, val bytes: ByteArray)
        val events = notes.flatMap { n ->
            val p = n.pitch.coerceIn(0, 127)
            val start = (n.start * ticksPerSecond).roundToInt().toLong()
            val end = ((n.start + n.duration) * ticksPerSecond).roundToInt().toLong().coerceAtLeast(start + 1)
            listOf(
                Ev(start, 2, byteArrayOf(0x90.toByte(), p.toByte(), n.velocity.coerceIn(1, 127).toByte())),
                Ev(end, 0, byteArrayOf(0x80.toByte(), p.toByte(), 0x40))
            )
        } + lyrics.filter { it.text.isNotBlank() }.map { l ->
            val text = l.text.toByteArray(Charsets.UTF_8)
            val meta = ByteArrayOutputStream()
            meta.write(0xFF)
            meta.write(0x05)
            writeVarLen(meta, text.size.toLong())
            meta.write(text)
            Ev((l.at * ticksPerSecond).roundToInt().toLong().coerceAtLeast(0), 1, meta.toByteArray())
        }

        val track = ByteArrayOutputStream()
        // Tempo, so the file plays at the speed the seconds were measured in.
        val usPerQuarter = (60_000_000.0 / bpm).roundToInt()
        track.write(byteArrayOf(0, 0xFF.toByte(), 0x51, 3,
            (usPerQuarter shr 16).toByte(), (usPerQuarter shr 8).toByte(), usPerQuarter.toByte()))
        meta?.let { m ->
            val text = (META_MARK + m).toByteArray(Charsets.UTF_8)
            track.write(0)
            track.write(0xFF)
            track.write(0x01)
            writeVarLen(track, text.size.toLong())
            track.write(text)
        }
        var last = 0L
        events.sortedWith(compareBy<Ev>({ it.tick }, { it.order })).forEach { e ->
            writeVarLen(track, e.tick - last)
            last = e.tick
            track.write(e.bytes)
        }
        writeVarLen(track, 0)
        track.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))

        val body = track.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray())
        out.write(int32(6))
        out.write(byteArrayOf(0, 0, 0, 1, (ticksPerQuarter shr 8).toByte(), ticksPerQuarter.toByte()))
        out.write("MTrk".toByteArray())
        out.write(int32(body.size))
        out.write(body)
        return out.toByteArray()
    }

    /** Notes out of a MIDI file, all tracks merged, times in seconds (tempo changes honoured). */
    fun read(bytes: ByteArray): List<Note> = readSong(bytes).notes

    /** Notes and words (lyric events, or text events where there are no lyric ones). */
    fun readSong(bytes: ByteArray): Song {
        val r = Reader(bytes)
        require(bytes.size >= 14 && r.ascii(4) == "MThd") { "not a MIDI file" }
        val headerLen = r.u32()
        r.skip(2) // format
        val tracks = r.u16()
        val division = r.u16()
        r.skip(headerLen - 6)
        require((division and 0x8000) == 0) { "time-code MIDI files are not supported" }
        require(division > 0) { "that MIDI file has no timing" }

        class Raw(val tick: Long, val on: Boolean, val ch: Int, val pitch: Int, val vel: Int)
        val raws = mutableListOf<Raw>()
        val tempos = mutableListOf(0L to 500_000)
        val words = mutableListOf<Pair<Long, String>>()
        val texts = mutableListOf<Pair<Long, String>>()
        repeat(tracks) {
            if (r.remaining() < 8) return@repeat
            if (r.ascii(4) != "MTrk") return@repeat
            val len = r.u32()
            val end = (r.pos + len).coerceAtMost(bytes.size)
            var tick = 0L
            var status = 0
            while (r.pos < end) {
                tick += r.varLen()
                if (r.pos >= end) break
                var b = r.u8()
                if (b < 0x80) {
                    if (status == 0) break // data with nothing to continue: a damaged track
                    r.pos-- // running status
                    b = status
                } else if (b < 0xF0) {
                    status = b
                }
                when {
                    b == 0xFF -> {
                        val type = r.u8()
                        val l = r.varLen().toInt().coerceAtLeast(0)
                        when {
                            type == 0x51 && l == 3 -> tempos += tick to ((r.u8() shl 16) or (r.u8() shl 8) or r.u8())
                            type == 0x05 || type == 0x01 -> {
                                val n = l.coerceAtMost(r.remaining())
                                val s = String(bytes, r.pos, n, Charsets.UTF_8)
                                r.skip(n)
                                val into = if (type == 0x05) words else texts
                                into += tick to s
                            }
                            else -> r.skip(l)
                        }
                    }
                    b == 0xF0 || b == 0xF7 -> r.skip(r.varLen().toInt())
                    (b and 0xF0) == 0x90 || (b and 0xF0) == 0x80 -> {
                        val p = r.u8()
                        val v = r.u8()
                        raws += Raw(tick, (b and 0xF0) == 0x90 && v > 0, b and 0x0F, p, v)
                    }
                    (b and 0xF0) == 0xC0 || (b and 0xF0) == 0xD0 -> r.skip(1)
                    else -> r.skip(2)
                }
            }
            r.pos = end
        }
        val tempoMap = tempos.sortedBy { it.first }
        fun seconds(tick: Long): Double {
            var t = 0.0
            var prevTick = 0L
            var us = 500_000
            for ((at, tempo) in tempoMap) {
                if (at >= tick) break
                t += (at - prevTick) * us / 1e6 / division
                prevTick = at
                us = tempo
            }
            return t + (tick - prevTick) * us / 1e6 / division
        }
        val open = mutableMapOf<Pair<Int, Int>, Raw>()
        val notes = mutableListOf<Note>()
        raws.sortedBy { it.tick }.forEach { e ->
            val k = e.ch to e.pitch
            if (e.on) {
                open[k] = e
            } else {
                open.remove(k)?.let { s ->
                    val a = seconds(s.tick)
                    notes += Note(s.pitch, a, (seconds(e.tick) - a).coerceAtLeast(0.01), s.vel)
                }
            }
        }
        // Text events also carry titles and copyright notices; they are only taken as
        // words when a file has no lyric events at all, as older karaoke files do.
        val meta = texts.firstOrNull { it.second.startsWith(META_MARK) }?.second?.removePrefix(META_MARK)
        val sung = words.ifEmpty { texts.filter { (_, s) -> !s.startsWith("@") && !s.startsWith(META_MARK) } }
        return Song(
            notes.sortedBy { it.start },
            sung.map { (tick, s) -> Lyric(seconds(tick), s) }.sortedBy { it.at },
            meta
        )
    }

    private fun writeVarLen(out: ByteArrayOutputStream, value: Long) {
        var v = value.coerceAtLeast(0)
        var buffer = v and 0x7F
        while (true) {
            v = v shr 7
            if (v == 0L) break
            buffer = (buffer shl 8) or ((v and 0x7F) or 0x80)
        }
        while (true) {
            out.write((buffer and 0xFF).toInt())
            if ((buffer and 0x80) != 0L) buffer = buffer shr 8 else break
        }
    }

    private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun remaining() = (b.size - pos).coerceAtLeast(0)
        // Past the end reads as zero rather than throwing, so a truncated file gives
        // what it has instead of nothing.
        fun u8(): Int = if (pos < b.size) b[pos++].toInt() and 0xFF else { pos++; 0 }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Int = (u16() shl 16) or u16()
        fun ascii(n: Int): String = String(b, pos.coerceAtMost(b.size), n.coerceAtMost(remaining()), Charsets.US_ASCII).also { pos += n }
        fun skip(n: Int) { pos = (pos + n.coerceAtLeast(0)).coerceAtMost(b.size) }
        fun varLen(): Long {
            var v = 0L
            repeat(4) {
                val c = u8()
                v = (v shl 7) or (c and 0x7F).toLong()
                if ((c and 0x80) == 0) return v
            }
            return v
        }
    }
}

/**
 * Words laid over a melody: each word on the start of a note, spread evenly when
 * there are more notes than words or the other way round. A transcription without
 * timings cannot do better, and it is what a singer following along needs.
 */
object SingAlong {
    fun lay(words: List<String>, notes: List<Note>): List<Lyric> {
        val w = words.filter { it.isNotBlank() }
        val onsets = notes.map { it.start }.distinct().sorted()
        if (w.isEmpty() || onsets.isEmpty()) return emptyList()
        return w.mapIndexed { i, word ->
            val at = onsets[(i.toLong() * onsets.size / w.size).toInt().coerceIn(0, onsets.lastIndex)]
            Lyric(at, if (i == w.lastIndex) word else "$word ")
        }
    }
}

/**
 * Notes as text, both ways: "C4 E4 G4 C5:2 - G4". A note name with an octave, an
 * optional length in beats after a colon, "-" for a beat of rest (or "-:0.5" for
 * other lengths), and "+" between notes sounding together: "C4+E4+G4:2". The simplest
 * way to write a melody on a keyboard, and the way a MIDI file is shown back as text.
 */
object NoteText {
    private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private const val NOTE = "[A-Ga-g][#b♯♭]?-?\\d"
    private val CHORD = Regex("^($NOTE(?:\\+$NOTE)*)(?::(\\d+(?:[.,]\\d+)?))?$")
    private val ONE = Regex("^([A-Ga-g])([#b♯♭]?)(-?\\d)$")
    private val REST = Regex("^-(?::(\\d+(?:[.,]\\d+)?))?$")

    fun name(pitch: Int): String = NAMES[pitch.mod(12)] + (Math.floorDiv(pitch, 12) - 1)

    /** A note's number from its name ("A4" is 69), or null. */
    fun pitchOf(name: String): Int? {
        val m = ONE.find(name.trim()) ?: return null
        val base = NAMES.indexOf(m.groupValues[1].uppercase())
        val acc = when (m.groupValues[2]) { "#", "♯" -> 1; "b", "♭" -> -1; else -> 0 }
        return ((m.groupValues[3].toInt() + 1) * 12 + base + acc).takeIf { it in 0..127 }
    }

    fun parse(text: String, bpm: Double = 120.0): List<Note> {
        val beat = 60.0 / bpm
        var t = 0.0
        val out = mutableListOf<Note>()
        text.split(Regex("[\\s,|;]+")).filter { it.isNotBlank() }.forEach { token ->
            if (token.length > 1 && token.all { it == '-' }) {
                t += beat * token.length
                return@forEach
            }
            val rest = REST.find(token)
            if (rest != null) {
                t += beat * (rest.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0)
                return@forEach
            }
            val m = CHORD.find(token) ?: return@forEach
            val beats = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: 1.0
            m.groupValues[1].split('+').mapNotNull { pitchOf(it) }.forEach { p ->
                out += Note(p, t, beat * beats * 0.95)
            }
            t += beat * beats
        }
        return out
    }

    /**
     * Notes as text, on a grid of quarter beats: notes starting together become a
     * chord, the time to the next one becomes the length, and silence long enough
     * becomes rests — so text written for [parse] comes back as it was written.
     */
    fun write(notes: List<Note>, bpm: Double = 120.0): String {
        if (notes.isEmpty()) return ""
        val beat = 60.0 / bpm
        fun q(seconds: Double) = (seconds / beat * 4).roundToInt() / 4.0
        val groups = notes.groupBy { q(it.start) }.toSortedMap()
        val starts = groups.keys.toList()
        val tokens = mutableListOf<String>()
        restTokens(starts.first(), tokens)
        starts.forEachIndexed { i, at ->
            val chord = groups.getValue(at).distinctBy { it.pitch }.sortedBy { it.pitch }
            val held = q(chord.maxOf { it.duration } / 0.95).coerceAtLeast(0.25)
            val gap = starts.getOrNull(i + 1)?.minus(at)
            val rest = if (gap == null || gap <= held) 0.0 else gap - held
            val length = if (gap == null) held else gap - rest
            tokens += chord.joinToString("+") { name(it.pitch) } + lengthSuffix(length)
            restTokens(rest, tokens)
        }
        return tokens.joinToString(" ")
    }

    private fun restTokens(beats: Double, into: MutableList<String>) {
        val whole = floor(beats + 1e-9).toInt()
        repeat(whole) { into += "-" }
        val part = beats - whole
        if (part > 1e-9) into += "-" + lengthSuffix(part)
    }

    private fun lengthSuffix(beats: Double): String =
        if (abs(beats - 1.0) < 1e-9) ""
        else ":" + "%.2f".format(java.util.Locale.ROOT, beats).trimEnd('0').trimEnd('.')
}

/** A plain synthesiser: notes to sound, for hearing what a picture or a file became. */
object Synth {
    fun frequency(pitch: Int): Double = 440.0 * 2.0.pow((pitch - 69) / 12.0)

    fun render(notes: List<Note>, rate: Int = 22050, maxSeconds: Double = 600.0): ShortArray {
        val end = (notes.maxOfOrNull { it.start + it.duration } ?: 0.0).coerceAtMost(maxSeconds) + 0.3
        val buf = FloatArray((end * rate).toInt().coerceAtLeast(1))
        notes.forEach { n ->
            val f = frequency(n.pitch)
            val a = n.velocity / 127.0 * 0.3
            val s0 = (n.start * rate).toInt()
            val len = (n.duration * rate).toInt()
            val release = (0.08 * rate).toInt()
            for (i in 0 until len + release) {
                val idx = s0 + i
                if (idx !in buf.indices) break
                val env = when {
                    i < 0.01 * rate -> i / (0.01 * rate)
                    i < len -> exp(-1.5 * i / rate.toDouble()).coerceAtLeast(0.35)
                    else -> 0.35 * (1.0 - (i - len).toDouble() / release)
                }
                val t = i.toDouble() / rate
                // A little of the octave above, so it sounds like an instrument and not a test tone.
                val wave = sin(2 * PI * f * t) + 0.3 * sin(4 * PI * f * t)
                buf[idx] += (a * env * wave).toFloat()
            }
        }
        val peak = buf.maxOfOrNull { abs(it) }?.coerceAtLeast(1e-6f) ?: 1f
        val gain = if (peak > 0.99f) 0.99f / peak else 1f
        return ShortArray(buf.size) { (buf[it] * gain * Short.MAX_VALUE).roundToInt().toShort() }
    }
}

/** 16-bit PCM WAV, written and read. */
object Wav {
    /** [comment], when given, goes into a LIST/INFO "ICMT" chunk, which players ignore. */
    fun write(samples: ShortArray, rate: Int, comment: String? = null): ByteArray {
        val data = samples.size * 2
        val note = comment?.let { Ascii.escape(it).toByteArray(Charsets.US_ASCII) + 0.toByte() }
        val noteChunk = note?.let { 4 + 8 + it.size + (it.size and 1) } ?: 0
        val out = ByteArrayOutputStream(44 + data + noteChunk + 8)
        fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); le32(36 + data + if (note != null) 8 + noteChunk else 0); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        if (note != null) {
            out.write("LIST".toByteArray()); le32(noteChunk); out.write("INFO".toByteArray())
            out.write("ICMT".toByteArray()); le32(note.size); out.write(note)
            if ((note.size and 1) == 1) out.write(0)
        }
        out.write("data".toByteArray()); le32(data)
        samples.forEach { le16(it.toInt()) }
        return out.toByteArray()
    }

    /** The comment a WAV carries in its INFO list, if it has one. */
    fun comment(bytes: ByteArray): String? {
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        fun le32(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or (bytes[at + 3].toInt() shl 24)
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4)
            val size = le32(p + 4)
            if (size < 0 || p + 8 + size > bytes.size) return null
            if (id == "LIST" && size >= 4 && String(bytes, p + 8, 4) == "INFO") {
                var q = p + 12
                while (q + 8 <= p + 8 + size) {
                    val sub = String(bytes, q, 4)
                    val len = le32(q + 4)
                    if (len < 0 || q + 8 + len > bytes.size) return null
                    if (sub == "ICMT") return String(bytes, q + 8, len, Charsets.US_ASCII).trimEnd('\u0000')
                    q += 8 + len + (len and 1)
                }
            }
            p += 8 + size + (size and 1)
        }
        return null
    }

    /**
     * Mono samples and the rate, from a 16-bit PCM WAV (channels mixed down); null if
     * it is not one. A data size of zero or past the end — what a file written while
     * streaming says — means "the rest of the file".
     */
    fun read(bytes: ByteArray): Pair<ShortArray, Int>? {
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        fun le16(at: Int) = (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)
        fun le32(at: Int) = (le16(at) and 0xFFFF) or (le16(at + 2) shl 16)
        var p = 12
        var channels = 1
        var rate = 0
        var bits = 16
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4)
            val size = le32(p + 4)
            if (id == "fmt ") {
                if (p + 24 > bytes.size || le16(p + 8) != 1) return null
                channels = le16(p + 10).coerceAtLeast(1)
                rate = le32(p + 12)
                bits = le16(p + 22)
            } else if (id == "data") {
                if (bits != 16 || rate <= 0) return null
                val available = bytes.size - p - 8
                val length = if (size <= 0 || size > available) available else size
                val count = length / 2 / channels
                val out = ShortArray(count) { i ->
                    var sum = 0
                    for (c in 0 until channels) sum += le16(p + 8 + (i * channels + c) * 2).toShort().toInt()
                    (sum / channels).toShort()
                }
                return out to rate
            }
            if (size < 0) return null
            p += 8 + size + (size and 1)
        }
        return null
    }

    /** Recordings joined end to end, at the first one's rate (the rest are assumed to match). */
    fun join(parts: List<Pair<ShortArray, Int>>): Pair<ShortArray, Int>? {
        if (parts.isEmpty()) return null
        val out = ShortArray(parts.sumOf { it.first.size })
        var at = 0
        parts.forEach { (s, _) ->
            s.copyInto(out, at)
            at += s.size
        }
        return out to parts.first().second
    }
}

/** Frequency ↔ pitch on the equal-tempered scale. */
object Pitch {
    fun of(frequency: Double): Double = 69 + 12 * ln(frequency / 440.0) / ln(2.0)
}
