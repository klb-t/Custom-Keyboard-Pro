package com.example.core

import com.example.core.predict.Candidate
import com.example.core.predict.Confidence
import com.example.core.predict.Cost
import com.example.core.predict.Decision
import com.example.core.predict.Kind
import com.example.core.predict.Modifier
import com.example.core.predict.ModifierStack
import com.example.core.predict.Power
import com.example.core.predict.PredictContext
import com.example.core.predict.Priced
import com.example.core.predict.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/**
 * The arithmetic that decides what the keyboard puts in somebody's mouth.
 *
 * Tested harder than anything else here, because it is the only part where being
 * subtly wrong is worse than being obviously broken: a stack that mostly works
 * produces a keyboard that is right nine times out of ten and humiliating the tenth.
 */
class PredictTest {

    private fun modifier(
        id: String,
        kind: Kind = Kind.OFFSET,
        power: Power = Power.GENERATIVE,
        weight: Double = 1.0,
        proposes: List<Candidate> = emptyList(),
        scoring: (Candidate) -> Double? = { null }
    ) = object : Modifier {
        override val id = id
        override val label = id
        override val kind = kind
        override val power = power
        override val weight = weight
        override fun score(candidate: Candidate, context: PredictContext) = scoring(candidate)
        override fun propose(context: PredictContext) = proposes
    }

    private val ctx = PredictContext()

    // -----------------------------------------------------------------------
    // Combination
    // -----------------------------------------------------------------------

    @Test
    fun `offsets add in log space and therefore multiply in probability space`() {
        val start = Candidate("x", logP = ln(0.1))
        val result = ModifierStack.combine(
            listOf(start),
            listOf(
                modifier("a", scoring = { ln(2.0) }),
                modifier("b", scoring = { ln(3.0) })
            ),
            ctx
        ).single()
        // 0.1 × 2 × 3
        assertEquals(0.6, result.probability, 0.0001)
    }

    @Test
    fun `offsets commute`() {
        val a = modifier("a", scoring = { 0.7 })
        val b = modifier("b", scoring = { -0.3 })
        val one = ModifierStack.combine(listOf(Candidate("x")), listOf(a, b), ctx).single()
        val two = ModifierStack.combine(listOf(Candidate("x")), listOf(b, a), ctx).single()
        assertEquals(one.logP, two.logP, 1e-12)
    }

    @Test
    fun `no opinion is not the same as zero`() {
        val silent = modifier("silent", scoring = { null })
        val result = ModifierStack.combine(listOf(Candidate("x", logP = -1.0)), listOf(silent), ctx)
        assertEquals(-1.0, result.single().logP, 1e-12)
        // Silence leaves no trace; a zero opinion would have.
        assertTrue(result.single().reasons.isEmpty())
    }

    @Test
    fun `weight scales a contribution`() {
        val result = ModifierStack.combine(
            listOf(Candidate("x")),
            listOf(modifier("a", weight = 0.5, scoring = { 2.0 })),
            ctx
        ).single()
        assertEquals(1.0, result.logP, 1e-12)
    }

    // -----------------------------------------------------------------------
    // Power
    // -----------------------------------------------------------------------

    @Test
    fun `a protective modifier cannot raise what the user did not produce`() {
        // The rule that stops a profile learned from somebody's worst moments from
        // putting words in their mouth.
        val profile = modifier("profile", power = Power.PROTECTIVE, scoring = { 5.0 })
        val unsupported = Candidate("insult", supported = false)
        val supported = Candidate("insult", supported = true)

        assertEquals(
            0.0,
            ModifierStack.combine(listOf(unsupported), listOf(profile), ctx).single().logP,
            1e-12
        )
        assertEquals(
            5.0,
            ModifierStack.combine(listOf(supported), listOf(profile), ctx).single().logP,
            1e-12
        )
    }

    @Test
    fun `a protective modifier cannot introduce a candidate at all`() {
        val profile = modifier(
            "profile", power = Power.PROTECTIVE,
            proposes = listOf(Candidate("invented"))
        )
        val result = ModifierStack.combine(listOf(Candidate("typed")), listOf(profile), ctx)
        assertEquals(listOf("typed"), result.map { it.text })
    }

    @Test
    fun `a suppressive modifier can only lower`() {
        val suppress = modifier("suppress", power = Power.SUPPRESSIVE, scoring = { 3.0 })
        assertEquals(
            0.0,
            ModifierStack.combine(listOf(Candidate("x", supported = true)), listOf(suppress), ctx)
                .single().logP,
            1e-12
        )
        val lower = modifier("lower", power = Power.SUPPRESSIVE, scoring = { -3.0 })
        assertEquals(
            -3.0,
            ModifierStack.combine(listOf(Candidate("x", supported = true)), listOf(lower), ctx)
                .single().logP,
            1e-12
        )
    }

    @Test
    fun `a generative modifier may add something nobody typed`() {
        val result = ModifierStack.combine(
            listOf(Candidate("typed")),
            listOf(modifier("gen", proposes = listOf(Candidate("offered", logP = 1.0)))),
            ctx
        )
        assertEquals(setOf("typed", "offered"), result.map { it.text }.toSet())
    }

    // -----------------------------------------------------------------------
    // Ordering
    // -----------------------------------------------------------------------

