package com.example.core

import com.example.core.config.Settings
import com.example.core.layout.ModifierKind
import com.example.core.layout.ModifierMode
import com.example.ime.KeyboardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When a modifier comes back down.
 *
 * The bug this is about produced "it puts allcaps everywhere", and it was one word
 * wide: a modifier raised from outside a key press was not marked one-shot, and
 * [KeyboardState.consumeOneShots] only clears states that say they are one-shot. So
 * auto-capitalisation raised Shift for one letter and it stayed up for the rest of the
 * session — every letter after it capital, in every field, until the user noticed and
 * pressed Shift themselves to turn off something they never turned on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModifierStateTest {

    private fun state(settings: Settings = Settings()) = KeyboardState { settings }

    @Test
    fun `a shift raised for one letter comes down after it`() {
        val s = state()
        s.setModifier(ModifierKind.SHIFT, active = true, oneShot = true)
        assertTrue(s.isActive(ModifierKind.SHIFT))
        s.consumeOneShots()
        assertFalse("shift stayed up after the character it was raised for", s.isActive(ModifierKind.SHIFT))
    }

    @Test
    fun `without that, it never comes down — which was the bug`() {
        val s = state()
        s.setModifier(ModifierKind.SHIFT, active = true)
        s.consumeOneShots()
        assertTrue(
            "a modifier set without one-shot is meant to stay up; that is why the " +
                "capitalisation path has to ask for one",
            s.isActive(ModifierKind.SHIFT)
        )
    }

    @Test
    fun `a lock is not undone by typing`() {
        // Somebody who asked for caps lock gets caps lock, and the next character does
        // not quietly cancel it.
        val s = state()
        s.setModifier(ModifierKind.SHIFT, active = true, locked = true, oneShot = true)
        s.consumeOneShots()
        assertTrue(s.isActive(ModifierKind.SHIFT))
        assertTrue(s.isLocked(ModifierKind.SHIFT))
    }

    @Test
    fun `tapping shift twice locks it, and a third tap clears it`() {
        // The phone convention, and the only thing that should ever produce a lock
        // without being asked for by name.
        val s = state(Settings(shiftOneShot = true))
        s.pressModifier(ModifierKind.SHIFT, ModifierMode.ONE_SHOT)
        assertTrue(s.isActive(ModifierKind.SHIFT))
        assertFalse(s.isLocked(ModifierKind.SHIFT))

        s.pressModifier(ModifierKind.SHIFT, ModifierMode.ONE_SHOT)
        assertTrue(s.isLocked(ModifierKind.SHIFT))

        s.pressModifier(ModifierKind.SHIFT, ModifierMode.ONE_SHOT)
        assertFalse(s.isActive(ModifierKind.SHIFT))
    }

    @Test
    fun `tapping a shift the keyboard raised cancels it rather than locking caps`() {
        // What somebody does the moment they see a capital they did not ask for. The
        // double-tap rule would read it as the second of two taps and give them caps
        // lock — the worst possible answer to "stop capitalising".
        val s = state()
        s.setModifier(ModifierKind.SHIFT, active = true, oneShot = true)
        s.pressModifier(ModifierKind.SHIFT, ModifierMode.ONE_SHOT)
        assertFalse("cancelling an unasked-for capital produced caps lock", s.isLocked(ModifierKind.SHIFT))
        assertFalse(s.isActive(ModifierKind.SHIFT))
    }

    @Test
    fun `tapping a shift the user raised still locks on the second tap`() {
        // The phone convention has to survive the fix above.
        val s = state(Settings(shiftOneShot = true))
        s.pressModifier(ModifierKind.SHIFT, ModifierMode.ONE_SHOT)
        s.pressModifier(ModifierKind.SHIFT, ModifierMode.ONE_SHOT)
        assertTrue(s.isLocked(ModifierKind.SHIFT))
    }

    @Test
    fun `a new field starts with nothing held`() {
        val s = state()
        s.setModifier(ModifierKind.SHIFT, active = true, locked = true)
        s.setModifier(ModifierKind.CTRL, active = true)
        s.clearAllModifiers()
        ModifierKind.entries.forEach {
            assertFalse("$it survived into a new field", s.isActive(it))
        }
    }

    @Test
    fun `the caps lamp says locked and not merely on`() {
        // Three keys share one Shift, so the highlight alone cannot distinguish "held
        // for one letter" from "locked". The lamp is what carries that difference.
        val s = state()
        s.setModifier(ModifierKind.SHIFT, active = true, oneShot = true)
        assertFalse(s.flag(com.example.core.layout.IndicatorKeys.CAPS_LOCK))
        s.setModifier(ModifierKind.SHIFT, active = true, locked = true)
        assertTrue(s.flag(com.example.core.layout.IndicatorKeys.CAPS_LOCK))
    }

    @Test
    fun `capitalisation is off until it is asked for`() {
        // A keyboard guessing at capitals is wrong often enough to be noticed and quiet
        // enough not to be, and an unwanted capital costs more than a missing one.
        assertFalse(Settings().autoCapitalize)
    }
}
