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

/** What the numbers in a [Field] mean, which decides what can be concluded from them. */
enum class FieldScale {
    /** Measured: 0 to 1 is 80 dB below the loudest up to the loudest. */
    DB,

    /** Read off a picture: brighter is louder, by how much is not known. */
    BRIGHTNESS,

    /** Drawn from notes: where a note is, as loud as its velocity; silence elsewhere. */
    IDEAL
}

/**
 * How much of each frequency there is at each moment — the information a
 * spectrogram or a scalogram shows, apart from any picture of it. [frames] moments
 * over [seconds], [bins] frequencies from [lowHz] to [highHz] (bin 0 the lowest),
 * values 0 to 1 in the sense [scale] says, and [method] saying how they were had.
 */
class Field(
    val frames: Int,
    val bins: Int,
    val values: FloatArray,
    val seconds: Double,
    val lowHz: Double,
    val highHz: Double,
    val logFrequency: Boolean,
    val scale: FieldScale,
    val method: String
) {
    init {
        require(values.size == frames * bins) { "a field of $frames × $bins needs ${frames * bins} values" }
    }

    operator fun get(frame: Int, bin: Int): Float = values[frame * bins + bin]

    /** Its frequency axis as a layout, for the arithmetic shared with pictures. */
    val axis: Spectro.Layout get() = Spectro.Layout(lowHz = lowHz, highHz = highHz, logFrequency = logFrequency, seconds = seconds)

    /** Whether it was measured from sound, so overtones and noise are to be expected. */
    val acoustic: Boolean get() = scale == FieldScale.DB
}

/**
 * Sound as a time–frequency field, the field as a picture, and back.
 *
 * The field is the information; a spectrogram picture is one way of showing it, in a
 * layout that is a parameter rather than a guess — which way time runs, whether the
 * frequency axis is linear or logarithmic, what range it covers — because one
 * program's spectrogram is not laid out like another's. Reading a picture as a field
 * is an inference under those parameters; drawing a field is not.
 */
object Spectro {

