package com.example.core

import com.example.core.io.ShortcutSession
import org.junit.Assert.*
import org.junit.Test

class ShortcutSessionTest {
    @Test fun `ordinary keyboard hides normally but a pin survives transient focus loss`() {
        val session = ShortcutSession()
        session.start(false)
        session.hidden()
        assertFalse(session.active)
        session.start(true)
        session.hidden()
        assertTrue(session.shouldRestore(false, true, false))
    }

    @Test fun `pin never brings keyboard over settings keyguard or pocket lock`() {
        val session = ShortcutSession().apply { start(true) }
        assertFalse(session.shouldRestore(true, true, false))
        assertFalse(session.shouldRestore(false, false, false))
        assertFalse(session.shouldRestore(false, true, true))
    }

    @Test fun `explicit hide stops persistence and a future focus change cannot resurrect it`() {
        val session = ShortcutSession().apply { start(true) }
        session.stop()
        session.hidden()
        session.updatePreference(true)
        assertFalse(session.active)
        assertFalse(session.shouldRestore(false, true, false))
    }

    @Test fun `turning persistence off applies to the running session without opening a keyboard`() {
        val session = ShortcutSession().apply { start(true) }
        session.updatePreference(false)
        assertTrue(session.active)
        assertFalse(session.shouldRestore(false, true, false))
        session.hidden()
        assertFalse(session.active)
    }
}
