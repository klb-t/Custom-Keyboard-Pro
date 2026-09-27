package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.io.ActionProfiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionProfilesTest {
    private val base = """{"schemaVersion":1,"profiles":[{"id":"back","label":"Back","command":"back"}]}"""

    @Test fun `bundled examples resolve to existing actions and routes`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val loaded = ActionProfiles.load(context, "[]")
        assertTrue(loaded.errors.toString(), loaded.errors.isEmpty())
        assertTrue(loaded.profiles.size > 40)
        assertTrue(loaded.profiles.any { it.route == "media" })
    }

    @Test fun `an override changes one field without losing the action`() {
        val result = ActionProfiles.merge(base, """[{"id":"back","label":"Return"}]""")
        assertEquals("Return", result.profiles.single().label)
        assertEquals("back", result.profiles.single().command)
    }

    @Test fun `hide and new instances need no new UI code`() {
        val result = ActionProfiles.merge(base,
            """[{"id":"back","hidden":true},{"id":"sound","label":"Quiet","command":"volume down"}]""")
        assertTrue(result.errors.isEmpty())
        assertEquals("sound", result.profiles.single().id)
    }

    @Test fun `invalid action does not replace a working profile`() {
        val result = ActionProfiles.merge(base, """[{"id":"back","command":"made_up"}]""")
        assertEquals("back", result.profiles.single().command)
        assertEquals(1, result.errors.size)
    }

    @Test fun `unknown schemas and duplicate ids are reported`() {
        assertFalse(ActionProfiles.merge("""{"schemaVersion":2,"profiles":[]}""", "[]").errors.isEmpty())
        val result = ActionProfiles.merge(base,
            """[{"id":"back","label":"First"},{"id":"back","label":"Second"}]""")
        assertEquals("First", result.profiles.single().label)
        assertEquals(1, result.errors.size)
    }
}
