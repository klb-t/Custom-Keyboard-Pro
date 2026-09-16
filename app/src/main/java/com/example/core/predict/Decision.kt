package com.example.core.predict

/**
 * How much it costs to be wrong about a particular word.
 *
 * Not every mistake costs the same, and picking the most probable candidate quietly
 * assumes they do. The case that settles it: somebody asks for help on a forum,
 * several people help, and they type a reply. The touches are poor — one lands
 * between two modifier keys, another at the far edge of the board. The likeliest
 * reading by probability alone might be an insult, because a profile learned from
 * their history says they write that way.
 *
 * Getting that wrong in one direction is a mild annoyance and a retype. Getting it
 * wrong in the other makes the keyboard insult the people who just helped them,
 * publicly, possibly before they notice. Those are not the same error and must not be
 * priced the same.
 */
enum class Cost {
    /** Wrong, and the user fixes it without thinking about it. */
    ORDINARY,

    /** Wrong in a way that changes what the sentence means. */
    MEANING,

    /** Wrong in a way that damages the user in front of other people. */
    SOCIAL,

    /** Wrong in a way that cannot be taken back — sent, committed, executed. */
    IRREVERSIBLE
}

/** A word and what it would cost to emit it when something else was meant. */
data class Priced(val candidate: Candidate, val cost: Cost)

/**
 * Deciding, as opposed to ranking.
 *
 * Ranking produces an order. Deciding produces an action, and the two differ whenever
 * the costs are uneven — which, for a keyboard, is most of the time that matters.
 *
 * Everything here is about the *correction* lane, where text the user already produced
 * gets changed, often without being noticed. The prediction lane needs none of it: a
 * suggestion the user must tap to accept can be as bold as it likes, because nothing
 * enters the text without a deliberate act. That asymmetry is the whole reason the two
 * lanes are separate.
 */
object Decision {

    /** Multiplier on the cost of emitting a candidate that turns out to be wrong. */
    fun penalty(cost: Cost): Double = when (cost) {
        Cost.ORDINARY -> 1.0
        Cost.MEANING -> 4.0
        Cost.SOCIAL -> 25.0
        Cost.IRREVERSIBLE -> 100.0
    }

    /**
     * The candidate with the lowest expected cost, rather than the highest probability.
     *
     * Reads as: for each thing I could emit, how bad is it on average given everything
     * else that might have been meant. A candidate that is slightly more likely but
     * ruinous when wrong loses to one that is slightly less likely and harmless.
     */
    fun choose(priced: List<Priced>): Priced? {
        if (priced.isEmpty()) return null
        val probabilities = ModifierStack.normalised(priced.map { it.candidate }).toMap()
        return priced.minByOrNull { option ->
            priced.sumOf { actual ->
                if (actual.candidate.text == option.candidate.text) {
                    0.0
                } else {
                    // Emitting `option` when `actual` was meant costs what `option`
                    // costs, weighted by how likely `actual` is.
                    (probabilities[actual.candidate] ?: 0.0) * penalty(option.cost)
                }
            }
        }
    }

    /**
     * Whether to change the text at all, or to flag it and let the user decide.
     *
     * The rule that falls out of the costs rather than being added on top: when the
     * alternatives differ in what they would cost, the keyboard does not get to pick.
     * It marks the word and offers them. Nothing is silently substituted across a cost
     * boundary, ever, at any confidence.
     */
    fun shouldAutoApply(priced: List<Priced>, minConfidence: Double = 0.80): Boolean {
        if (priced.size < 2) return priced.size == 1 && priced.first().cost == Cost.ORDINARY
        val costs = priced.map { it.cost }.distinct()
        if (costs.size > 1) return false
        if (costs.single() != Cost.ORDINARY) return false
        val ranked = ModifierStack.normalised(priced.map { it.candidate })
        return ranked.firstOrNull()?.second?.let { it >= minConfidence } ?: false
    }

    /**
     * How sure to say we are, in bands rather than in numbers.
     *
     * A percentage nobody has calibrated is a lie with a decimal point: "73%" attached
     * to something right 40% of the time is worse than no number at all, because it
     * looks like a measurement. Bands survive being uncalibrated; they only claim an
     * ordering, which is the thing these scores genuinely have.
     */
    fun band(probability: Double): Confidence = when {
        probability >= 0.85 -> Confidence.HIGH
        probability >= 0.55 -> Confidence.MEDIUM
        else -> Confidence.LOW
    }
}

enum class Confidence { HIGH, MEDIUM, LOW }
