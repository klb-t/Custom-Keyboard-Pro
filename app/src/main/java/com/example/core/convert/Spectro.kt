package com.example.core.convert

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A picture as brightness, 0 (dark) to 1 (bright), row by row from the top. */
class Luma(val width: Int, val height: Int, val values: FloatArray) {
    operator fun get(x: Int, y: Int): Float = values[y * width + x]

    /**
     * The part inside the given edges, as fractions of the width and height — to leave
     * out the axes, labels and colour bar a spectrogram picture usually has around it.
     */
    fun crop(left: Float, top: Float, right: Float, bottom: Float): Luma {
        if (width == 0 || height == 0) return this
        val x0 = (left.coerceIn(0f, 1f) * width).toInt().coerceIn(0, width - 1)
        val x1 = (right.coerceIn(0f, 1f) * width).toInt().coerceIn(x0 + 1, width)
        val y0 = (top.coerceIn(0f, 1f) * height).toInt().coerceIn(0, height - 1)
        val y1 = (bottom.coerceIn(0f, 1f) * height).toInt().coerceIn(y0 + 1, height)
        val w = x1 - x0
        val h = y1 - y0
        return Luma(w, h, FloatArray(w * h) { i -> this[x0 + i % w, y0 + i / w] })
    }

    /** Dark for loud: for spectrograms printed dark on white. */
    fun inverted(): Luma = Luma(width, height, FloatArray(values.size) { 1f - values[it] })
}

/** Which way time runs across a spectrogram picture. */
enum class TimeAxis {
    /** Left to right: the usual spectrogram. */
    RIGHT,

    /** Right to left. */
    LEFT,

    /** Top to bottom: a waterfall read from the top. */
    DOWN,

    /** Bottom to top: a waterfall as radios draw it, newest line at the top. */
    UP;

    val vertical: Boolean get() = this == DOWN || this == UP

    companion object {
        fun parse(raw: String?): TimeAxis? = when (raw?.trim()?.lowercase()) {
            "right", "across", "horizontal" -> RIGHT
            "left" -> LEFT
            "down", "waterfall", "vertical" -> DOWN
            "up" -> UP
            else -> null
        }
    }
}

/**
 * Sound as a picture and a picture as sound.
 *
 * Both directions of the example that asked for a converter of everything into
 * everything: a waterfall spectrogram saved as a JPEG turned back into notes, and a
 * recording turned into the spectrogram that would do it. The picture's layout is a
 * parameter, not a guess — which way time runs, whether the frequency axis is linear
 * or logarithmic, and what range it covers — because a spectrogram from one program
 * is not laid out like one from another.
 */
object Spectro {

    /** How a spectrogram picture is laid out. */
    data class Layout(
        val time: TimeAxis = TimeAxis.RIGHT,
        /**
         * Low frequencies where they usually are not: at the top when time runs
         * across, at the right when it runs down.
         */
        val frequencyFlipped: Boolean = false,
        /** Frequency axis logarithmic (musical) rather than linear. */
        val logFrequency: Boolean = true,
        val lowHz: Double = 55.0,
        val highHz: Double = 4186.0,
        /** How long the whole picture lasts, in seconds. */
        val seconds: Double = 10.0,
        /** Brightness above which something is sounding, 0 to 1. */
        val threshold: Float = 0.5f,
        /** Shortest note kept, in seconds — shorter is noise in the picture. */
        val minNote: Double = 0.05,
        /**
         * Keep every partial as a note of its own. A sung or played note shows in a
         * spectrogram as a stack of lines — the note and its overtones an octave, a
         * twelfth, two octaves up — and false keeps only the lowest of each stack, at
         * the price of also dropping real octaves played together.
         */
        val harmonics: Boolean = true,
        /**
         * At most this many notes at once, the loudest; 0 for no limit. One is a
         * melody — a voice, a whistle, a single line — with the accompaniment's
         * quieter notes left out.
         */
        val voices: Int = 0
    )

    /** Semitones from a note to its 2nd to 8th harmonics. */
    private val PARTIALS = intArrayOf(12, 19, 24, 28, 31, 34, 36)

    /** Moments and frequency bins of a picture this size in this layout. */
    fun axes(width: Int, height: Int, layout: Layout): Pair<Int, Int> =
        if (layout.time.vertical) height to width else width to height

