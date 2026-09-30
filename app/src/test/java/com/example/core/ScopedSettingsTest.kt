package com.example.core

import com.example.core.config.*
import com.example.core.layout.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScopedSettingsTest {
    private data class ViewportCase(val id: String, val global: Double, val layout: Double, val panel: Double) {
        val key: String get() = Knobs.PREFIX + id
    }
    private val viewportCases = listOf(
        ViewportCase("viewportMinimumKeysDp", 72.0, 120.0, 144.0),
        ViewportCase("viewportMinimumKeysFraction", .4, .5, .6),
        ViewportCase("toolbarRowHeightDp", 36.0, 44.0, 48.0),
        ViewportCase("keyboardMaxScreenFraction", .7, .8, .9)
    )
    private fun fixture(id: String = "one"): LayoutDef = LayoutDef(id, id,
        linkedMapOf("base" to LayerDef("base", listOf(RowDef(listOf(KeyDef("a"), KeyDef("b"))))),
            "other" to LayerDef("other", listOf(RowDef(listOf(KeyDef("a")))))),
        elements = listOf(ElementDef("main", layer = "base"), ElementDef("spare", layer = "other")))
    private fun set(layout: LayoutDef, address: SettingsAddress, value: Boolean?): LayoutDef =
        ScopedSettingsResolver.withOverride(layout, address, "autoCapitalize", value).getOrThrow()
    @Test fun `cascade has exact provenance and removing a value restores its parent`() {
        val global = Settings(autoCapitalize = false)
        val layoutAddress = SettingsAddress("one")
        val panelAddress = SettingsAddress("one", "main")
        val keyAddress = SettingsAddress("one", "main", "base", "a")
        var layout = set(fixture(), layoutAddress, true)
        assertEquals(SettingsScope.LAYOUT, ScopedSettingsResolver.effective(global, layout, keyAddress, "autoCapitalize").source.scope)
        layout = set(layout, panelAddress, false)
        layout = set(layout, keyAddress, true)
        val effective = ScopedSettingsResolver.effective(global, layout, keyAddress, "autoCapitalize")
        assertEquals(true, effective.value)
        assertEquals(SettingsSource(SettingsScope.KEY, "a"), effective.source)
        assertFalse(effective.inherited)
        layout = set(layout, keyAddress, null)
        val inherited = ScopedSettingsResolver.effective(global, layout, keyAddress, "autoCapitalize")
        assertEquals(false, inherited.value)
        assertEquals(SettingsSource(SettingsScope.PANEL, "main"), inherited.source)
        assertTrue(inherited.inherited)
        assertFalse(layout.layers.getValue("base").allKeys.first().settingsOverrides.keys.contains("autoCapitalize"))
    }
    @Test fun `same key id in another layer and another layout stays isolated`() {
        val layout = set(fixture(), SettingsAddress("one", "main", "base", "a"), true)
        assertEquals(false, ScopedSettingsResolver.effective(Settings(), layout, SettingsAddress("one", "spare", "other", "a"), "autoCapitalize").value)
        assertEquals(false, ScopedSettingsResolver.effective(Settings(), fixture("two"), SettingsAddress("two", "main", "base", "a"), "autoCapitalize").value)
        assertTrue(ScopedSettingsResolver.withOverride(layout, SettingsAddress("two"), "autoCapitalize", true).isFailure)
        assertTrue(ScopedSettingsResolver.withOverride(layout, SettingsAddress("one", "spare", "base", "a"), "autoCapitalize", true).isFailure)
    }
    @Test fun `default policy can suppress local overrides without deleting them`() {
        var layout = set(fixture(), SettingsAddress("one"), true)
        layout = set(layout, SettingsAddress("one", "main"), false)
        layout = set(layout, SettingsAddress("one", "main", "base", "a"), true)
        val target = SettingsAddress("one", "main", "base", "a")
        val defaults = ScopedSettingsResolver.effective(Settings(localSettingsPolicy = SettingsOverridePolicy.DEFAULTS_ONLY), layout, target, "autoCapitalize")
        assertEquals(false, defaults.value)
        assertEquals(SettingsScope.KEYBOARD_DEFAULTS, defaults.source.scope)
        assertEquals(3, defaults.suppressedSources.size)
        val layoutOnly = ScopedSettingsResolver.effective(Settings(localSettingsPolicy = SettingsOverridePolicy.LAYOUT_ONLY), layout, target, "autoCapitalize")
        assertEquals(true, layoutOnly.value)
        assertEquals(SettingsScope.LAYOUT, layoutOnly.source.scope)
        assertEquals(2, layoutOnly.suppressedSources.size)
        assertEquals(1, layout.layers.getValue("base").allKeys.first().settingsOverrides.keys.size)
    }
    @Test fun `new codec retains sparse typed overrides and old layouts inherit all values`() {
        var layout = set(fixture(), SettingsAddress("one"), true)
        layout = ScopedSettingsResolver.withOverride(layout, SettingsAddress("one", "main"), "hapticMs", 22).getOrThrow()
        layout = ScopedSettingsResolver.withOverride(layout, SettingsAddress("one", "main", "base", "a"), "keyTextScale", 1.2).getOrThrow()
        assertEquals(layout, LayoutJson.parse(LayoutJson.writeString(layout)))
        val old = LayoutJson.parse("""{"id":"old","rows":[["a","b"]]}""")
        assertTrue(old.settingsOverrides.isEmpty)
        assertTrue(old.base.allKeys.all { it.settingsOverrides.isEmpty })
    }
    @Test fun `credentials permissions invalid types and nonapplicable scope are rejected before mutation`() {
        val layout = fixture()
        assertTrue(ScopedSettingsResolver.withOverride(layout, SettingsAddress("one"), "aiApiKey", "leak").isFailure)
        assertTrue(ScopedSettingsResolver.withOverride(layout, SettingsAddress("one"), "clipboardIgnorePasswordFields", false).isFailure)
        assertTrue(ScopedSettingsResolver.withOverride(layout, SettingsAddress("one"), "autoCapitalize", "maybe").isFailure)
        assertTrue(ScopedSettingsResolver.withOverride(layout, SettingsAddress("one", "main", "base", "a"), "toolbarRowsJson", "{}").isFailure)
        assertTrue(runCatching { LayoutJson.parse(LayoutJson.write(layout).put("settingsOverrides", JSONObject().put("aiApiKey", "leak"))) }.isFailure)
        assertTrue(layout.settingsOverrides.isEmpty)
    }
    @Test fun `visibility level cannot change effective value or source`() {
        val layout = set(fixture(), SettingsAddress("one", "main", "base", "a"), true)
        val address = SettingsAddress("one", "main", "base", "a")
        val baseline = ScopedSettingsResolver.effective(Settings(), layout, address, "autoCapitalize")
        SettingsLevel.entries.forEach { level ->
            assertEquals(baseline, ScopedSettingsResolver.effective(SettingsHierarchy.selectLevel(Settings(), level), layout, address, "autoCapitalize"))
        }
    }
    @Test fun `explicit empty container is inheritance not copied defaults`() {
        val layout = LayoutJson.parse(LayoutJson.write(fixture()).put("settingsOverrides", JSONObject()))
        assertTrue(layout.settingsOverrides.isEmpty)
        assertEquals(true, ScopedSettingsResolver.resolve(Settings(autoCapitalize = true), layout).snapshot.autoCapitalize)
        assertEquals(false, ScopedSettingsResolver.resolve(Settings(autoCapitalize = false), layout).snapshot.autoCapitalize)
    }
    @Test fun `main docked panel follows authored shift layer while spare panel remains exact`() {
        val base = fixture()
        val layout = base.copy(layers = base.layers + ("shift" to LayerDef("shift", listOf(RowDef(listOf(KeyDef("a")))))))
        val withPanel = set(layout, SettingsAddress("one", "main"), true)
        assertEquals(SettingsScope.PANEL, ScopedSettingsResolver.effective(Settings(), withPanel, SettingsAddress("one", "main", "shift", "a"), "autoCapitalize").source.scope)
        assertTrue(ScopedSettingsResolver.withOverride(layout, SettingsAddress("one", "spare", "shift", "a"), "autoCapitalize", true).isFailure)
    }
    @Test fun `profile applies only to target and rejects a mixed global patch as a whole`() {
        val layout = fixture()
        val address = SettingsAddress("one", "main", "base", "a")
        val profile = SettingsProfile("local", "Local", "", JSONObject().put("autoCapitalize", true).put("hapticMs", 22))
        val applied = ScopedSettingsResolver.applyProfile(layout, address, profile).getOrThrow()
        assertTrue(layout.layers.getValue("base").allKeys.first().settingsOverrides.isEmpty)
        assertEquals(true, ScopedSettingsResolver.effective(Settings(), applied, address, "autoCapitalize").value)
        val other = SettingsAddress("one", "main", "base", "b")
        assertEquals(false, ScopedSettingsResolver.effective(Settings(), applied, other, "autoCapitalize").value)
        val bad = profile.copy(values = JSONObject().put("autoCapitalize", true).put("themeId", "dark"))
        assertTrue(ScopedSettingsResolver.applyProfile(layout, address, bad).isFailure)
        assertTrue(layout.settingsOverrides.isEmpty)
    }
    @Test fun `overrides cannot be mutated through exposed collections or serialized copies`() {
        val overrides = SettingsOverrides.EMPTY.with(SettingsScope.LAYOUT, "autoCapitalize", true).getOrThrow()
        assertTrue(runCatching { (overrides.keys as MutableSet<String>).clear() }.isFailure)
        overrides.toJson().put("autoCapitalize", false)
        assertEquals(true, overrides["autoCapitalize"]!!.raw())
        val scopes = SettingsSchema.spec("autoCapitalize")!!.applicableScopes
        assertTrue(runCatching { (scopes as MutableSet<SettingsScope>).clear() }.isFailure)
        assertTrue(SettingsScope.KEY in SettingsSchema.spec("autoCapitalize")!!.applicableScopes)
    }
    @Test fun `structured key editing preserves siblings layers and local settings`() {
        val original = fixture().copy(layers = fixture().layers.mapValues { (name, layer) ->
            if (name == "base") layer.copy(rows = layer.rows.map { row -> row.copy(keys = row.keys.map { key ->
                if (key.id == "a") key.copy(visible = false, settingsOverrides = SettingsOverrides.EMPTY.with(SettingsScope.KEY, "autoCapitalize", true).getOrThrow()) else key
            }) }) else layer
        })
        val key = original.layers.getValue("base").allKeys.first()
        val updated = LayoutAuthoring.replaceKeyInstance(original, "base", "a", key.copy(label = "local"))
        assertEquals(original.layers.getValue("other"), updated.layers.getValue("other"))
        assertEquals(original.layers.getValue("base").allKeys[1], updated.layers.getValue("base").allKeys[1])
        assertEquals(key.settingsOverrides, updated.layers.getValue("base").allKeys.first().settingsOverrides)
        assertFalse(updated.layers.getValue("base").allKeys.first().visible)
        assertTrue(runCatching { LayoutAuthoring.replaceKeyInstance(original, "base", "a", key.copy(id = "another")) }.isFailure)
    }
    @Test fun `toolbar overrides belong only to the shared main docked panel`() {
        val layout = fixture()
        val rows = """{"version":1,"rows":[]}"""
        val main = SettingsAddress("one", "main")
        val spare = SettingsAddress("one", "spare")
        assertEquals("main", ToolbarRows.ownerPanelId(layout))
        assertTrue(SettingsHierarchy.appliesTo(layout, main, "toolbarRowsJson"))
        assertFalse(SettingsHierarchy.appliesTo(layout, spare, "toolbarRowsJson"))
        assertTrue(ScopedSettingsResolver.withOverride(layout, main, "toolbarRowsJson", rows).isSuccess)
        assertTrue(ScopedSettingsResolver.withOverride(layout, spare, "toolbarRowsJson", rows).isFailure)
        val imported = layout.copy(elements = layout.elements.map { panel -> if (panel.id == "spare")
            panel.copy(settingsOverrides = SettingsOverrides.EMPTY.with(SettingsScope.PANEL, "toolbarRowsJson", rows).getOrThrow()) else panel })
        val resolved = ScopedSettingsResolver.resolve(Settings(), imported, "spare")
        assertEquals("", resolved.snapshot.toolbarRowsJson)
        assertEquals(listOf(SettingsSource(SettingsScope.PANEL, "spare")), resolved.suppressed["toolbarRowsJson"])
        assertTrue(ScopedSettingsResolver.withOverride(imported, spare, "toolbarRowsJson", null).getOrThrow().elements[1].settingsOverrides.isEmpty)
        val rearranged = layout.copy(elements = layout.elements.map { if (it.id == "main") it.copy(visible = false) else it })
        assertEquals("spare", ToolbarRows.ownerPanelId(rearranged))
        assertTrue(SettingsHierarchy.appliesTo(rearranged, spare, "toolbarRowsJson"))
    }
    @Test fun `viewport thresholds are validated Expert defaults with layout and main panel scopes`() {
        val layout = fixture()
        viewportCases.forEach { item ->
            val spec = SettingsSchema.spec(item.key)!!
            val knob = Knobs.byKey(item.key)!!
            assertEquals(SettingsOwner.KEYBOARD_DEFAULTS, spec.owner)
            assertEquals(SettingsLevel.EXPERT, spec.minimumLevel)
            assertEquals(setOf(SettingsScope.KEYBOARD_DEFAULTS, SettingsScope.LAYOUT, SettingsScope.PANEL), spec.applicableScopes)
            assertTrue(SettingsHierarchy.appliesTo(layout, SettingsAddress("one"), item.key))
            assertTrue(SettingsHierarchy.appliesTo(layout, SettingsAddress("one", "main"), item.key))
            assertFalse(SettingsHierarchy.appliesTo(layout, SettingsAddress("one", "spare"), item.key))
            assertFalse(SettingsHierarchy.appliesTo(layout, SettingsAddress("one", "main", "base", "a"), item.key))
            assertTrue(ScopedSettingsResolver.withOverride(layout, SettingsAddress("one"), item.key, knob.min).isSuccess)
            assertTrue(ScopedSettingsResolver.withOverride(layout, SettingsAddress("one"), item.key, knob.max).isSuccess)
            assertTrue(ScopedSettingsResolver.withOverride(layout, SettingsAddress("one"), item.key, knob.min - 1.0).isFailure)
            assertTrue(ScopedSettingsResolver.withOverride(layout, SettingsAddress("one"), item.key, knob.max + 1.0).isFailure)
            assertTrue(ScopedSettingsResolver.withOverride(layout, SettingsAddress("one"), item.key, Double.NaN).isFailure)
            assertTrue(ScopedSettingsResolver.withOverride(layout, SettingsAddress("one", "main", "base", "a"), item.key, item.panel).isFailure)
        }
    }
    @Test fun `viewport thresholds cascade through the canonical codec and honor ignore policies`() {
        viewportCases.forEach { item ->
            val knob = Knobs.byKey(item.key)!!
            val global = Settings(knobs = mapOf(item.id to item.global))
            assertEquals(item.global, SettingsStore.fromJson(SettingsStore.toJson(global)).knob(knob), 0.0)
            var layout = ScopedSettingsResolver.withOverride(fixture(), SettingsAddress("one"), item.key, item.layout).getOrThrow()
            val address = SettingsAddress("one", "main")
            layout = ScopedSettingsResolver.withOverride(layout, address, item.key, item.panel).getOrThrow()
            layout = LayoutJson.parse(LayoutJson.writeString(layout))
            val active = ScopedSettingsResolver.effective(global, layout, address, item.key)
            assertEquals(item.panel, (active.value as Number).toDouble(), 0.0)
            assertEquals(SettingsSource(SettingsScope.PANEL, "main"), active.source)
            val layoutOnly = ScopedSettingsResolver.effective(global.copy(localSettingsPolicy = SettingsOverridePolicy.LAYOUT_ONLY), layout, address, item.key)
            assertEquals(item.layout, (layoutOnly.value as Number).toDouble(), 0.0)
            assertEquals(SettingsScope.LAYOUT, layoutOnly.source.scope)
            assertEquals(listOf(SettingsSource(SettingsScope.PANEL, "main")), layoutOnly.suppressedSources)
            val defaultsOnly = ScopedSettingsResolver.effective(global.copy(localSettingsPolicy = SettingsOverridePolicy.DEFAULTS_ONLY), layout, address, item.key)
            assertEquals(item.global, (defaultsOnly.value as Number).toDouble(), 0.0)
            assertEquals(SettingsScope.KEYBOARD_DEFAULTS, defaultsOnly.source.scope)
            assertEquals(2, defaultsOnly.suppressedSources.size)
            val inherited = ScopedSettingsResolver.withOverride(layout, address, item.key, null).getOrThrow()
            assertEquals(item.layout, ScopedSettingsResolver.resolve(global, inherited, "main").snapshot.knob(knob), 0.0)
            assertTrue(layout.elements.first().settingsOverrides.keys.contains(item.key))
        }
    }
    @Test fun `dormant imported viewport values are retained until removal or a real owner change`() {
        viewportCases.forEach { item ->
            val knob = Knobs.byKey(item.key)!!
            val global = Settings(knobs = mapOf(item.id to item.global))
            val json = LayoutJson.write(fixture())
            json.getJSONArray("elements").getJSONObject(1).put("settingsOverrides",
                JSONObject().put(item.key, item.panel).put("autoCapitalize", true))
            val imported = LayoutJson.parse(json)
            val address = SettingsAddress("one", "spare")
            val inactive = ScopedSettingsResolver.resolve(global, imported, "spare")
            assertEquals(item.global, inactive.snapshot.knob(knob), 0.0)
            assertEquals(listOf(SettingsSource(SettingsScope.PANEL, "spare")), inactive.suppressed[item.key])
            assertTrue(inactive.snapshot.autoCapitalize)
            assertTrue(imported.elements[1].settingsOverrides.keys.contains(item.key))
            assertTrue(ScopedSettingsResolver.withOverride(imported, address, item.key, item.layout).isFailure)
            val cleared = ScopedSettingsResolver.withOverride(imported, address, item.key, null).getOrThrow()
            assertFalse(cleared.elements[1].settingsOverrides.keys.contains(item.key))
            assertEquals(true, cleared.elements[1].settingsOverrides["autoCapitalize"]!!.raw())
            val newOwner = imported.copy(elements = imported.elements.map { if (it.id == "main") it.copy(visible = false) else it })
            val active = ScopedSettingsResolver.resolve(global, newOwner, "spare")
            assertEquals(item.panel, active.snapshot.knob(knob), 0.0)
            assertEquals(SettingsSource(SettingsScope.PANEL, "spare"), active.provenance[item.key])
            assertTrue(active.suppressed[item.key].isNullOrEmpty())
            assertTrue(ScopedSettingsResolver.withOverride(newOwner, address, item.key, item.layout).isSuccess)
        }
    }
}
