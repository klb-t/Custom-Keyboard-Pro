package com.example.core

import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.layout.BuiltinLayouts
import com.example.core.layout.ClipboardOp
import com.example.core.layout.IndicatorSource
import com.example.core.layout.IndicatorStyle
import com.example.core.layout.KeyAction
import com.example.core.layout.KeyTrigger
import com.example.core.layout.LayoutJson
import com.example.core.layout.ModifierKind
import com.example.core.layout.ModifierMode
import com.example.core.layout.PanelId
import com.example.core.layout.PresentationMode
import com.example.core.layout.SwipeDirection
import com.example.core.layout.TextUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses Robolectric because the codec leans on `org.json` and `android.view.KeyEvent`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayoutJsonTest {

    @Test
    fun `every built-in layout survives a round trip`() {
        BuiltinLayouts.ALL.forEach { original ->
            val reparsed = LayoutJson.parse(LayoutJson.writeString(original))
            assertEquals(original.id, reparsed.id)
            assertEquals(original.name, reparsed.name)
            assertEquals(original.layers.keys, reparsed.layers.keys)
            original.layers.forEach { (name, layer) ->
                val other = reparsed.layers.getValue(name)
                assertEquals("row count of $name in ${original.id}", layer.rows.size, other.rows.size)
                layer.rows.forEachIndexed { index, row ->
                    assertEquals(row.keys.map { it.id }, other.rows[index].keys.map { it.id })
                    assertEquals(row.keys.map { it.tapAction }, other.rows[index].keys.map { it.tapAction })
                }
            }
        }
    }

    @Test
    fun `bare strings are keys that type themselves`() {
        val layout = LayoutJson.parse(
            """{"id":"t","name":"T","layers":{"base":{"rows":[["a","b","c"]]}}}"""
        )
        val keys = layout.base.rows[0].keys
        assertEquals(3, keys.size)
        assertEquals(KeyAction.Text("a"), keys[0].tapAction)
        assertEquals("b", keys[1].label)
    }

    @Test
    fun `a top-level rows array is accepted as the base layer`() {
        val layout = LayoutJson.parse("""{"id":"t","name":"T","rows":[["x"]]}""")
        assertEquals(1, layout.layers.size)
        assertEquals(KeyAction.Text("x"), layout.base.rows[0].keys[0].tapAction)
    }

    @Test
    fun `a labelled key with no explicit tap still types its label`() {
        val layout = LayoutJson.parse(
            """{"id":"t","name":"T","layers":{"base":{"rows":[[{"label":"ż"}]]}}}"""
        )
        assertEquals(KeyAction.Text("ż"), layout.base.rows[0].keys[0].tapAction)
    }

    @Test
    fun `every trigger and a representative action parse`() {
        val layout = LayoutJson.parse(
            """
            {
              "id":"t","name":"T",
              "layers":{"base":{"rows":[[{
                "id":"k","label":"k","repeat":true,
                "tap":{"type":"text","text":"k"},
                "longPress":{"type":"backspace","unit":"word"},
                "doubleTap":{"type":"select","unit":"line"},
                "repeatAction":{"type":"backspace","unit":"character"},
                "swipe":{
                  "up":{"type":"key","code":"KEYCODE_TAB","meta":"ctrl+shift"},
                  "down":{"type":"panel","panel":"clipboard"},
                  "left":{"type":"modifier","kind":"ctrl","mode":"lock"},
                  "up_right":{"type":"presentation","mode":"split"}
                },
                "chords":{"shift":{"type":"clipboard","op":"cut"}},
                "popup":["ą","ä"],
                "indicators":[{"source":"lock:shift","style":"bar_bottom","on":"#FF00FF00"}]
              }]]}}
            }
            """.trimIndent()
        )
        val key = layout.base.rows[0].keys[0]

        assertEquals(KeyAction.Text("k"), key.actionFor(KeyTrigger.Tap))
        assertEquals(KeyAction.Backspace(TextUnit.WORD), key.actionFor(KeyTrigger.LongPress))
        assertEquals(KeyAction.Select(TextUnit.LINE), key.actionFor(KeyTrigger.DoubleTap))
        assertEquals(KeyAction.Backspace(TextUnit.CHARACTER), key.actionFor(KeyTrigger.Repeat))

        val up = key.actionFor(KeyTrigger.Swipe(SwipeDirection.UP)) as KeyAction.SendKey
        assertEquals(android.view.KeyEvent.KEYCODE_TAB, up.keyCode)
        assertTrue(up.metaState and android.view.KeyEvent.META_CTRL_ON != 0)
        assertTrue(up.metaState and android.view.KeyEvent.META_SHIFT_ON != 0)

        assertEquals(
            KeyAction.OpenPanel(PanelId.CLIPBOARD),
            key.actionFor(KeyTrigger.Swipe(SwipeDirection.DOWN))
        )
        assertEquals(
            KeyAction.Modifier(ModifierKind.CTRL, ModifierMode.LOCK),
            key.actionFor(KeyTrigger.Swipe(SwipeDirection.LEFT))
        )
        assertEquals(
            KeyAction.Presentation(PresentationMode.SPLIT),
            key.actionFor(KeyTrigger.Swipe(SwipeDirection.UP_RIGHT))
        )
        assertEquals(
            KeyAction.Clipboard(ClipboardOp.CUT),
            key.actionFor(KeyTrigger.Chord("shift"))
        )

        assertEquals(listOf("ą", "ä"), key.popup)
        assertTrue(key.repeatable)

        val indicator = key.indicators.single()
        assertEquals(IndicatorSource.ModifierLock(ModifierKind.SHIFT), indicator.source)
        assertEquals(IndicatorStyle.BAR_BOTTOM, indicator.style)
        assertEquals(0xFF00FF00L, indicator.onColor)
    }

    @Test
    fun `a nested macro round trips`() {
        val layout = LayoutJson.parse(
            """
            {"id":"t","name":"T","layers":{"base":{"rows":[[{"id":"m","label":"m",
             "tap":{"type":"macro","steps":[
               {"type":"text","text":"("},
               {"type":"text","text":")"},
               {"type":"cursor","direction":"left"}
             ]}}]]}}}
            """.trimIndent()
        )
        val macro = layout.base.rows[0].keys[0].tapAction as KeyAction.Macro
        assertEquals(3, macro.steps.size)

        val reparsed = LayoutJson.parse(LayoutJson.writeString(layout))
        assertEquals(macro, reparsed.base.rows[0].keys[0].tapAction)
    }

    @Test
    fun `absolute bounds and background survive a round trip`() {
        val layout = LayoutJson.parse(
            """
            {"id":"t","name":"T",
             "background":{"image":"skin.png","fit":"cover","opacity":0.8,"sensitivity":"w.png"},
             "layers":{"base":{"rows":[],"free":[
               {"id":"blob","bounds":[0.1,0.2,0.3,0.4],"touch":2.5,"visible":false,
                "tap":{"type":"enter"}}
             ]}}}
            """.trimIndent()
        )
        val key = layout.base.freeKeys.single()
        assertEquals(0.1f, key.bounds!!.left, 0.0001f)
        assertEquals(0.4f, key.bounds!!.bottom, 0.0001f)
        assertEquals(2.5f, key.touchWeight, 0.0001f)
        assertEquals(false, key.visible)
        assertEquals("skin.png", layout.background?.imageFile)
        assertEquals("w.png", layout.background?.sensitivityFile)

        val reparsed = LayoutJson.parse(LayoutJson.writeString(layout))
        assertEquals(key.bounds, reparsed.base.freeKeys.single().bounds)
        assertEquals(layout.background, reparsed.background)
    }

    @Test
    fun `a model's fenced reply is unwrapped before parsing`() {
        val fenced = "Here you go:\n```json\n{\"id\":\"t\",\"name\":\"T\",\"rows\":[[\"a\"]]}\n```\n"
        assertEquals("t", LayoutJson.parse(fenced).id)
    }

    @Test
    fun `unreadable json reports why rather than throwing something opaque`() {
        val error = runCatching { LayoutJson.parse("not json at all") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is LayoutJson.ParseException)
    }

    @Test
    fun `a layout with no layers is rejected`() {
        val error = runCatching { LayoutJson.parse("""{"id":"t","name":"T"}""") }.exceptionOrNull()
        assertTrue(error is LayoutJson.ParseException)
    }

    @Test
    fun `colours parse in the forms people write them`() {
        assertEquals(0xFFAABBCCL, LayoutJson.parseColor("#AABBCC"))
        assertEquals(0x80AABBCCL, LayoutJson.parseColor("#80AABBCC"))
        assertEquals(0xFFAABBCCL, LayoutJson.parseColor("ABC"))
        assertNull(LayoutJson.parseColor("nonsense"))
        assertNull(LayoutJson.parseColor(null))
    }

    @Test
    fun `settings survive a round trip and unknown keys are ignored`() {
        val custom = Settings(
            expertMode = true,
            themeId = "terminal",
            presentation = PresentationMode.SPLIT,
            heightPortrait = 0.41f,
            enabledLayoutIds = listOf("hacker", "numpad"),
            longPressMs = 222L,
            aiEnabled = true,
            aiModel = "some-model",
            asrAlternativeCount = 7,
            indicatorOnColor = 0xFF112233L
        )
        val json = SettingsStore.toJson(custom)
        json.put("a_setting_from_the_future", 42)
        assertEquals(custom, SettingsStore.fromJson(json))
    }

    @Test
    fun `missing settings fall back to defaults rather than to zero`() {
        val decoded = SettingsStore.fromJson(org.json.JSONObject("""{"themeId":"light"}"""))
        assertEquals("light", decoded.themeId)
        assertEquals(Settings().heightPortrait, decoded.heightPortrait, 0.0001f)
        assertEquals(Settings().enabledLayoutIds, decoded.enabledLayoutIds)
        assertEquals(Settings().longPressMs, decoded.longPressMs)
    }
}
