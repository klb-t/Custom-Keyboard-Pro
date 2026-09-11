package com.example.core.text

import java.text.BreakIterator

/**
 * Text manipulation that is correct for real text rather than for ASCII.
 *
 * The keyboard this replaced deleted one Java `char` per backspace. That splits
 * surrogate pairs, so one press of backspace turned an emoji into half of an emoji,
 * and it strips one combining mark at a time off an accented letter. Everything here
 * works in grapheme clusters instead, via [BreakIterator].
 */
object TextOps {

    /** Length in `char`s of the last grapheme cluster of [text], or 0 if empty. */
    fun lastGraphemeLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val s = text.toString()
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        val end = s.length
        val start = it.preceding(end)
        return if (start == BreakIterator.DONE) end else end - start
    }

    /** Length in `char`s of the first grapheme cluster of [text]. */
    fun firstGraphemeLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val s = text.toString()
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        it.first()
        val next = it.next()
        return if (next == BreakIterator.DONE) s.length else next
    }

    /**
     * How many characters to remove to delete the word before the cursor.
     *
     * Deletes any run of trailing whitespace, then the word itself. Matches what
     * Ctrl+Backspace does in a desktop editor rather than stopping at the space.
     */
    fun backwardWordLength(before: CharSequence): Int {
        if (before.isEmpty()) return 0
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        return (before.length - i).coerceAtLeast(1)
    }

    fun forwardWordLength(after: CharSequence): Int {
        if (after.isEmpty()) return 0
        var i = 0
        while (i < after.length && after[i].isWhitespace()) i++
        while (i < after.length && !after[i].isWhitespace()) i++
        return i.coerceAtLeast(1)
    }

    /** Characters back to the start of the current line (excluding the newline). */
    fun toLineStartLength(before: CharSequence): Int {
        val idx = before.lastIndexOf('\n')
        return if (idx < 0) before.length else before.length - idx - 1
    }

    fun toLineEndLength(after: CharSequence): Int {
        val idx = after.indexOf('\n')
        return if (idx < 0) after.length else idx
    }

    /** The word immediately before the cursor, used for suggestions and learning. */
    fun currentWord(before: CharSequence): String {
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        return before.substring(i, before.length)
    }

    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '-' || c == '_'

    /** Splits a body of text into learnable words. */
    fun words(text: CharSequence): List<String> =
        Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'-]*").findAll(text).map { it.value }.toList()

    // -----------------------------------------------------------------------
    // Auto-capitalisation
    // -----------------------------------------------------------------------

    /**
     * Whether the next letter should be capitalised, given the text before the cursor.
     *
     * True at the start of the field and after sentence-ending punctuation followed by
     * whitespace. Deliberately conservative: it does not capitalise after every
     * newline-free full stop inside an abbreviation like "e.g. ", because guessing
     * wrong there is more annoying than missing a capital.
     */
    fun shouldCapitalise(before: CharSequence): Boolean {
        if (before.isEmpty()) return true
        var i = before.length
        var sawSpace = false
        while (i > 0 && before[i - 1].isWhitespace()) {
            if (before[i - 1] == '\n') return true
            sawSpace = true
            i--
        }
        if (i == 0) return true
        if (!sawSpace) return false
        val prev = before[i - 1]
        if (prev !in SENTENCE_END) return false
        // "e.g. " — a single letter before the dot is almost always an abbreviation.
        if (prev == '.' && i >= 2 && before[i - 2].isLetter()) {
            val twoBack = if (i >= 3) before[i - 3] else ' '
            if (!twoBack.isLetter()) return false
        }
        return true
    }

    private val SENTENCE_END = charArrayOf('.', '!', '?', '…')

    /** Punctuation after which a space is conventional. */
    fun wantsTrailingSpace(c: Char): Boolean = c in ",.;:!?"

    // -----------------------------------------------------------------------
    // Smart punctuation
    // -----------------------------------------------------------------------

    /** Directional quote for a straight quote, chosen from what precedes it. */
    fun smartQuote(straight: Char, before: CharSequence): String {
        val opening = before.isEmpty() || before.last().isWhitespace() ||
            before.last() in "([{‘“"
        return when (straight) {
            '"' -> if (opening) "“" else "”"
            '\'' -> if (opening) "‘" else "’"
            else -> straight.toString()
        }
    }

    /**
     * Two spaces in a row become ". " when the preceding character can end a sentence.
     * Returns the replacement, or null to leave the second space alone.
     */
    fun doubleSpaceReplacement(before: CharSequence): String? {
        if (before.length < 2) return null
        if (before.last() != ' ') return null
        val prev = before[before.length - 2]
        if (!prev.isLetterOrDigit() && prev !in ")]}\"'") return null
        return ". "
    }

    // -----------------------------------------------------------------------
    // Dead keys and compose sequences
    // -----------------------------------------------------------------------

    /**
     * Applies a combining mark to a base letter and returns the precomposed form
     * when one exists, so `´` + `e` gives a single `é` rather than `e` plus a mark.
     */
    fun applyDeadKey(combining: String, base: String): String {
        if (base.isEmpty()) return combining
        val composed = java.text.Normalizer.normalize(base + combining, java.text.Normalizer.Form.NFC)
        // Normalisation leaves the mark standing when there is no precomposed glyph.
        return composed
    }

    /** Dead keys most Latin users expect, as display glyph to combining mark. */
    val DEAD_KEYS: List<Pair<String, String>> = listOf(
        "´" to "́",   // acute
        "`" to "̀",   // grave
        "^" to "̂",   // circumflex
        "¨" to "̈",   // diaeresis
        "~" to "̃",   // tilde
        "ˇ" to "̌",   // caron
        "˘" to "̆",   // breve
        "˚" to "̊",   // ring
        "¸" to "̧",   // cedilla
        "˛" to "̨",   // ogonek — ą, ę
        "¯" to "̄",   // macron
        "˙" to "̇",   // dot above — ż
        "/" to "̸"    // stroke — ł is special-cased below
    )

    /** Stroke has no combining form for most letters; map the common ones directly. */
    private val STROKE = mapOf(
        "l" to "ł", "L" to "Ł", "o" to "ø", "O" to "Ø",
        "d" to "đ", "D" to "Đ", "g" to "ǥ", "b" to "ƀ"
    )

    fun applyStroke(base: String): String = STROKE[base] ?: applyDeadKey("̸", base)

    /** Parses a hex code point, tolerating `U+` and `\u` prefixes. */
    fun codePointFromHex(raw: String): String? {
        val cleaned = raw.trim().removePrefix("U+").removePrefix("u+")
            .removePrefix("\\u").removePrefix("0x").removePrefix("0X")
        val value = cleaned.toIntOrNull(16) ?: return null
        if (value !in 0..0x10FFFF) return null
        return try {
            String(Character.toChars(value))
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