    /** How a spectrogram picture is laid out, and how notes are read from a field. */
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
        /** Level above which something is sounding, 0 to 1. */
        val threshold: Float = 0.5f,
        /** Shortest note kept, in seconds — shorter is noise. */
        val minNote: Double = 0.05,
        /**
         * Keep every partial as a note of its own. A sung or played note shows as a
         * stack of lines — the note and its overtones an octave, a twelfth, two
         * octaves up — and false keeps only the lowest of each stack, at the price of
         * also dropping real octaves played together.
         */
        val harmonics: Boolean = true,
        /**
         * At most this many notes at once, the loudest; 0 for no limit. One is a
         * melody — a voice, a whistle, a single line — with the accompaniment's
         * quieter notes left out.
         */
        val voices: Int = 0
    )

    /**
     * The parameters that say how a picture maps onto a field, as text — what a
     * rendering records about itself, and what reading a picture has to be told.
     */
    fun params(layout: Layout): Map<String, String> = linkedMapOf(
        "time" to layout.time.name.lowercase(),
        "flip" to layout.frequencyFlipped.toString(),
        "scale" to if (layout.logFrequency) "log" else "linear",
        "low" to layout.lowHz.toString(),
        "high" to layout.highHz.toString(),
        "seconds" to layout.seconds.toString()
    )

    /** [base] with whichever of [params]' values can be read; the rest left as they were. */
    fun withParams(base: Layout, params: Map<String, String>): Layout {
        fun num(k: String) = params[k]?.replace(',', '.')?.toDoubleOrNull()
        fun flag(k: String) = params[k]?.trim()?.lowercase()?.let { it in setOf("true", "on", "yes", "keep") }
        return base.copy(
            time = TimeAxis.parse(params["time"]) ?: base.time,
            frequencyFlipped = flag("flip") ?: base.frequencyFlipped,
            logFrequency = when (params["scale"]?.trim()?.lowercase()) {
                "linear", "lin" -> false
                "log", "logarithmic", "musical" -> true
                else -> base.logFrequency
            },
            lowHz = num("low")?.takeIf { it > 0 } ?: base.lowHz,
            highHz = num("high")?.takeIf { it > 0 } ?: base.highHz,
            seconds = num("seconds")?.takeIf { it > 0 } ?: base.seconds,
            threshold = num("threshold")?.toFloat()?.coerceIn(0.01f, 1f) ?: base.threshold,
            minNote = num("min_note")?.takeIf { it >= 0 } ?: base.minNote,
            harmonics = flag("harmonics") ?: base.harmonics,
            voices = params["voices"]?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: base.voices
        )
    }

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
     * axis is too coarse to tell semitones apart — low down on a linear one — some
     * pitches get none, and that is the truth about the axis rather than a gap.
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

    // ------------------------------------------------------------------
    // Field ↔ picture
    // ------------------------------------------------------------------

    /** The field drawn as a picture in [layout]'s orientation: one pixel per moment and bin. */
    fun render(field: Field, layout: Layout): Luma {
        val width = if (layout.time.vertical) field.bins else field.frames
        val height = if (layout.time.vertical) field.frames else field.bins
        val out = FloatArray(width * height)
        for (f in 0 until field.frames) for (b in 0 until field.bins) {
            out[index(f, b, width, height, layout)] = field[f, b]
        }
        return Luma(width, height, out)
    }

    /**
     * A picture read as a spectrogram laid out as [layout] says. Only brightness
     * survives the reading: how bright stands for how loud is not in the picture.
     */
    fun read(image: Luma, layout: Layout): Field {
        val (frames, bins) = axes(image.width, image.height, layout)
        val values = FloatArray(frames * bins)
        for (f in 0 until frames) for (b in 0 until bins) {
            values[f * bins + b] = image.values[index(f, b, image.width, image.height, layout)]
        }
        return Field(frames, bins, values, layout.seconds, layout.lowHz, layout.highHz, layout.logFrequency, FieldScale.BRIGHTNESS, "picture")
    }

    // ------------------------------------------------------------------
    // Field ↔ notes
    // ------------------------------------------------------------------

    /**
     * Notes in a field. For each moment, each semitone in range is sounding if the
     * loudest bin of its band is over the threshold; runs of it sounding become notes,
     * as loud as they were. Reading parameters come from [reading]; the frequency axis
     * and the duration are the field's own.
     */
    fun notes(field: Field, reading: Layout = Layout()): List<Note> {
        val frames = field.frames
        val bins = field.bins
        if (frames == 0 || bins == 0) return emptyList()
        val axis = field.axis
        val frameSeconds = field.seconds / frames
        val pitches = pitches(axis).toList()
        if (pitches.isEmpty()) return emptyList()
        val bands = bands(axis, bins)

        val level = Array(frames) { frame ->
            FloatArray(pitches.size) { i -> bands[i]?.maxOf { field[frame, it] } ?: 0f }
        }
        if (!reading.harmonics) for (row in level) {
            // From the top down, so each is compared with the lower ones as they were.
            for (i in pitches.indices.reversed()) {
                if (row[i] < reading.threshold) continue
                if (PARTIALS.any { d -> i - d >= 0 && row[i - d] >= reading.threshold }) row[i] = 0f
            }
        }
        if (reading.voices > 0) for (row in level) {
            val sounding = row.indices.filter { row[it] >= reading.threshold }
            if (sounding.size > reading.voices) {
                sounding.sortedByDescending { row[it] }.drop(reading.voices).forEach { row[it] = 0f }
            }
        }

        val notes = mutableListOf<Note>()
        val onSince = IntArray(pitches.size) { -1 }
        val loudest = FloatArray(pitches.size)
        for (frame in 0..frames) {
            for (i in pitches.indices) {
                val l = if (frame == frames) 0f else level[frame][i]
                val sounding = l >= reading.threshold
                val since = onSince[i]
                if (sounding && since < 0) {
                    onSince[i] = frame
                    loudest[i] = l
                } else if (sounding) {
                    loudest[i] = maxOf(loudest[i], l)
                } else if (since >= 0) {
                    onSince[i] = -1
                    val duration = (frame - since) * frameSeconds
                    if (duration >= reading.minNote) {
                        val v = (40 + 87 * loudest[i]).toInt().coerceIn(1, 127)
                        notes += Note(pitches[i], since * frameSeconds, duration, v)
                    }
                }
            }
        }
        return notes.sortedWith(compareBy({ it.start }, { it.pitch }))
    }

    /**
     * The field pure tones of [notes] would make, on [axis]'s frequency axis over its
     * duration: [frames] moments by [bins] frequencies. Louder is higher, and never so
     * low that the default threshold misses it.
     */
    fun fieldOf(notes: List<Note>, axis: Layout, frames: Int, bins: Int): Field {
        val out = FloatArray(frames * bins)
        val range = pitches(axis)
        val bands = if (bins > 0) bands(axis, bins) else emptyList()
        if (frames > 0 && bins > 0) notes.forEach { n ->
            if (n.pitch !in range) return@forEach
            // A pitch the axis is too coarse to have a row of its own for is drawn on
            // the nearest row, where it will read back as its neighbour.
            val band = bands[n.pitch - range.first]
                ?: (positionOf(Synth.frequency(n.pitch), axis) * (bins - 1)).roundToInt().coerceIn(0, bins - 1).let { it..it }
            val f0 = (n.start / axis.seconds * frames).roundToInt().coerceAtLeast(0)
            if (f0 >= frames) return@forEach
            val f1 = ((n.start + n.duration) / axis.seconds * frames).roundToInt().coerceIn(f0 + 1, frames)
            val v = 0.55f + 0.45f * n.velocity.coerceIn(1, 127) / 127f
            for (f in f0 until f1) for (b in band) {
                val i = f * bins + b
                out[i] = maxOf(out[i], v)
            }
        }
        return Field(frames, bins, out, axis.seconds, axis.lowHz, axis.highHz, axis.logFrequency, FieldScale.IDEAL, "notes")
    }

    // ------------------------------------------------------------------
    // Sound → field
    // ------------------------------------------------------------------

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

    /** The inverse transform, scaled so that it undoes [fft]. */
    fun inverseFft(re: DoubleArray, im: DoubleArray) {
        for (i in im.indices) im[i] = -im[i]
        fft(re, im)
        val n = re.size.toDouble()
        for (i in re.indices) {
            re[i] /= n
            im[i] = -im[i] / n
        }
    }

    private fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
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

    /** Magnitudes to 0–1 over the top 80 dB; silence stays at zero rather than becoming loud. */
    private fun decibels(mag: Array<DoubleArray>, frames: Int, bins: Int): FloatArray {
        val out = FloatArray(frames * bins)
        var peak = -1e9
        val db = Array(frames) { f -> DoubleArray(bins) { b -> (20 * log10(mag[f][b] + 1e-9)).also { if (it > peak) peak = it } } }
        if (peak < -150) return out
        for (f in 0 until frames) for (b in 0 until bins) {
            out[f * bins + b] = ((db[f][b] - (peak - 80)) / 80).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }

    /**
     * The short-time Fourier transform: [frames] moments by [bins] frequencies on
     * [axis]'s axis, each moment the sound around the middle of its share of the time,
     * windowed over [window] samples.
     */
    fun stft(samples: ShortArray, rate: Int, axis: Layout, frames: Int, bins: Int, window: Int = 2048): Field {
        val seconds = samples.size / rate.toDouble()
        if (frames <= 0 || bins <= 0) return Field(0, 0, FloatArray(0), seconds, axis.lowHz, axis.highHz, axis.logFrequency, FieldScale.DB, "stft")
        val re = DoubleArray(window)
        val im = DoubleArray(window)
        val mag = Array(frames) { DoubleArray(bins) }
        val span = (bins - 1.0).coerceAtLeast(1.0)
        for (f in 0 until frames) {
            // Each moment is the sound around the middle of its share of the time, so a
            // field of N seconds puts things where they happen.
            val start = ((f + 0.5) * samples.size / frames).toInt() - window / 2
            for (i in 0 until window) {
                val at = start + i
                val s = if (at >= 0 && at < samples.size) samples[at] / 32768.0 else 0.0
                re[i] = s * (0.5 - 0.5 * cos(2 * PI * i / (window - 1)))
                im[i] = 0.0
            }
            fft(re, im)
            for (b in 0 until bins) {
                // A bin covers the frequencies half a bin either side of it; at the top of
                // a logarithmic axis that is several of the transform's, and the loudest
                // of them is the one that should count.
                val kLo = (frequencyAt((b - 0.5) / span, axis) / rate * window).roundToInt().coerceIn(0, window / 2)
                val kHi = (frequencyAt((b + 0.5) / span, axis) / rate * window).roundToInt().coerceIn(kLo, window / 2)
                var m = 0.0
                for (k in kLo..kHi) m = maxOf(m, sqrt(re[k] * re[k] + im[k] * im[k]))
                mag[f][b] = m
            }
        }
        return Field(frames, bins, decibels(mag, frames, bins), seconds, axis.lowHz, axis.highHz, axis.logFrequency, FieldScale.DB, "stft")
    }

    /**
     * The continuous wavelet transform with a Morlet wavelet (ω₀ = [omega0]): for each
     * frequency on [axis]'s axis, how strongly the sound resembles a short burst at that
     * frequency, moment by moment. Unlike the Fourier transform, its time resolution is
     * finer high up and its frequency resolution finer low down — a scalogram.
     *
     * Computed in the frequency domain, one band at a time: the sound is transformed
     * once, each frequency's band is multiplied by the wavelet's Gaussian and brought
     * back only as finely as the output needs, so it costs about as much as one
     * transform of the sound per few frequencies rather than a convolution per sample.
     */
    fun cwt(samples: ShortArray, rate: Int, axis: Layout, frames: Int, bins: Int, omega0: Double = 6.0): Field {
        val seconds = samples.size / rate.toDouble()
        if (frames <= 0 || bins <= 0 || samples.isEmpty()) {
            return Field(frames.coerceAtLeast(0), bins.coerceAtLeast(0), FloatArray(frames.coerceAtLeast(0) * bins.coerceAtLeast(0)), seconds, axis.lowHz, axis.highHz, axis.logFrequency, FieldScale.DB, "cwt")
        }
        // Fewer samples where the top frequency allows: averaging blocks keeps what is
        // well below the new Nyquist frequency, which is all the axis asks for.
        val factor = (rate / (axis.highHz * 2.5)).toInt().coerceAtLeast(1)
        val r = rate / factor
        val count = samples.size / factor
        val n = nextPowerOfTwo(count.coerceAtLeast(2))
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until count) {
            var sum = 0.0
            for (k in 0 until factor) sum += samples[i * factor + k]
            re[i] = sum / factor / 32768.0
        }
        fft(re, im)
        val df = r.toDouble() / n
        val mag = Array(frames) { DoubleArray(bins) }
        val span = (bins - 1.0).coerceAtLeast(1.0)
        for (b in 0 until bins) {
            val f = frequencyAt(if (bins == 1) 0.5 else b / span, axis)
            if (f <= 0 || f >= r / 2.0) continue
            val s = omega0 / (2 * PI * f)
            // The wavelet's spectrum is a Gaussian around f, f/ω₀ wide; four widths hold all of it.
            val width = 4 * f / omega0 / df
            val kLo = (f / df - width).toInt().coerceAtLeast(1)
            val kHi = (f / df + width).toInt().coerceIn(kLo, n / 2)
            val m = nextPowerOfTwo(maxOf(kHi - kLo + 1, frames * 2)).coerceAtMost(n)
            val br = DoubleArray(m)
            val bi = DoubleArray(m)
            for (k in kLo..kHi) {
                val w = kotlin.math.exp(-0.5 * (s * 2 * PI * k * df - omega0).pow(2))
                // Shifted down to start at zero: the magnitude does not change, and the
                // band then fits a transform as short as the output needs.
                val j = (k - kLo) % m
                br[j] += re[k] * w
                bi[j] += im[k] * w
            }
            inverseFft(br, bi)
            // A shorter inverse is a coarser one: scaled back to what the full one gives.
            val gain = m.toDouble() / n
            val used = (count.toDouble() / n * m).coerceAtLeast(1.0)
            for (fr in 0 until frames) {
                val j0 = (fr * used / frames).toInt().coerceIn(0, m - 1)
                val j1 = ((fr + 1) * used / frames).toInt().coerceIn(j0 + 1, m)
                var peak = 0.0
                for (j in j0 until j1) peak = maxOf(peak, sqrt(br[j] * br[j] + bi[j] * bi[j]) * gain)
                mag[fr][b] = peak
            }
        }
        return Field(frames, bins, decibels(mag, frames, bins), seconds, axis.lowHz, axis.highHz, axis.logFrequency, FieldScale.DB, "cwt")
    }

    // ------------------------------------------------------------------
    // Field → sound
    // ------------------------------------------------------------------

    /**
     * The field played: every bin a sine at its frequency, as loud as the bin is,
     * moment by moment. The way pictures are hidden in music, and the faithful way to
     * hear a spectrogram — nothing is rounded to notes; the phase, which no field keeps,
     * is made up. Levels are read as decibels over the top [rangeDb].
     */
    fun resynthesize(field: Field, rate: Int = 22050, voices: Int = 256, rangeDb: Double = 60.0): ShortArray {
        val frames = field.frames
        val bins = field.bins
        if (frames == 0 || bins == 0) return ShortArray(0)
        val axis = field.axis
        val total = (field.seconds * rate).toInt().coerceIn(1, rate * 600)
        // Keep the work to a few seconds on a phone however large the field.
        val n = minOf(voices, bins, (150_000_000L / total).toInt().coerceAtLeast(8))
        val out = FloatArray(total)
        val tableSize = 4096
        val table = FloatArray(tableSize) { sin(2 * PI * it / tableSize).toFloat() }
        val span = (n - 1).coerceAtLeast(1).toDouble()
        for (v in 0 until n) {
            val pos = if (n == 1) 0.5 else v / span
            val hz = frequencyAt(pos, axis)
            if (hz <= 0 || hz >= rate / 2.0) continue
            // The bins this voice stands for; the loudest of them at each moment.
            val lo = (((v - 0.5) / span) * (bins - 1)).roundToInt().coerceIn(0, bins - 1)
            val hi = (((v + 0.5) / span) * (bins - 1)).roundToInt().coerceIn(lo, bins - 1)
            val amp = FloatArray(frames) { f ->
                var b = 0f
                for (bin in lo..hi) b = maxOf(b, field[f, bin])
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

    // ------------------------------------------------------------------
    // The old shortcuts, each now a composition of the above
    // ------------------------------------------------------------------

    /** Notes out of a spectrogram picture: [read], then [notes]. */
    fun toNotes(image: Luma, layout: Layout): List<Note> = notes(read(image, layout), layout)

    /** Notes drawn as a spectrogram picture: [fieldOf], then [render]. */
    fun draw(notes: List<Note>, layout: Layout, width: Int = 800, height: Int = 400): Luma {
        val (frames, bins) = axes(width, height, layout)
        return render(fieldOf(notes, layout, frames, bins), layout)
    }

    /** A picture played as the sound whose spectrogram it is: [read], then [resynthesize]. */
    fun sonify(image: Luma, layout: Layout, rate: Int = 22050, voices: Int = 256, rangeDb: Double = 60.0): ShortArray =
        resynthesize(read(image, layout), rate, voices, rangeDb)

    /** The spectrogram picture of a recording: [stft], then [render]. */
    fun of(samples: ShortArray, rate: Int, layout: Layout, width: Int = 800, height: Int = 400, window: Int = 2048): Luma {
        val (frames, bins) = axes(width, height, layout)
        return render(stft(samples, rate, layout, frames, bins, window), layout)
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
