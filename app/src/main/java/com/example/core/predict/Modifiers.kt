package com.example.core.predict

import kotlin.math.exp
import kotlin.math.ln

/**
 * Where a piece of text came from, which decides how much it is worth believing.
 *
 * A field rather than a note, because the difference is load-bearing: a correction
 * rule learned from dictation must not fire on a word somebody typed on purpose. The
 * same string means different things depending on how it arrived.
 */
enum class Provenance {
    /** Picked out of a list of alternatives. The only true label this app ever gets. */
    CHOSEN,

    /** Typed, with touches the hit model was confident about. */
    TYPED_CONFIDENT,

    /** Typed, but at least one touch landed where two keys disagree. */
    TYPED_UNCERTAIN,

    /** A recogniser's guess at what was said. */
    DICTATED,

    /** Put there by a completion the user accepted. */
    COMPLETED,

    /** Put there by a correction the user has not objected to — which is not consent. */
    AUTOCORRECTED,

    /** Pasted, imported, or otherwise not produced here. */
    EXTERNAL;

    /**
     * How far this may be trusted as evidence about what the user meant, 0..1.
     *
     * [CHOSEN] is 1 because it is the one case where the user said so outright.
     * [AUTOCORRECTED] is low on purpose: text the keyboard put there and the user did
     * not delete is weak evidence at best, and learning from it at face value is how a
     * model ends up training on its own output.
     */
    val weight: Float
        get() = when (this) {
            CHOSEN -> 1.0f
            TYPED_CONFIDENT -> 0.9f
            TYPED_UNCERTAIN -> 0.5f
            DICTATED -> 0.6f
            COMPLETED -> 0.7f
            AUTOCORRECTED -> 0.2f
            EXTERNAL -> 0.3f
        }
}

/**
 * What a modifier is allowed to do, which is not the same as how strong it is.
 *
 * The distinction exists because of one specific failure. A profile that has learned
 * somebody writes abusively is useful for *not fighting them* when they clearly typed
 * an insult. It must never be able to *produce* one over weak touch evidence — the
 * difference between a keyboard that stops correcting you and a keyboard that makes
 * you insult the people who just helped you.
 *
 * So power is declared, and the registry enforces it.
 */
enum class Power {
    /** May introduce a candidate nothing else proposed. */
    GENERATIVE,

    /** May only raise a candidate the user's own input already supports. */
    PROTECTIVE,

    /** May only lower. Cannot introduce, cannot raise. */
    SUPPRESSIVE
}

/**
 * How a modifier acts on a score. Offsets commute; the others do not.
 *
 * Ordering matters and is fixed: rewrites, then offsets in any order, then floors and
 * vetoes. An offset computed on the pre-rewrite form of a word is simply wrong, and a
 * veto that anything can outbid is not a veto.
 */
enum class Kind {
    /** Additive in log space, which is multiplicative in probability space. */
    OFFSET,

    /** Cannot fall below this, whatever the offsets said. */
    FLOOR,

    /** Removed outright. */
    VETO,

    /** Changes the candidate itself rather than its score. */
    REWRITE
}

/** One candidate, with everything known about why it is a candidate. */
data class Candidate(
    val text: String,
    /** Log-probability. Additive, so modifiers simply sum onto it. */
    val logP: Double = 0.0,
    /** True when the user's own input supports this, as opposed to something proposing it. */
    val supported: Boolean = false,
    val provenance: Provenance = Provenance.TYPED_CONFIDENT,
    /** Why it scores what it scores, in the order the reasons were applied. */
    val reasons: List<String> = emptyList(),
    val vetoed: Boolean = false
) {
    val probability: Double get() = exp(logP)
}

/**
 * One contribution to what a candidate is worth.
 *
 * The set of these is data; the way they combine is not. That split is the whole
 * design: adding a way of knowing something about the user means registering a
 * modifier, never editing the arithmetic.
 */
interface Modifier {
    val id: String
    val label: String
    val kind: Kind
    val power: Power

    /** Weight applied to whatever this returns. Learned, eventually; settable now. */
    val weight: Double get() = 1.0

    /**
     * The log-odds this modifier contributes for [candidate], or null for no opinion.
     *
     * No opinion and zero are different: zero is a statement that this candidate is
     * exactly as likely as the modifier's baseline, and null is silence.
     */
    fun score(candidate: Candidate, context: PredictContext): Double?

    /** Candidates this modifier proposes. Only ever called on [Power.GENERATIVE] ones. */
    fun propose(context: PredictContext): List<Candidate> = emptyList()
}

