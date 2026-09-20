package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.caps.Abilities
import com.example.core.caps.Availability
import com.example.core.caps.Need
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rule that keeps "uses every permission" and "needs none of them" compatible.
 *
 * This app is meant to handle every kind of input and output, so nearly every special
 * permission on the platform buys it something. It is also meant to work with none of
 * them granted, doing less. Those two only stay compatible if every ability names its
 * own reduced form — so that is checked here rather than left to whoever adds the next
 * one remembering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AbilitiesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `every ability says what happens without it`() {
        Abilities.ALL.forEach { ability ->
            assertFalse(
                "'${ability.id}' does not say what the keyboard does when it is refused",
                ability.without.isBlank()
            )
            assertFalse("'${ability.id}' does not say what it gives", ability.gives.isBlank())
            assertFalse("'${ability.id}' has no label", ability.label.isBlank())
        }
    }

    @Test
    fun `no ability is allowed to answer that the app stops working`() {
        // The one sentence this registry exists to make impossible. Refusing a
        // permission has to leave something behind, and if it genuinely leaves nothing
        // — as acting inside other apps does — the entry has to say so in those words
        // rather than implying the whole keyboard falls over.
        val fatal = listOf("nothing works", "the app stops", "unusable", "cannot be used")
        Abilities.ALL.forEach { ability ->
            fatal.forEach { phrase ->
                assertFalse(
                    "'${ability.id}' claims the app breaks without it: ${ability.without}",
                    ability.without.lowercase().contains(phrase)
                )
            }
        }
    }

    @Test
    fun `ids are unique, because features look abilities up by them`() {
        assertEquals(Abilities.ALL.size, Abilities.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun `what is not built yet says so instead of looking broken`() {
        // A permission the user grants for a feature that does not exist is worse than
        // an honest gap: it reads as the feature being broken rather than absent.
        val unbuilt = Abilities.ALL.filterNot { it.built }
        unbuilt.forEach {
            assertEquals(Availability.ABSENT, Abilities.availability(context, it))
        }
        assertFalse("nothing is listed as still to come, which is unlikely", unbuilt.isEmpty())
    }

    @Test
    fun `dictation is the one that is built and asks with a dialog`() {
        val dictation = Abilities.byId(Abilities.DICTATION)
        assertNotNull(dictation)
        assertTrue(dictation!!.built)
        assertTrue(dictation.needs is Need.Permission)
        // Not granted in a fresh test environment, and that is the interesting half:
        // it reports off rather than throwing or assuming.
        assertEquals(Availability.OFF, Abilities.availability(context, dictation))
        assertFalse(Abilities.isOn(context, Abilities.DICTATION))
    }

    @Test
    fun `a special access sends you to a screen and a permission does not`() {
        // The difference is the whole reason the two are separate kinds: one can be
        // asked for in a dialog and the other can only be explained and navigated to.
        Abilities.ALL.forEach { ability ->
            val intent = Abilities.settingsIntent(context, ability)
            when (ability.needs) {
                is Need.SpecialAccess -> assertNotNull(
                    "'${ability.id}' needs a settings screen and names none", intent
                )
                else -> assertNull(
                    "'${ability.id}' is an ordinary permission and should be asked, not navigated to",
                    intent
                )
            }
        }
    }

    @Test
    fun `a special access says where in the settings it actually is`() {
        // "Enable it in settings" is not instructions. Android buries these several
        // screens deep and moves them between versions.
        Abilities.ALL.mapNotNull { it.needs as? Need.SpecialAccess }.forEach {
            assertFalse("a special access with no path named", it.where.isBlank())
            assertFalse(it.settingsAction.isBlank())
        }
    }

    @Test
    fun `an unknown id is null rather than a guess`() {
        assertNull(Abilities.byId("no_such_thing"))
        assertFalse(Abilities.isOn(context, "no_such_thing"))
    }

    @Test
    fun `acting inside other apps is honest that there is no lesser way`() {
        // It is the one entry whose fallback is genuinely nothing, and the reason
        // belongs in the registry: an input method reaches the text field and no
        // further. Hiding that would make the permission look optional when the
        // feature is the permission.
        val act = Abilities.byId(Abilities.ACT_IN_OTHER_APPS)
        assertNotNull(act)
        assertTrue(act!!.without.contains("no narrower permission"))
    }
}
