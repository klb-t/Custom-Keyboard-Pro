package com.example.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.example.core.matrix.ChangeKind
import com.example.core.matrix.Representations
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageReencoderTest {
    private fun image(format: Bitmap.CompressFormat, transparent: Boolean = false): ByteArray {
        val colors = IntArray(64 * 32) { index ->
            if (index % 64 < 32) (if (transparent) Color.argb(96, 255, 0, 0) else Color.RED) else Color.BLUE
        }
        val bitmap = Bitmap.createBitmap(colors, 64, 32, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(format, 100, it); bitmap.recycle() }.toByteArray()
    }

    private fun decode(bytes: ByteArray) = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

    @Test fun `JPEG to PNG preserves decoded colors and dimensions`() {
        val source = image(Bitmap.CompressFormat.JPEG)
        val result = ImageReencoder.convert(source, Representations.PNG, 92, 100_000)
        val before = decode(source)
        val after = decode(result.bytes)
        try {
            assertEquals(before.width, after.width)
            assertEquals(before.height, after.height)
            for (x in listOf(8, 48)) assertEquals(before.getPixel(x, 16), after.getPixel(x, 16))
            assertTrue(Color.red(after.getPixel(8, 16)) > Color.blue(after.getPixel(8, 16)) + 100)
        } finally { before.recycle(); after.recycle() }
    }

    @Test fun `PNG alpha stays present in PNG but JPEG records its loss`() {
        val source = image(Bitmap.CompressFormat.PNG, transparent = true)
        val png = ImageReencoder.convert(source, Representations.PNG, 92, 100_000)
        val bitmap = decode(png.bytes)
        try { assertEquals(96, Color.alpha(bitmap.getPixel(8, 16))) } finally { bitmap.recycle() }
        val jpeg = ImageReencoder.convert(source, Representations.JPEG, 92, 100_000)
        assertTrue(jpeg.changes.any { it.kind == ChangeKind.DISCARDED && "transparency" in it.what })
        assertTrue(jpeg.changes.any { it.kind == ChangeKind.DISCARDED && "fine detail" in it.what })
    }

    @Test fun `lossless WebP records no lossy encoding and retains transparency`() {
        val result = ImageReencoder.convert(image(Bitmap.CompressFormat.PNG, transparent = true),
            Representations.WEBP, 100, 100_000)
        assertEquals("lossless", result.parameters["quality"])
        assertFalse(result.changes.any { it.kind == ChangeKind.DISCARDED && "fine detail" in it.what })
        val bitmap = decode(result.bytes)
        try { assertEquals(96, Color.alpha(bitmap.getPixel(8, 16))) } finally { bitmap.recycle() }
    }

    @Test fun `oversized pictures are refused instead of silently resized`() {
        try {
            ImageReencoder.convert(image(Bitmap.CompressFormat.PNG), Representations.PNG, 92, 100)
            fail("A 2048-pixel picture must not pass a 100-pixel limit")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("decode limit"))
        }
    }
}
