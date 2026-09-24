package com.example.core

import com.example.core.text.CapitalHow
import com.example.core.text.CapitalMoment
import com.example.core.text.CapitalWhen
import com.example.core.text.Capitalisation
import com.example.core.text.ProperNouns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProperNounsTest {

    @Test
    fun `a word capitalised mid-sentence twice becomes a name`() {
        val n = ProperNouns()
        n.observe("Kowalski", capitalImplied = false)
        assertNull("once is not a habit", n.formFor("kowalski"))
        n.observe("Kowalski", capitalImplied = false)
        assertEquals("Kowalski", n.formFor("kowalski"))
    }

    @Test
    fun `a capital at the start of a sentence says nothing about the word`() {
        val n = ProperNouns()
        repeat(5) { n.observe("Dom", capitalImplied = true) }
        assertNull(n.formFor("dom"))
    }

    @Test
    fun `written in lower case often enough, it stops being a name`() {
        val n = ProperNouns()
        repeat(3) { n.observe("Róża", capitalImplied = false) }
        repeat(2) { n.observe("róża", capitalImplied = false) }
        assertNull(n.formFor("róża"))
    }

    @Test
    fun `the form comes back as written, not merely capitalised`() {
        val n = ProperNouns()
        repeat(2) { n.observe("iPhone", capitalImplied = false) }
        // Not counted: its first letter is lower case, so it never looked capitalised.
        // Declaring it is how such a word is taught.
        n.declare("iPhone")
        assertEquals("iPhone", n.formFor("iphone"))
    }

    @Test
    fun `acronyms are not names to learn`() {
        val n = ProperNouns()
        repeat(4) { n.observe("NASA", capitalImplied = false) }
        assertNull(n.formFor("nasa"))
    }

    @Test
    fun `what was learned survives being saved`() {
        val n = ProperNouns()
        repeat(2) { n.observe("Łódź", capitalImplied = false) }
        n.declare("McDonald")
        val back = ProperNouns.fromJson(n.toJson())
        assertEquals("Łódź", back.formFor("łódź"))
        assertEquals("McDonald", back.formFor("mcdonald"))
        assertNull(ProperNouns.fromJson("junk").formFor("łódź"))
    }

    @Test
    fun `the default rules fix a name once it is typed, and a veto still wins`() {
        val names = setOf(CapitalWhen.PROPER_NOUN)
        assertEquals(
            CapitalHow.FIX_AFTER_WORD,
            Capitalisation.action(Capitalisation.DEFAULT, names, CapitalMoment.TYPING)
        )
        val vetoed = Capitalisation.DEFAULT +
            com.example.core.text.CapitalRule(CapitalWhen.PROPER_NOUN, CapitalHow.NOTHING)
        assertEquals(CapitalHow.NOTHING, Capitalisation.action(vetoed, names, CapitalMoment.TYPING))
    }
}
