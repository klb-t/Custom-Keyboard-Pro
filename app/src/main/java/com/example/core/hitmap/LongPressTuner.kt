package com.example.core.hitmap

/**
 * A long-press threshold that follows the hand using it.
 *
 * On a board where your language lives in the long-press strip, the threshold is how
 * letters are typed — too long and every "ę" is a wait, too short and a slow "e"
 * becomes one. The right number differs from person to person and from morning to
 * night, so it can be learned from three things the keyboard already sees:
 *
 *  - **How long plain taps are held.** The threshold is kept clear of nearly all of
 *    them, so an ordinary tap does not open the strip.
 *  - **An alternate deleted straight away.** The strip opened when it was not wanted:
 *    a little longer next time.
 *  - **A tap deleted and the same key long-pressed at once.** The strip was wanted and
 *    did not come in time: a little shorter — but never into the range where plain
 *    taps live, which would trade one annoyance for the other.
 *
 * Small steps, hard bounds, and no decision from a single sample of anything.
 */
class LongPressTuner(
    threshold: Long,
    private val min: Long = 90L,
    private val max: Long = 600L,
    private val step: Long = STEP,
    private val margin: Long = MARGIN
) {
    var threshold: Long = threshold.coerceIn(min, max)
        private set

    private val taps = ArrayDeque<Long>()

    /** A plain tap on a key that has a strip, held for [heldMs]. */
    fun tap(heldMs: Long): Long {
        if (heldMs <= 0 || heldMs > max * 3) return threshold
        taps.addLast(heldMs)
        while (taps.size > WINDOW) taps.removeFirst()
        guard()?.let { floor ->
            // Only upwards here, and gently: taps crowding the threshold is a warning,
            // not an emergency.
            if (floor > threshold) threshold = minOf(floor, threshold + step).coerceIn(min, max)
        }
        return threshold
    }

    /** The strip opened and what it typed was deleted at once. */
    fun accidental(): Long {
        threshold = (threshold + step).coerceIn(min, max)
        return threshold
    }

    /** A tap was deleted and the same key long-pressed straight after. */
    fun tooSlow(): Long {
        val floor = guard() ?: min
        threshold = maxOf(threshold - step, floor).coerceIn(min, max)
        return threshold
    }

    /**
     * How low the threshold may go without catching ordinary taps: nearly all of them
     * plus a margin. Null until there are enough taps to say.
     */
    fun guard(): Long? {
        if (taps.size < MIN_SAMPLES) return null
        val sorted = taps.sorted()
        val p90 = sorted[((sorted.size - 1) * 0.9).toInt()]
        return p90 + margin
    }

    companion object {
        const val STEP = 12L
        const val MARGIN = 30L
        const val WINDOW = 80
        const val MIN_SAMPLES = 20
    }
}