    /** Where in the picture a moment and a frequency bin are (bin 0 the lowest), as an index into its values. */
    fun index(frame: Int, bin: Int, width: Int, height: Int, layout: Layout): Int {
        val (frames, bins) = axes(width, height, layout)
        val t = if (layout.time == TimeAxis.LEFT || layout.time == TimeAxis.UP) frames - 1 - frame else frame
        val b = if (layout.frequencyFlipped) bins - 1 - bin else bin
        // Time down the picture: low frequencies at the left. Time across: at the bottom.
        return if (layout.time.vertical) t * width + b else (bins - 1 - b) * width + t
    }

    /** Frequency of a position along the frequency axis, 0 at the low end, 1 at the high. */
    fun frequencyAt(f: Double, layout: Layout): Double =
        if (layout.logFrequency) layout.lowHz * (layout.highHz / layout.lowHz).pow(f)
        else layout.lowHz + f * (layout.highHz - layout.lowHz)

    /** Position along the frequency axis of a frequency — the inverse of [frequencyAt]. */
    fun positionOf(hz: Double, layout: Layout): Double =
        if (layout.logFrequency) ln(hz / layout.lowHz) / ln(layout.highHz / layout.lowHz)
        else (hz - layout.lowHz) / (layout.highHz - layout.lowHz)

    /** Pitches the layout's frequency range holds. */
    fun pitches(layout: Layout): IntRange =
        ceil(Pitch.of(layout.lowHz) - 1e-9).toInt()..floor(Pitch.of(layout.highHz) + 1e-9).toInt()

    /**
     * The bins along the frequency axis that belong to each pitch of [pitches]: those
     * nearer to it than to any other semitone, so no bin is ever shared. Where the
     * picture is too coarse to tell semitones apart — low down on a linear axis — some
     * pitches get none, and that is the truth about the picture rather than a gap.
     */
    fun bands(layout: Layout, bins: Int): List<IntRange?> {
        val range = pitches(layout)
        val lo = IntArray(range.count()) { -1 }
        val hi = IntArray(range.count()) { -1 }
        for (b in 0 until bins) {
            val pos = if (bins == 1) 0.5 else b / (bins - 1.0)
            val p = Pitch.of(frequencyAt(pos, layout)).roundToInt()
            if (p !in range) continue
            val i = p - range.first
            if (lo[i] < 0) lo[i] = b
            hi[i] = b
        }
        return range.map { p -> (p - range.first).let { i -> if (lo[i] < 0) null else lo[i]..hi[i] } }
    }

    /**
     * Notes out of a spectrogram picture. For each moment, each semitone in range is
     * sounding if the brightest pixel of its band is over the threshold; runs of it
     * sounding become notes, as loud as they are bright.
     */
    fun toNotes(image: Luma, layout: Layout): List<Note> {
        val (frames, bins) = axes(image.width, image.height, layout)
        if (frames == 0 || bins == 0) return emptyList()
        val frameSeconds = layout.seconds / frames
        val pitches = pitches(layout).toList()
        if (pitches.isEmpty()) return emptyList()
        val bands = bands(layout, bins)

        fun brightness(frame: Int, bin: Int) = image.values[index(frame, bin, image.width, image.height, layout)]

        val level = Array(frames) { frame ->
            FloatArray(pitches.size) { i -> bands[i]?.maxOf { brightness(frame, it) } ?: 0f }
        }
        if (!layout.harmonics) for (row in level) {
            // From the top down, so each is compared with the lower ones as they were.
            for (i in pitches.indices.reversed()) {
                if (row[i] < layout.threshold) continue
                if (PARTIALS.any { d -> i - d >= 0 && row[i - d] >= layout.threshold }) row[i] = 0f
            }
        }
        if (layout.voices > 0) for (row in level) {
            val sounding = row.indices.filter { row[it] >= layout.threshold }
            if (sounding.size > layout.voices) {
                sounding.sortedByDescending { row[it] }.drop(layout.voices).forEach { row[it] = 0f }
            }
        }

        val notes = mutableListOf<Note>()
        val onSince = IntArray(pitches.size) { -1 }
        val loudest = FloatArray(pitches.size)
        for (frame in 0..frames) {
            for (i in pitches.indices) {
                val l = if (frame == frames) 0f else level[frame][i]
                val sounding = l >= layout.threshold
                val since = onSince[i]
                if (sounding && since < 0) {
                    onSince[i] = frame
                    loudest[i] = l
                } else if (sounding) {
                    loudest[i] = maxOf(loudest[i], l)
                } else if (since >= 0) {
                    onSince[i] = -1
                    val duration = (frame - since) * frameSeconds
                    if (duration >= layout.minNote) {
                        val v = (40 + 87 * loudest[i]).toInt().coerceIn(1, 127)
                        notes += Note(pitches[i], since * frameSeconds, duration, v)
                    }
                }
            }
        }
        return notes.sortedWith(compareBy({ it.start }, { it.pitch }))
    }