    @Test
    fun `a rewrite happens before the offsets that judge it`() {
        // Scoring the pre-rewrite spelling and then rewriting would award the points
        // to a word that is no longer there.
        val rewrite = modifier(
            "rewrite", kind = Kind.REWRITE,
            proposes = listOf(Candidate("±"))
        )
        val reward = modifier("reward", scoring = { if (it.text == "±") 2.0 else 0.0 })
        val result = ModifierStack.combine(listOf(Candidate("+-")), listOf(reward, rewrite), ctx)
            .single()
        assertEquals("±", result.text)
        assertEquals(2.0, result.logP, 1e-12)
    }

    @Test
    fun `a veto cannot be outbid`() {
        val huge = modifier("huge", scoring = { 100.0 })
        val veto = modifier("veto", kind = Kind.VETO, scoring = { if (it.text == "no") 1.0 else null })
        val result = ModifierStack.combine(
            listOf(Candidate("no"), Candidate("yes")),
            listOf(huge, veto),
            ctx
        )
        assertEquals(listOf("yes"), result.map { it.text })
    }

    @Test
    fun `a floor holds against everything below it`() {
        val sink = modifier("sink", scoring = { -10.0 })
        val floor = modifier("floor", kind = Kind.FLOOR, scoring = { -1.0 })
        assertEquals(
            -1.0,
            ModifierStack.combine(listOf(Candidate("x")), listOf(sink, floor), ctx).single().logP,
            1e-12
        )
    }

    @Test
    fun `a learned rule is log-odds of how often it was corrected`() {
        // Ten corrections and no keeps is a strong rule; five and five is barely one.
        assertTrue(ModifierStack.logOdds(corrections = 10, keeps = 0) > 2.0)
        assertEquals(0.0, ModifierStack.logOdds(corrections = 5, keeps = 5), 1e-12)
        assertTrue(ModifierStack.logOdds(corrections = 0, keeps = 10) < -2.0)
        // And it is defined with no data at all, rather than dividing by zero.
        assertEquals(0.0, ModifierStack.logOdds(0, 0), 1e-12)
    }

    // -----------------------------------------------------------------------
    // Deciding
    // -----------------------------------------------------------------------

    @Test
    fun `the likeliest word does not win when being wrong about it is ruinous`() {
        // The forum case. The insult is likelier by the numbers, because a profile
        // learned from this user's history says so. It still must not be emitted.
        val insult = Priced(Candidate("Dziwki", logP = ln(0.55), supported = false), Cost.SOCIAL)
        val thanks = Priced(Candidate("Dzięki", logP = ln(0.45), supported = true), Cost.ORDINARY)

        val ranked = ModifierStack.normalised(listOf(insult.candidate, thanks.candidate))
        assertEquals("Dziwki", ranked.first().first.text)

        val chosen = Decision.choose(listOf(insult, thanks))
        assertNotNull(chosen)
        assertEquals("Dzięki", chosen!!.candidate.text)
    }

    @Test
    fun `nothing is substituted across a difference in cost, at any confidence`() {
        val insult = Priced(Candidate("Dziwki", logP = ln(0.99)), Cost.SOCIAL)
        val thanks = Priced(Candidate("Dzięki", logP = ln(0.01)), Cost.ORDINARY)
        // Even at 99%: the alternatives differ in what they would cost, so the
        // keyboard flags the word instead of deciding for the user.
        assertFalse(Decision.shouldAutoApply(listOf(insult, thanks)))
    }

    @Test
    fun `an ordinary correction still applies when it is clear enough`() {
        val right = Priced(Candidate("keyboard", logP = ln(0.95)), Cost.ORDINARY)
        val wrong = Priced(Candidate("keybaord", logP = ln(0.05)), Cost.ORDINARY)
        assertTrue(Decision.shouldAutoApply(listOf(right, wrong)))
    }

    @Test
    fun `an ordinary correction waits when it is not clear enough`() {
        val a = Priced(Candidate("form", logP = ln(0.55)), Cost.ORDINARY)
        val b = Priced(Candidate("from", logP = ln(0.45)), Cost.ORDINARY)
        assertFalse(Decision.shouldAutoApply(listOf(a, b)))
    }

    @Test
    fun `confidence is a band, because a number nobody calibrated is a lie`() {
        assertEquals(Confidence.HIGH, Decision.band(0.9))
        assertEquals(Confidence.MEDIUM, Decision.band(0.6))
        assertEquals(Confidence.LOW, Decision.band(0.3))
    }

    @Test
    fun `normalised probabilities sum to one and survive extreme scores`() {
        val candidates = listOf(
            Candidate("a", logP = -1000.0),
            Candidate("b", logP = -1001.0)
        )
        val total = ModifierStack.normalised(candidates).sumOf { it.second }
        // Subtracting the maximum before exponentiating is what stops this underflowing
        // to zero and producing NaN for every suggestion on screen.
        assertEquals(1.0, total, 1e-9)
    }

    @Test
    fun `provenance ranks evidence, and a keyboard's own output ranks low`() {
        assertTrue(Provenance.CHOSEN.weight > Provenance.TYPED_CONFIDENT.weight)
        assertTrue(Provenance.TYPED_CONFIDENT.weight > Provenance.TYPED_UNCERTAIN.weight)
        // Text the keyboard put there and the user did not delete is not consent, and
        // learning from it at face value is how a model trains on its own output.
        assertTrue(Provenance.AUTOCORRECTED.weight < Provenance.TYPED_UNCERTAIN.weight)
    }
}
