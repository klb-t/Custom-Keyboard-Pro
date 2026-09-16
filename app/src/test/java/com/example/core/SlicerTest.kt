package com.example.core

import com.example.core.predict.GenerationCut
import com.example.core.predict.Scope
import com.example.core.predict.Slicer
import com.example.core.predict.StopCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.ln

/**
 * Cutting one generation into the several things somebody might accept.
 *
 * All of this is pure, which is the point: the expensive half is the inference, and
 * none of it is needed to find out whether the cheap half cuts in the right places.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlicerTest {

    private val prose = " keyboard, which types what you meant. And then some more."

    @Test
    fun `one generation yields several offers, each a prefix of the last`() {
        val slices = Slicer.slices(prose)
        assertTrue(slices.size >= 3)
        // The whole reason for slicing rather than generating four times: they cannot
        // contradict each other, because each is a prefix of the same continuation.
        slices.zipWithNext().forEach { (shorter, longer) ->
            assertTrue(
                "'${longer.text}' does not extend '${shorter.text}'",
                longer.text.startsWith(shorter.text)
            )
        }
    }

    @Test
    fun `the leading space survives`() {
        // Dropping it produces "thekeyboard" every time somebody accepts one word.
        assertEquals(" keyboard", Slicer.cut(prose, Scope.WORD))
    }

    @Test
    fun `each scope cuts where it says it does`() {
        assertEquals(" keyboard", Slicer.cut(prose, Scope.WORD))
        assertEquals(" keyboard,", Slicer.cut(prose, Scope.CLAUSE))
        assertEquals(" keyboard, which types what you meant.", Slicer.cut(prose, Scope.SENTENCE))
        assertEquals(prose.trimEnd(), Slicer.cut(prose, Scope.ALL))
    }

    @Test
    fun `a paragraph ends at a blank line`() {
        val text = "first line\nstill first\n\nsecond paragraph"
        assertEquals("first line\nstill first", Slicer.cut(text, Scope.PARAGRAPH))
        assertEquals(text, Slicer.cut(text, Scope.ALL))
    }

    @Test
    fun `two scopes landing on the same text are one offer`() {
        // A one-word continuation has nothing further to say at sentence scope, and
        // showing the same string four times is worse than showing it once.
        val slices = Slicer.slices(" yes")
        assertEquals(1, slices.size)
        assertEquals(" yes", slices.single().text)
    }

    @Test
    fun `a continuation that opens with punctuation is not cut back to it`() {
        val text = ", and then we left."
        assertEquals(", and then we left.", Slicer.cut(text, Scope.SENTENCE))
        assertTrue(Slicer.cut(text, Scope.CLAUSE).length > 1)
    }

    @Test
    fun `nothing to cut is nothing, not a crash`() {
        Scope.entries.forEach {
            assertEquals("", Slicer.cut("", it))
            assertEquals("", Slicer.cut("   ", it))
        }
        assertTrue(Slicer.slices("").isEmpty())
    }

    @Test
    fun `a continuation with no terminator is offered whole`() {
        val text = " and it just keeps going"
        assertEquals(text.trimEnd(), Slicer.cut(text, Scope.SENTENCE))
        assertEquals(text.trimEnd(), Slicer.cut(text, Scope.CLAUSE))
    }

    // -----------------------------------------------------------------------
    // Balance, which is the stopping rule that matters for code
    // -----------------------------------------------------------------------

    @Test
    fun `balance is what closes a generated function`() {
        assertTrue(Slicer.isBalanced("fun x() { return listOf(1, 2) }"))
        assertFalse(Slicer.isBalanced("fun x() { return listOf(1, 2)"))
        assertFalse(Slicer.isBalanced("fun x() {"))
        assertTrue(Slicer.isBalanced(""))
    }

    @Test
    fun `a bracket inside a string does not count`() {
        // An apostrophe or a stray brace in a comment must not leave the generation
        // looking unbalanced forever.
        assertTrue(Slicer.isBalanced("""val s = "a ( b" """))
        assertTrue(Slicer.isBalanced("""val s = "it's fine" """))
        assertTrue(Slicer.isBalanced("""val s = "escaped \" quote" """))
    }

    @Test
    fun `a closer with nothing open is unbalanced, not ignored`() {
        assertFalse(Slicer.isBalanced("}"))
        assertFalse(Slicer.isBalanced("( ]"))
    }

    // -----------------------------------------------------------------------
    // Stopping
    // -----------------------------------------------------------------------

    @Test
    fun `a single uncertain token cuts when a floor is set`() {
        val cut = GenerationCut(StopCondition(tokenFloor = ln(0.1)), startedAt = 0L)
        assertTrue(cut.accept(" the", ln(0.9), now = 1))
        assertFalse(cut.accept(" Zbigniew", ln(0.01), now = 2))
        assertEquals(" the", cut.text)
        assertEquals("it stopped being sure", cut.stoppedBecause)
    }

    @Test
    fun `a surprise budget survives one bad token and still catches drift`() {
        // The non-obvious half: a name or a number in the middle of a confident run
        // should not cut a good continuation in two.
        val cut = GenerationCut(StopCondition(surpriseBudget = 6.0), startedAt = 0L)
        assertTrue(cut.accept(" the", ln(0.9), now = 1))
        assertTrue(cut.accept(" Zbigniew", ln(0.05), now = 2))
        assertTrue(cut.accept(" said", ln(0.8), now = 3))
        assertEquals(" the Zbigniew said", cut.text)
        // …and then keeps going until the generation as a whole has wandered.
        assertFalse(cut.accept(" xyzzy", ln(0.02), now = 4))
        assertEquals("it had drifted too far", cut.stoppedBecause)
    }

    @Test
    fun `waiting is a stopping condition`() {
        val cut = GenerationCut(StopCondition(maxMillis = 100L), startedAt = 0L)
        assertTrue(cut.accept("a", null, now = 50))
        assertFalse(cut.accept("b", null, now = 500))
        assertEquals("a", cut.text)
        assertEquals("you had been waiting", cut.stoppedBecause)
    }

    @Test
    fun `a stop string is cut out, not included`() {
        val cut = GenerationCut(StopCondition(stopStrings = listOf("\n\n")), startedAt = 0L)
        assertTrue(cut.accept("one", null, now = 1))
        assertFalse(cut.accept("\n\ntwo", null, now = 2))
        assertEquals("one", cut.text)
    }

    @Test
    fun `the hard cap fires even when nothing else would`() {
        // Every other condition can fail to trigger, so this one is always set.
        val cut = GenerationCut(StopCondition(maxChars = 5), startedAt = 0L)
        assertFalse(cut.accept("abcdefgh", null, now = 1))
        assertEquals("abcde", cut.text)
        assertEquals("it was long enough", cut.stoppedBecause)
    }

    @Test
    fun `generating until the brackets close stops there`() {
        val cut = GenerationCut(StopCondition(untilBalanced = true), startedAt = 0L)
        assertTrue(cut.accept("fun x() ", null, now = 1))
        assertTrue(cut.accept("{ return 1", null, now = 2))
        assertFalse(cut.accept(" }", null, now = 3))
        assertEquals("fun x() { return 1 }", cut.text)
        assertEquals("everything it opened was closed", cut.stoppedBecause)
    }

    @Test
    fun `balance does not fire before anything has opened`() {
        // Otherwise "generate until balanced" stops on the first token every time,
        // since an empty string is balanced.
        val cut = GenerationCut(StopCondition(untilBalanced = true), startedAt = 0L)
        assertTrue(cut.accept("plain prose", null, now = 1))
        assertNull(cut.stoppedBecause)
    }

    @Test
    fun `a stopped generation stays stopped`() {
        val cut = GenerationCut(StopCondition(maxChars = 2), startedAt = 0L)
        assertFalse(cut.accept("abc", null, now = 1))
        assertFalse(cut.accept("def", null, now = 2))
        assertEquals("ab", cut.text)
    }

    @Test
    fun `stop conditions survive a round trip, because they are data`() {
        val condition = StopCondition(
            tokenFloor = -2.5, surpriseBudget = 8.0,
            stopStrings = listOf("\n\n", "```"),
            maxChars = 500, maxMillis = 1500L, untilBalanced = true
        )
        assertEquals(condition, StopCondition.fromJson(condition.toJson()))
        assertNotNull(StopCondition.fromJson(org.json.JSONObject("{}")))
    }
}