    /**
     * Notes drawn as the spectrogram of a pure tone would show them — a piano roll in
     * [layout]'s shape, which [toNotes] reads back. Louder is brighter, and never so
     * dim that the default threshold misses it.
     */
    fun draw(notes: List<Note>, layout: Layout, width: Int = 800, height: Int = 400): Luma {
        val out = FloatArray(width * height)
        val (frames, bins) = axes(width, height, layout)
        if (frames == 0 || bins == 0) return Luma(width, height, out)
        val range = pitches(layout)
        val bands = bands(layout, bins)
        notes.forEach { n ->
            if (n.pitch !in range) return@forEach
            // A pitch the picture is too coarse to have a row of its own for is drawn on
            // the nearest row, where it will read back as its neighbour.
            val band = bands[n.pitch - range.first]
                ?: (positionOf(Synth.frequency(n.pitch), layout) * (bins - 1)).roundToInt().coerceIn(0, bins - 1).let { it..it }
            val f0 = (n.start / layout.seconds * frames).roundToInt().coerceAtLeast(0)
            if (f0 >= frames) return@forEach
            val f1 = ((n.start + n.duration) / layout.seconds * frames).roundToInt().coerceIn(f0 + 1, frames)
            val v = 0.55f + 0.45f * n.velocity.coerceIn(1, 127) / 127f
            for (f in f0 until f1) for (b in band) {
                val i = index(f, b, width, height, layout)
                out[i] = maxOf(out[i], v)
            }
        }
        return Luma(width, height, out)
    }

    /**
     * A picture played as the sound whose spectrogram it is: every row a sine at its
     * frequency, as loud as the row is bright, moment by moment. The way pictures are
     * hidden in music, and the faithful way to hear a spectrogram — nothing is rounded
     * to notes. Brightness is read as decibels over the top [rangeDb].
     */
    fun sonify(image: Luma, layout: Layout, rate: Int = 22050, voices: Int = 256, rangeDb: Double = 60.0): ShortArray {
        val (frames, bins) = axes(image.width, image.height, layout)
        if (frames == 0 || bins == 0) return ShortArray(0)
        val total = (layout.seconds * rate).toInt().coerceIn(1, rate * 600)
        // Keep the work to a few seconds on a phone however large the picture.
        val n = minOf(voices, bins, (150_000_000L / total).toInt().coerceAtLeast(8))
        val out = FloatArray(total)
        val tableSize = 4096
        val table = FloatArray(tableSize) { sin(2 * PI * it / tableSize).toFloat() }
        val span = (n - 1).coerceAtLeast(1).toDouble()
        for (v in 0 until n) {
            val pos = if (n == 1) 0.5 else v / span
            val hz = frequencyAt(pos, layout)
            if (hz <= 0 || hz >= rate / 2.0) continue
            // The rows this voice stands for; the brightest of them at each moment.
            val lo = (((v - 0.5) / span) * (bins - 1)).roundToInt().coerceIn(0, bins - 1)
            val hi = (((v + 0.5) / span) * (bins - 1)).roundToInt().coerceIn(lo, bins - 1)
            val amp = FloatArray(frames) { f ->
                var b = 0f
                for (row in lo..hi) b = maxOf(b, image.values[index(f, row, image.width, image.height, layout)])
                if (b <= 0.02f) 0f else 10.0.pow((b - 1.0) * rangeDb / 20).toFloat()
            }
            if (amp.all { it == 0f }) continue
            val step = hz / rate * tableSize
            // Spread starting phases, or every voice peaks together at the start.
            var phase = (v * 0.618034 * tableSize) % tableSize
            for (i in 0 until total) {
                val x = i.toDouble() * frames / total
                val f = x.toInt().coerceAtMost(frames - 1)
                val g = (f + 1).coerceAtMost(frames - 1)
                val a = amp[f] + (amp[g] - amp[f]) * (x - f).toFloat()
                phase += step
                if (phase >= tableSize) phase -= tableSize * floor(phase / tableSize)
                if (a > 0f) out[i] += a * table[phase.toInt().coerceIn(0, tableSize - 1)]
            }
        }
        val peak = out.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        val gain = if (peak > 0f) 0.9f / peak else 0f
        return ShortArray(total) { (out[it] * gain * Short.MAX_VALUE).roundToInt().toShort() }
    }

