package com.example.core.speech

/**
 * The text side of reading aloud: what to say, cut how, and where to pick up again.
 *
 * Speech engines take a few thousand characters at a time and cannot pause, only
 * stop. So long text is cut at sentence ends (a cut mid-word is audible), every piece
 * remembers where it starts in the whole, and resuming means starting again at the
 * beginning of the word that was being spoken. All pure, so the cutting can be tested.
 */
object SpeechText {

    /** One piece handed to the engine, and where it starts in the whole text. */
    data class Chunk(val start: Int, val text: String)

    private fun isSentenceEnd(c: Char) = c == '.' || c == '!' || c == '?' || c == '…' || c == '\n'

    /** Cuts [text] into pieces of at most [max] characters, at sentence ends where possible. */
    fun chunks(text: String, max: Int = 3000): List<Chunk> {
        val limit = max.coerceAtLeast(20)
        val out = mutableListOf<Chunk>()
        var start = 0
        while (start < text.length) {
            // Leading whitespace is not worth an utterance of its own.
            while (start < text.length && text[start].isWhitespace()) start++
            if (start >= text.length) break
            if (text.length - start <= limit) {
                out += Chunk(start, text.substring(start))
                break
            }
            val window = text.substring(start, start + limit)
            var cut = window.indexOfLast { isSentenceEnd(it) } + 1
            if (cut <= limit / 4) cut = window.lastIndexOf(' ') + 1
            if (cut <= 0) cut = limit
            out += Chunk(start, window.substring(0, cut))
            start += cut
        }
        return out.filter { it.text.isNotBlank() }
    }

    /** The start of the word containing [offset], so a resume never begins mid-word. */
    fun wordStart(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return i
    }

    /** The last sentence before the cursor — "read back what I just wrote". */
    fun lastSentence(before: String): String {
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) return ""
        // Skip the sentence end the text finishes with, then find the one before it.
        var end = trimmed.length
        while (end > 0 && isSentenceEnd(trimmed[end - 1])) end--
        var i = end
        while (i > 0 && !isSentenceEnd(trimmed[i - 1])) i--
        return trimmed.substring(i).trim()
    }

    /** The word just finished — for reading words back as they are typed. */
    fun lastWord(before: String): String =
        before.trimEnd().takeLastWhile { !it.isWhitespace() }.trim { !it.isLetterOrDigit() }

    /** Lines not read yet, in order — reading a page while it scrolls under us. */
    fun unread(lines: List<String>, seen: MutableSet<String>): List<String> =
        lines.map { it.trim() }.filter { it.isNotEmpty() && seen.add(it) }

    /** How typing is read back, if at all. */
    enum class Echo {
        OFF, CHARACTERS, WORDS, SENTENCES;

        companion object {
            fun parse(raw: String?): Echo = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OFF
        }
    }
}
