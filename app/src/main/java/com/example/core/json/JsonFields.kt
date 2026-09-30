package com.example.core.json

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader

/** Validate syntax and decoded field uniqueness before a DOM parser can discard duplicates. */
object JsonFields {
    fun check(raw: String, maxLength: Int = 65_536, maxDepth: Int = 12) {
        require(raw.length in 2..maxLength) { "JSON document is empty or too large." }
        require(maxDepth in 1..32) { "Invalid JSON depth budget." }
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            readValue(reader, 0, maxDepth)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected trailing JSON content." }
        }
    }

    private fun readValue(reader: JsonReader, depth: Int, maxDepth: Int) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                require(depth < maxDepth) { "JSON document is too deeply nested." }
                reader.beginObject()
                val names = HashSet<String>()
                while (reader.hasNext()) {
                    require(names.add(reader.nextName())) { "Duplicate JSON field." }
                    readValue(reader, depth + 1, maxDepth)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                require(depth < maxDepth) { "JSON document is too deeply nested." }
                reader.beginArray()
                while (reader.hasNext()) readValue(reader, depth + 1, maxDepth)
                reader.endArray()
            }
            JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> error("Expected a JSON value.")
        }
    }
}
