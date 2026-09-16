package com.example.core.hitmap

import android.graphics.Bitmap
import android.graphics.Color
import com.example.core.layout.NormRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a picture into key rectangles.
 *
 * Two ways in, because two different people ask for this.
 *
 * Someone who wants an exact layout paints a **mask**: one flat colour per key. That
 * is unambiguous, so [extractColorRegions] just groups pixels by colour and takes
 * bounding boxes — any shape, any arrangement, no guessing.
 *
 * Someone who has a **screenshot** of a keyboard they like has no mask, so
 * [detectGrid] infers one: it looks for the horizontal lines where the image stops
 * changing (the gaps between rows), then does the same within each row. It is a guess,
 * and it is presented as one — the result is a normal layout the user then edits.
 *
 * A third input, a greyscale **weight map**, does not define regions at all: it says
 * how strongly each area should attract a touch, and feeds the probabilistic hit model.
 */
object HitmapExtractor {

    data class Region(val bounds: NormRect, val color: Int, val pixelCount: Int)

    /** Working resolution. Big enough to keep thin gaps, small enough to be instant. */
    private const val WORK_SIZE = 360

    private fun scaled(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= WORK_SIZE) return bitmap
        val factor = WORK_SIZE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * factor).toInt()),
            max(1, (bitmap.height * factor).toInt()),
            true
        )
    }

    // -----------------------------------------------------------------------
    // Colour mask
    // -----------------------------------------------------------------------

    /**
     * One region per distinct colour.
     *
     * [tolerance] quantises the colour so a mask exported from a lossy format still
     * groups correctly. Regions smaller than [minPixelFraction] of the image are
     * dropped as compression noise, and near-white and near-black are treated as
     * background unless [includeExtremes] is set.
     */
    fun extractColorRegions(
        source: Bitmap,
        tolerance: Int = 16,
        minPixelFraction: Float = 0.0015f,
        includeExtremes: Boolean = false,
        maxRegions: Int = 200
    ): List<Region> {
        val bitmap = scaled(source)
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return emptyList()

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val step = (tolerance.coerceIn(1, 128))
        val buckets = HashMap<Int, IntArray>()  // key -> [minX, minY, maxX, maxY, count, colour]

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                if (Color.alpha(pixel) < 24) continue
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (!includeExtremes) {
                    if (r > 244 && g > 244 && b > 244) continue
                    if (r < 12 && g < 12 && b < 12) continue
                }
                val key = ((r / step) shl 16) or ((g / step) shl 8) or (b / step)
                val entry = buckets.getOrPut(key) { intArrayOf(x, y, x, y, 0, pixel) }
                entry[0] = min(entry[0], x)
                entry[1] = min(entry[1], y)
                entry[2] = max(entry[2], x)
                entry[3] = max(entry[3], y)
                entry[4]++
            }
        }

        val minPixels = (width * height * minPixelFraction).toInt().coerceAtLeast(4)
        return buckets.values
            .filter { it[4] >= minPixels }
            .sortedWith(compareBy({ it[1] }, { it[0] }))
            .take(maxRegions)
            .map { entry ->
                Region(
                    bounds = NormRect(
                        entry[0].toFloat() / width,
                        entry[1].toFloat() / height,
                        (entry[2] + 1).toFloat() / width,
                        (entry[3] + 1).toFloat() / height
                    ),
                    color = entry[5],
                    pixelCount = entry[4]
                )
            }
    }

    // -----------------------------------------------------------------------
    // Grid inference from a screenshot
    // -----------------------------------------------------------------------

    data class Grid(val rows: List<List<NormRect>>)

    /**
     * Infers rows and keys from a picture of a keyboard.
     *
     * Rows are found where a horizontal line of pixels barely changes across its
     * width — the gap between rows. Keys are found the same way vertically inside each
     * row. Deliberately simple: it gives a sensible starting point on a clean
     * screenshot and an obviously wrong one on a photo of a desk, which is the right
     * failure mode for something the user is about to edit anyway.
     */
    fun detectGrid(
        source: Bitmap,
        rowSensitivity: Float = 0.35f,
        keySensitivity: Float = 0.35f
    ): Grid {
        val bitmap = scaled(source)
        val width = bitmap.width
        val height = bitmap.height
        if (width < 8 || height < 8) return Grid(emptyList())

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        fun luminance(x: Int, y: Int): Int {
            val p = pixels[y * width + x]
            return (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        }

        // Horizontal energy per row: how much a row changes along its own width.
        val rowEnergy = FloatArray(height)
        for (y in 0 until height) {
            var sum = 0
            for (x in 1 until width) sum += abs(luminance(x, y) - luminance(x - 1, y))
            rowEnergy[y] = sum.toFloat() / width
        }

        val rowBands = bands(rowEnergy, rowSensitivity)
        if (rowBands.isEmpty()) return Grid(emptyList())

        val rows = rowBands.map { (top, bottom) ->
            val columnEnergy = FloatArray(width)
            for (x in 0 until width) {
                var sum = 0
                for (y in top + 1..bottom) sum += abs(luminance(x, y) - luminance(x, y - 1))
                columnEnergy[x] = sum.toFloat() / (bottom - top).coerceAtLeast(1)
            }
            val keyBands = bands(columnEnergy, keySensitivity)
            val usable = if (keyBands.isEmpty()) listOf(0 to width - 1) else keyBands
            usable.map { (left, right) ->
                NormRect(
                    left.toFloat() / width,
                    top.toFloat() / height,
                    (right + 1).toFloat() / width,
                    (bottom + 1).toFloat() / height
                )
            }
        }.filter { it.isNotEmpty() }

        return Grid(rows)
    }

    /**
     * Contiguous runs where [energy] is above a threshold derived from its own mean,
     * which makes the detection independent of how contrasty the screenshot is.
     */
    private fun bands(energy: FloatArray, sensitivity: Float): List<Pair<Int, Int>> {
        if (energy.isEmpty()) return emptyList()
        val mean = energy.average().toFloat()
        if (mean <= 0f) return emptyList()
        val threshold = mean * sensitivity.coerceIn(0.05f, 2f)

        val out = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (i in energy.indices) {
            val active = energy[i] > threshold
            if (active && start < 0) start = i
            if (!active && start >= 0) {
                if (i - start >= 2) out += start to i - 1
                start = -1
            }
        }
        if (start >= 0 && energy.size - start >= 2) out += start to energy.size - 1

        // Merge runs separated by a gap too small to be a real key boundary.
        val minGap = (energy.size / 60).coerceAtLeast(1)
        val merged = mutableListOf<Pair<Int, Int>>()
        out.forEach { band ->
            val last = merged.lastOrNull()
            if (last != null && band.first - last.second <= minGap) {
                merged[merged.size - 1] = last.first to band.second
            } else {
                merged += band
            }
        }
        // Drop slivers.
        val minSize = (energy.size / 40).coerceAtLeast(2)
        return merged.filter { it.second - it.first >= minSize }
    }

    // -----------------------------------------------------------------------
    // Greyscale weight map
    // -----------------------------------------------------------------------

    /** True when the image is (near enough) greyscale, so it is a weight map. */
    fun isGreyscale(source: Bitmap, tolerance: Int = 12): Boolean {
        val bitmap = scaled(source)
        val stepX = max(1, bitmap.width / 24)
        val stepY = max(1, bitmap.height / 24)
        var checked = 0
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (abs(r - g) > tolerance || abs(r - b) > tolerance || abs(g - b) > tolerance) {
                    return false
                }
                checked++
                y += stepY
            }
            x += stepX
        }
        return checked > 0
    }

    /**
     * Mean brightness under [bounds], as 0..1. Used as a key's touch weight, so a
     * weight map can make some keys easier to hit than others without changing the
     * layout's geometry.
     */
    fun averageWeight(source: Bitmap, bounds: NormRect): Float {
        val bitmap = scaled(source)
        val left = (bounds.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val right = (bounds.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val top = (bounds.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = (bounds.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)

        var total = 0L
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                total += Color.red(bitmap.getPixel(x, y)).toLong()
                count++
                x += 2
            }
            y += 2
        }
        return if (count == 0) 1f else (total.toFloat() / count / 255f)
    }
}
