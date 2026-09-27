package com.example.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.example.core.matrix.Change
import com.example.core.matrix.ChangeKind
import com.example.core.matrix.Representation
import com.example.core.matrix.Representations
import java.io.ByteArrayOutputStream

/** File representation changes keep color channels; luminance is a separate transform. */
internal object ImageReencoder {
    data class Result(val bytes: ByteArray, val changes: List<Change>, val parameters: Map<String, String>)

    @Suppress("DEPRECATION")
    fun convert(source: ByteArray, target: Representation, quality: Int, maxPixels: Long): Result {
        val format = when (target) {
            Representations.PNG -> Bitmap.CompressFormat.PNG
            Representations.JPEG -> Bitmap.CompressFormat.JPEG
            Representations.WEBP -> Bitmap.CompressFormat.WEBP
            else -> error("No image encoder for ${target.label}")
        }
        require(quality in 0..100 && maxPixels > 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "That picture could not be read." }
        require(bounds.outWidth.toLong() * bounds.outHeight <= maxPixels) {
            "The picture exceeds the image decode limit. Increase the expert limit or resize explicitly; no pixels were silently dropped."
        }
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: error("That picture could not be decoded.")
        try {
            // The legacy encoder switches to lossless WebP at quality 100 from
            // Android 10 onward; provenance must describe the actual encoding.
            val lossy = target == Representations.JPEG ||
                (target == Representations.WEBP && (Build.VERSION.SDK_INT < 29 || quality < 100))
            val changes = buildList {
                add(Change(ChangeKind.PRESERVED, "color channels and pixel dimensions"))
                add(Change(ChangeKind.DISCARDED, "original container metadata; IO Matrix history is reattached where supported"))
                if (lossy) {
                    add(Change(ChangeKind.DISCARDED, "fine detail from lossy image encoding"))
                }
                if (bitmap.hasAlpha()) {
                    add(Change(if (target == Representations.JPEG) ChangeKind.DISCARDED else ChangeKind.PRESERVED,
                        "alpha channel (transparency)"))
                }
            }
            val out = ByteArrayOutputStream()
            check(bitmap.compress(format, if (target == Representations.PNG) 100 else quality, out)) { "Image encoding failed." }
            return Result(out.toByteArray(), changes, mapOf(
                "as" to target.id, "width" to bitmap.width.toString(), "height" to bitmap.height.toString(),
                "quality" to if (lossy) quality.toString() else "lossless",
                "pixel_format" to "ARGB_8888"
            ))
        } finally {
            bitmap.recycle()
        }
    }
}