    /** In-place radix-2 FFT; [re] and [im] must be a power of two long. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n > 0 && (n and (n - 1)) == 0 && im.size == n) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            for (start in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val c = cos(angle * k)
                    val s = sin(angle * k)
                    val a = start + k
                    val b = a + len / 2
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            len = len shl 1
        }
    }

    /**
     * A transform window fine enough to tell semitones apart low down — about a sixth
     * of a second — as a power of two.
     */
    fun windowFor(rate: Int): Int {
        var w = 512
        while (w < rate / 6 && w < 16384) w = w shl 1
        return w
    }

    /**
     * The spectrogram of a recording, as a picture in [layout]'s shape: [width] by
     * [height] pixels, brightness on a decibel scale over the top 80 dB.
     */
    fun of(samples: ShortArray, rate: Int, layout: Layout, width: Int = 800, height: Int = 400, window: Int = 2048): Luma {
        val (frames, bins) = axes(width, height, layout)
        val out = FloatArray(width * height)
        if (frames == 0 || bins == 0) return Luma(width, height, out)
        val re = DoubleArray(window)
        val im = DoubleArray(window)
        val db = Array(frames) { DoubleArray(bins) }
        var peak = -1e9
        val span = (bins - 1.0).coerceAtLeast(1.0)
        for (f in 0 until frames) {
            // Each column is the sound around the middle of its share of the time, so a
            // picture of N seconds puts things where they happen.
            val start = ((f + 0.5) * samples.size / frames).toInt() - window / 2
            for (i in 0 until window) {
                val at = start + i
                val s = if (at >= 0 && at < samples.size) samples[at] / 32768.0 else 0.0
                re[i] = s * (0.5 - 0.5 * cos(2 * PI * i / (window - 1)))
                im[i] = 0.0
            }
            fft(re, im)
            for (b in 0 until bins) {
                // A row covers the frequencies half a row either side of it; at the top
                // of a logarithmic axis that is several of the transform's bins, and the
                // loudest of them is the one that should show.
                val kLo = (frequencyAt((b - 0.5) / span, layout) / rate * window).roundToInt().coerceIn(0, window / 2)
                val kHi = (frequencyAt((b + 0.5) / span, layout) / rate * window).roundToInt().coerceIn(kLo, window / 2)
                var mag = 0.0
                for (k in kLo..kHi) mag = maxOf(mag, sqrt(re[k] * re[k] + im[k] * im[k]))
                val v = 20 * log10(mag + 1e-9)
                db[f][b] = v
                if (v > peak) peak = v
            }
        }
        // Silence has no loudest part to measure from; it stays dark.
        if (peak < -150) return Luma(width, height, out)
        for (f in 0 until frames) for (b in 0 until bins) {
            out[index(f, b, width, height, layout)] = ((db[f][b] - (peak - 80)) / 80).coerceIn(0.0, 1.0).toFloat()
        }
        return Luma(width, height, out)
    }
}

/** Getting a picture ready to be read as a spectrogram. */
object PicturePrep {

    /**
     * "left,top,right,bottom" — the part of the picture that is the spectrogram
     * itself — as fractions when all are at most 1, as percentages otherwise.
     */
    fun area(raw: String?): FloatArray? {
        val n = raw.orEmpty().split(Regex("[\\s,;]+")).mapNotNull { it.toFloatOrNull() }
        if (n.size != 4) return null
        val scale = if (n.all { it <= 1f }) 1f else 100f
        val (l, t, r, b) = n.map { it / scale }
        if (r <= l || b <= t) return null
        return floatArrayOf(l, t, r, b)
    }

    /**
     * Whether loud is dark in this picture: "on" or "off", or — unless told — when the
     * picture is mostly light, as a spectrogram printed on paper or text on a page is.
     */
    fun invert(raw: String?, image: Luma): Boolean = when (raw?.trim()?.lowercase()) {
        "on", "true", "yes" -> true
        "off", "false", "no" -> false
        else -> image.values.isNotEmpty() && image.values.average() > 0.5
    }

    fun prepare(image: Luma, area: String?, invert: String?): Luma {
        val cropped = area(area)?.let { image.crop(it[0], it[1], it[2], it[3]) } ?: image
        return if (invert(invert, cropped)) cropped.inverted() else cropped
    }
}