/** Everything a modifier is allowed to look at. */
data class PredictContext(
    /** Text before the cursor, nearest last. */
    val before: String = "",
    val after: String = "",
    /** The word being replaced, when this is a correction rather than a completion. */
    val word: String = "",
    val locale: String = "",
    /** Which app, so a profile can differ between a terminal and a chat. */
    val packageName: String = "",
    /** Where the text under consideration came from. */
    val provenance: Provenance = Provenance.TYPED_CONFIDENT,
    /** Free-form facts contributed by whatever knows them. */
    val facts: Map<String, String> = emptyMap()
)

/**
 * Combining modifiers, which is the part that is not data.
 *
 * Log-linear: every offset is summed, and that is multiplication in probability
 * space — a prior times a likelihood, which is what these are. The arithmetic is
 * hardcoded because it follows from what probability is, not from anybody's
 * preference. Everything it operates on is registered.
 */
object ModifierStack {

    fun combine(
        candidates: List<Candidate>,
        modifiers: List<Modifier>,
        context: PredictContext
    ): List<Candidate> {
        // Rewrites first: an offset computed on the pre-rewrite spelling is wrong.
        var working = candidates
        modifiers.filter { it.kind == Kind.REWRITE }.forEach { modifier ->
            working = working.map { candidate ->
                val rewritten = modifier.propose(context.copy(word = candidate.text))
                    .firstOrNull() ?: return@map candidate
                candidate.copy(
                    text = rewritten.text,
                    reasons = candidate.reasons + modifier.label
                )
            }
        }

        // Anything generative may add; anything else may not, however sure it is.
        val proposed = modifiers
            .filter { it.power == Power.GENERATIVE }
            .flatMap { it.propose(context) }
        val known = working.map { it.text }.toSet()
        working = working + proposed.filterNot { it.text in known }

        // Offsets, in any order, because addition commutes.
        val offsets = modifiers.filter { it.kind == Kind.OFFSET }
        working = working.map { candidate ->
            var logP = candidate.logP
            val reasons = candidate.reasons.toMutableList()
            offsets.forEach { modifier ->
                // A protective modifier may raise only what the user's own input
                // already supports. Without this it is a generative one with a
                // reassuring name.
                if (modifier.power == Power.PROTECTIVE && !candidate.supported) return@forEach
                val contribution = modifier.score(candidate, context) ?: return@forEach
                val effective = when (modifier.power) {
                    Power.SUPPRESSIVE -> minOf(0.0, contribution)
                    else -> contribution
                }
                if (effective == 0.0) return@forEach
                logP += modifier.weight * effective
                reasons += "${modifier.label}: ${format(effective)}"
            }
            candidate.copy(logP = logP, reasons = reasons)
        }

        // Floors, then vetoes. A veto anything can outbid is not a veto.
        modifiers.filter { it.kind == Kind.FLOOR }.forEach { modifier ->
            working = working.map { candidate ->
                val floor = modifier.score(candidate, context) ?: return@map candidate
                if (candidate.logP < floor) {
                    candidate.copy(logP = floor, reasons = candidate.reasons + modifier.label)
                } else {
                    candidate
                }
            }
        }
        modifiers.filter { it.kind == Kind.VETO }.forEach { modifier ->
            working = working.map { candidate ->
                if (modifier.score(candidate, context) != null) {
                    candidate.copy(vetoed = true, reasons = candidate.reasons + modifier.label)
                } else {
                    candidate
                }
            }
        }

        return working.filterNot { it.vetoed }.sortedByDescending { it.logP }
    }

    /** Scores as probabilities that sum to one, for anything that has to show a number. */
    fun normalised(candidates: List<Candidate>): List<Pair<Candidate, Double>> {
        if (candidates.isEmpty()) return emptyList()
        val top = candidates.maxOf { it.logP }
        val weights = candidates.map { exp(it.logP - top) }
        val total = weights.sum()
        if (total <= 0.0) return candidates.map { it to 0.0 }
        return candidates.mapIndexed { index, candidate -> candidate to weights[index] / total }
    }

    /** Log-odds from counts, which is what a learned correction rule actually is. */
    fun logOdds(corrections: Int, keeps: Int, prior: Double = 1.0): Double {
        val a = corrections + prior
        val b = keeps + prior
        return ln(a / b)
    }

    private fun format(value: Double): String =
        if (value >= 0) "+%.2f".format(value) else "%.2f".format(value)
}
