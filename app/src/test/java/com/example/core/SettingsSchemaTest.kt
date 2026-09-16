package com.example.core

import com.example.core.config.Settings
import com.example.core.config.SettingKind
import com.example.core.config.SettingsSchema
import com.example.core.config.SettingsStore
import com.example.core.layout.CursorAvoidStrategy
import com.example.core.layout.FreeKeyPins
import com.example.core.layout.InsetsMode
import com.example.core.layout.NormRect
import com.example.core.layout.PresentationMode
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
 * The schema is what lets "every setting is exposed" be true without anyone
 * maintaining a list, so the things worth testing are the ones that would make it
 * quietly incomplete or quietly destructive: a setting the codec persists but the
 * schema cannot see, and a generated panel writing a value the codec then mangles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSchemaTest {

    @Test
    fun `the schema covers every setting the codec persists`() {
        val persisted = SettingsStore.toJson(Settings()).keys().asSequence().toSet()
        val described = SettingsSchema.all.map { it.key }.toSet()
        assertEquals("schema and codec disagree about what a setting is", persisted, described)
    }

    @Test
    fun `no metadata entry describes a setting that does not exist`() {
        // The failure this catches is silent by construction: a mistyped key here
        // leaves the setting it was written for with a generated label and a guessed
        // group, which reads as an oversight rather than as a typo.
        assertEquals(emptyList<String>(), SettingsSchema.orphanedMetadata)
    }

    @Test
    fun `every setting has a label and lands in a named group`() {
        SettingsSchema.all.forEach { spec ->
            assertFalse("setting '${spec.key}' has a blank label", spec.label.isBlank())
            assertFalse("setting '${spec.key}' has a blank group", spec.group.isBlank())
        }
    }

    @Test
    fun `a slider range that excludes its own default would be unusable`() {
        SettingsSchema.all.forEach { spec ->
            val min = spec.min
            val max = spec.max
            if (min != null && max != null) {
                assertTrue("setting '${spec.key}': min $min is not below max $max", min < max)
                val default = (spec.default as? Number)?.toFloat()
                if (default != null) {
                    assertTrue(
                        "setting '${spec.key}': default $default falls outside its range $min..$max",
                        default in min..max
                    )
                }
            }
        }
    }

    @Test
    fun `every enum setting offers its own default as one of the options`() {
        SettingsSchema.all.filter { it.kind == SettingKind.ENUM && it.options.isNotEmpty() }
            .forEach { spec ->
                val default = spec.default?.toString() ?: return@forEach
                assertTrue(
                    "setting '${spec.key}': default '$default' is not among ${spec.options}",
                    spec.options.any { it.equals(default, ignoreCase = true) }
                )
            }
    }

    @Test
    fun `writing a value through the schema is the same as writing the field`() {
        val base = Settings()
        assertEquals(
            base.copy(keyGapDp = 7f),
            SettingsSchema.withValue(base, "keyGapDp", 7f)
        )
        assertEquals(
            base.copy(expertMode = true),
            SettingsSchema.withValue(base, "expertMode", true)
        )
        assertEquals(
            base.copy(longPressMs = 333L),
            SettingsSchema.withValue(base, "longPressMs", 333L)
        )
        assertEquals(
            base.copy(insetsMode = InsetsMode.KEYS_ONLY),
            SettingsSchema.withValue(base, "insetsMode", "KEYS_ONLY")
        )
    }

    @Test
    fun `a value typed as text still lands in the right type`() {
        val base = Settings()
        // A generated panel and a text field both hand over strings; nothing should
        // have to know which control a value came from.
        assertEquals(7f, SettingsSchema.withValue(base, "keyGapDp", "7").keyGapDp, 0.0001f)
        assertEquals(true, SettingsSchema.withValue(base, "expertMode", "true").expertMode)
        assertEquals(333L, SettingsSchema.withValue(base, "longPressMs", "333").longPressMs)
        assertEquals(
            0xFF112233L,
            SettingsSchema.withValue(base, "indicatorOnColor", "#FF112233").indicatorOnColor
        )
        assertEquals(
            listOf("a", "b"),
            SettingsSchema.withValue(base, "enabledLayoutIds", "a, b").enabledLayoutIds
        )
    }

    @Test
    fun `enum values are accepted in any casing and refused when unknown`() {
        val base = Settings()
        assertEquals(
            PresentationMode.SPLIT,
            SettingsSchema.withValue(base, "presentation", "split").presentation
        )
        // Not silently defaulted: a panel that guesses a constant leaves the setting
        // as it was rather than resetting it to the first one in the enum.
        assertEquals(base, SettingsSchema.withValue(base, "presentation", "diagonal"))
    }

    @Test
    fun `an unknown key and a nonsense value both change nothing`() {
        val base = Settings(keyGapDp = 5f)
        assertEquals(base, SettingsSchema.withValue(base, "noSuchSetting", 1))
        assertEquals(base, SettingsSchema.withValue(base, "keyGapDp", "not a number"))
        assertEquals(base, SettingsSchema.withValue(base, "keyGapDp", null))
    }

    @Test
    fun `reading back a written value gives what was written`() {
        SettingsSchema.all
            .filter { it.kind != SettingKind.ACTION }
            .forEach { spec ->
                val written = SettingsSchema.withValue(Settings(), spec.key, spec.default)
                assertEquals(
                    "setting '${spec.key}' does not survive being written back at its default",
                    Settings(), written
                )
            }
    }

    @Test
    fun `search finds a setting by key, by label and by group`() {
        assertTrue(SettingsSchema.search("keyGapDp").any { it.key == "keyGapDp" })
        assertTrue(SettingsSchema.search("gap between").any { it.key == "keyGapDp" })
        assertTrue(SettingsSchema.search("opacity").size > 1)
        assertEquals(SettingsSchema.all.size, SettingsSchema.search("   ").size)
        assertTrue(SettingsSchema.search("zzzzz-nothing").isEmpty())
    }

    @Test
    fun `api keys are marked secret without anyone listing them`() {
        SettingsSchema.all.filter { it.key.contains("apiKey", ignoreCase = true) }
            .also { assertTrue("expected at least one api key setting", it.isNotEmpty()) }
            .forEach { assertTrue("setting '${it.key}' is not marked secret", it.secret) }
    }

    @Test
    fun `coverage settings are grouped together, because they are one decision`() {
        val group = SettingsSchema.spec("insetsMode")?.group
        assertNotNull(group)
        assertEquals(group, SettingsSchema.spec("cursorAvoidStrategy")?.group)
    }

    // -----------------------------------------------------------------------
    // Free-key pins
    // -----------------------------------------------------------------------

    @Test
    fun `pins survive a round trip`() {
        val pins = mapOf(
            "k_a" to NormRect(0.1f, 0.2f, 0.2f, 0.3f),
            "esc" to NormRect(0f, 0f, 0.1f, 0.05f)
        )
        val back = FreeKeyPins.parse(FreeKeyPins.write(pins))
        assertEquals(pins.keys, back.keys)
        pins.forEach { (id, r) ->
            assertEquals(r.left, back.getValue(id).left, 0.0001f)
            assertEquals(r.bottom, back.getValue(id).bottom, 0.0001f)
        }
    }

    @Test
    fun `unreadable pins are no pins rather than a crash`() {
        assertTrue(FreeKeyPins.parse("").isEmpty())
        assertTrue(FreeKeyPins.parse("garbage").isEmpty())
        assertTrue(FreeKeyPins.parse("""{"k":[0.1]}""").isEmpty())
    }

    @Test
    fun `moving a key keeps its size`() {
        val from = NormRect(0.2f, 0.2f, 0.3f, 0.26f)
        val moved = FreeKeyPins.moved(emptyMap(), "k", from, dx = 0.1f, dy = 0.05f).getValue("k")
        assertEquals(0.3f, moved.left, 0.0001f)
        assertEquals(0.25f, moved.top, 0.0001f)
        assertEquals(from.width, moved.width, 0.0001f)
        assertEquals(from.height, moved.height, 0.0001f)
    }

    @Test
    fun `a key can never be dragged entirely off the screen`() {
        val from = NormRect(0.2f, 0.2f, 0.3f, 0.26f)
        val far = FreeKeyPins.moved(emptyMap(), "k", from, dx = 99f, dy = 99f).getValue("k")
        // Half of it may hang off; all of it may not, or it becomes a key that exists
        // and cannot be pressed.
        assertTrue("dragged past the right edge", far.left < 1f)
        assertTrue("dragged past the bottom edge", far.top < 1f)
        val back = FreeKeyPins.moved(emptyMap(), "k", from, dx = -99f, dy = -99f).getValue("k")
        assertTrue("dragged past the left edge", back.right > 0f)
        assertTrue("dragged past the top edge", back.bottom > 0f)
    }

    @Test
    fun `a second move continues from where the first left off`() {
        val from = NormRect(0.2f, 0.2f, 0.3f, 0.26f)
        val once = FreeKeyPins.moved(emptyMap(), "k", from, 0.1f, 0f)
        val twice = FreeKeyPins.moved(once, "k", from, 0.1f, 0f)
        assertEquals(0.4f, twice.getValue("k").left, 0.0001f)
    }

    @Test
    fun `moving one key leaves the others alone`() {
        val other = NormRect(0.5f, 0.5f, 0.6f, 0.56f)
        val pins = mapOf("other" to other)
        val after = FreeKeyPins.moved(pins, "k", NormRect(0f, 0f, 0.1f, 0.06f), 0.1f, 0.1f)
        assertEquals(other, after["other"])
        assertNotNull(after["k"])
    }

    @Test
    fun `every cursor-avoid strategy and insets mode has a name the codec accepts`() {
        // These two are the settings a free-floating layout depends on, and both are
        // written by name. A constant the codec cannot read back is a setting that
        // silently resets.
        InsetsMode.entries.forEach { mode ->
            assertEquals(
                mode,
                SettingsSchema.withValue(Settings(), "insetsMode", mode.name).insetsMode
            )
        }
        CursorAvoidStrategy.entries.forEach { strategy ->
            assertEquals(
                strategy,
                SettingsSchema.withValue(Settings(), "cursorAvoidStrategy", strategy.name)
                    .cursorAvoidStrategy
            )
        }
    }
}
