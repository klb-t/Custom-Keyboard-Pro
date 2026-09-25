package com.example.core.convert

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Text kept to 7-bit ASCII by escaping everything else as JSON does (\uXXXX). */
object Ascii {
    fun escape(s: String): String = buildString(s.length) {
        s.forEach { c -> if (c.code in 0x20..0x7E) append(c) else append("\\u%04x".format(c.code)) }
    }
}

/**
 * Text chunks in PNG files: where IO Matrix writes a picture's history and the
 * layout it was drawn in, so reading it back is a fact rather than an assumption.
 * Viewers ignore them; every PNG reader keeps them intact or drops them whole.
 */
object Png {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun isPng(bytes: ByteArray): Boolean = bytes.size >= 8 && (0 until 8).all { bytes[it] == SIGNATURE[it] }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    /** Every tEXt chunk, keyword to text; empty for anything that is not a PNG. */
    fun texts(bytes: ByteArray): Map<String, String> {
        if (!isPng(bytes)) return emptyMap()
        val out = linkedMapOf<String, String>()
        var p = 8
        while (p + 12 <= bytes.size) {
            val len = u32(bytes, p)
            if (len < 0 || p + 12 + len > bytes.size) break
            val type = String(bytes, p + 4, 4, Charsets.US_ASCII)
            if (type == "tEXt") {
                val data = bytes.copyOfRange(p + 8, p + 8 + len.toInt())
                val zero = data.indexOf(0.toByte())
                if (zero > 0) out[String(data, 0, zero, Charsets.ISO_8859_1)] = String(data, zero + 1, data.size - zero - 1, Charsets.ISO_8859_1)
            }
            if (type == "IEND") break
            p += 12 + len.toInt()
        }
        return out
    }

    /** The PNG with a tEXt chunk added just before its end; the same bytes when it is not a PNG. */
    fun withText(bytes: ByteArray, keyword: String, text: String): ByteArray {
        if (!isPng(bytes)) return bytes
        var p = 8
        var end = -1
        while (p + 12 <= bytes.size) {
            val len = u32(bytes, p).toInt()
            if (len < 0 || p + 12 + len > bytes.size) break
            if (String(bytes, p + 4, 4, Charsets.US_ASCII) == "IEND") {
                end = p
                break
            }
            p += 12 + len
        }
        if (end < 0) return bytes
        val data = keyword.take(79).toByteArray(Charsets.ISO_8859_1) + 0.toByte() + Ascii.escape(text).toByteArray(Charsets.ISO_8859_1)
        val chunk = ByteArrayOutputStream(12 + data.size)
        fun be32(v: Long) = chunk.write(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
        be32(data.size.toLong())
        val typeAndData = "tEXt".toByteArray(Charsets.US_ASCII) + data
        chunk.write(typeAndData)
        be32(CRC32().apply { update(typeAndData) }.value)
        return bytes.copyOfRange(0, end) + chunk.toByteArray() + bytes.copyOfRange(end, bytes.size)
    }
}
