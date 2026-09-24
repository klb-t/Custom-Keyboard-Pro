package com.example.core.text

/**
 * Where somebody pressing the arrow keys is trying to get to.
 *
 * The case it was built for: a word is finished, a typo in it is noticed, and the left
 * arrow is tapped to get back to it — two presses too many, then one too few. When
 * there is exactly one word nearby that the dictionary does not know, and the presses
 * were heading towards it, that word is almost certainly the destination; and the
 * dictionary's nearest real word says *where inside it* the slip is.
 *
 * How hard it pulls is the user's choice, from not at all to on every press, because
 * the same guess that saves four taps for one person moves the cursor out from under
 * another. Everything here is pure: the dictionary is asked by the caller, and this
 * only decides.
 */
object CursorMagnet {

    enum class Pull {
        /** Arrows move one character, always. */
        OFF,

        /** Offer the jump in the strip; nothing moves unless it is tapped. */
        OFFER,

        /** When the presses stop near a lone suspect, finish the trip. */
        ON_PAUSE,

        /** The first press towards a lone suspect goes straight there. */
        STRIDE
    }

    /** A run of letters in the text, by position. */
    data class Span(val start: Int, val end: Int, val word: String)

    /** A word the dictionary did not know, and where in it the cursor should land. */
    data class Suspect(val start: Int, val end: Int, val word: String, val landing: Int)

    /** Every word in [text], with letters, digits' neighbours and apostrophes kept together. */
    fun words(text: CharSequence): List<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        while (i < text.length) {
            if (!text[i].isLetter()) {
                i++
                continue
            }
            val start = i
            while (i < text.length && (text[i].isLetter() || (text[i] == '\'' && i + 1 < text.length && text[i + 1].isLetter()))) i++
            out += Span(start, i, text.substring(start, i))
        }
        return out
    }

    /**
     * Whether a word is worth suspecting at all, before the dictionary is asked.
     *
     * Capitalised words are names far more often than typos, so they are left alone
     * unless they start a sentence; all-capital words are acronyms; single letters are
     * words in half the languages this keyboard types.
     */
    fun worthChecking(span: Span, text: CharSequence): Boolean {
        val w = span.word
        if (w.length < 2) return false
        if (w.length > 1 && w.all { it.isUpperCase() }) return false
        if (w.first().isUpperCase()) {
            var j = span.start - 1
            while (j >= 0 && text[j].isWhitespace()) j--
            val sentenceStart = j < 0 || text[j] == '.' || text[j] == '!' || text[j] == '?' || text[j] == '\n'
            if (!sentenceStart) return false
        }
        return true
    }

    /**
     * Where in [word] the slip is, as an offset into it, given the real word it was
     * probably meant to be.
     *
     * After the first character that differs, so one backspace removes it — except
     * when a letter is missing, where the cursor goes to the gap so it can be typed,
     * and when two letters are swapped, where it goes after the pair so both can be
     * retyped. No correction to compare with: the end of the word, which is where the
     * user would have started from anyway.
     */
    fun landing(word: String, correction: String?): Int {
        if (correction.isNullOrEmpty()) return word.length
        val a = word.lowercase()
        val b = correction.lowercase()
        var p = 0
        while (p < a.length && p < b.length && a[p] == b[p]) p++
        if (p >= a.length) return word.length
        val missing = b.length > a.length && p + 1 <= b.length && a.substring(p) == b.substring(p + 1)
        if (missing) return p
        val swapped = p + 1 < a.length && p + 1 < b.length && a[p] == b[p + 1] && a[p + 1] == b[p]
        if (swapped) return p + 2
        return (p + 1).coerceAtMost(word.length)
    }

    fun suspect(span: Span, correction: String?): Suspect =
        Suspect(span.start, span.end, span.word, span.start + landing(span.word, correction))

    /**
     * The position a run of presses is aiming for, or null to leave the cursor alone.
     *
     * [runStart] is where the run began and [cursor] where it is now, both in the same
     * coordinates as the suspects; [direction] is -1 for left, +1 for right. Only
     * suspects the run has been travelling towards count, and only when exactly one is
     * within [reach] characters — two candidates is a guess, and a guess that moves
     * the cursor is worse than none.
     */
    fun aim(suspects: List<Suspect>, runStart: Int, cursor: Int, direction: Int, reach: Int): Int? {
        val ahead = suspects.filter {
            if (direction < 0) it.landing < runStart else it.landing > runStart
        }
        // Already inside the word: close enough to finish the trip to the slip, and
        // no other word is in question.
        ahead.firstOrNull { cursor in it.start..it.end }?.let { inside ->
            return inside.landing.takeIf { it != cursor }
        }
        val near = ahead.filter { kotlin.math.abs(it.landing - cursor) <= reach }
        if (near.size != 1) return null
        return near.single().landing.takeIf { it != cursor }
    }

    /**
     * For [Pull.STRIDE]: the first press heads straight for the nearest suspect in
     * its direction, if it is within [stride] characters and nothing else is as close.
     */
    fun stride(suspects: List<Suspect>, cursor: Int, direction: Int, stride: Int): Int? {
        val ahead = suspects
            .filter { if (direction < 0) it.landing < cursor else it.landing > cursor }
            .filter { kotlin.math.abs(it.landing - cursor) <= stride }
            .sortedBy { kotlin.math.abs(it.landing - cursor) }
        return ahead.firstOrNull()?.landing
    }
}
