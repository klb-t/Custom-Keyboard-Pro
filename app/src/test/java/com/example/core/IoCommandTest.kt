package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.caps.Abilities
import com.example.core.io.Command
import com.example.core.io.NodeFacts
import com.example.core.io.NodeQuery
import com.example.core.io.Sweep
import com.example.core.io.Verbs
import com.example.core.layout.KeyAction
import com.example.core.layout.LayoutJson
import com.example.io.Performer
import com.example.io.PerformerHost
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
 * Actions beyond the text field: the line they are written as, the catalogue they
 * come from, and the rule that a key bound to one never silently does nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IoCommandTest {

    @Test
    fun `a line reads as a verb and its arguments in the verb's own order`() {
        val c = Command.parse("tap 0.5 0.8")!!
        assertEquals("tap", c.verb)
        assertEquals(0.5f, c.float("x")!!, 0.0001f)
        assertEquals(0.8f, c.float("y")!!, 0.0001f)
    }

    @Test
    fun `a named argument wins and the rest keep their positions`() {
        val c = Command.parse("tap y=0.2 0.7")!!
        assertEquals("0.7", c.arg("x"))
        assertEquals("0.2", c.arg("y"))
    }

    @Test
    fun `a quoted label stays one argument`() {
        val c = Command.parse("click text=\"Select all\" index=1")!!
        assertEquals("Select all", c.arg("text"))
        assertEquals(1, c.int("index"))
    }

    @Test
    fun `a link with an equals sign in it is a link, not a named argument`() {
        val c = Command.parse("open https://example.com/?a=b")!!
        assertEquals("https://example.com/?a=b", c.arg("target"))
    }

    @Test
    fun `a comma decimal is read the way it was meant`() {
        assertEquals(0.25f, Command.parse("tap 0,25 0,5")!!.float("x")!!, 0.0001f)
    }

    @Test
    fun `writing and reading back gives the same command, whatever is in it`() {
        val tricky = Command(
            "click",
            positional = listOf("two words"),
            named = mapOf("text" to "say \"hi\" = ok", "desc" to "back\\slash")
        )
        assertEquals(tricky, Command.parse(tricky.write()))
    }

    @Test
    fun `a blank line is no command, and verbs are case blind`() {
        assertNull(Command.parse("   "))
        assertEquals("back", Command.parse("BACK")!!.verb)
    }

    @Test
    fun `a key bound to an action survives the layout file`() {
        val action = LayoutJson.parseAction("do:sweep mode=longpress pages=3")
        assertTrue(action is KeyAction.Do)
        val again = LayoutJson.parseAction(LayoutJson.writeAction(action!!))
        assertEquals(action, again)
        assertEquals("longpress", (again as KeyAction.Do).command.arg("mode"))
    }

    @Test
    fun `the catalogue is well formed`() {
        assertEquals(Verbs.ALL.size, Verbs.ALL.map { it.id }.toSet().size)
        Verbs.ALL.forEach { v ->
            assertFalse("${v.id} has no label", v.label.isBlank())
            assertFalse("${v.id} has no glyph", v.glyph.isBlank())
            assertFalse("${v.id} does not say what happens without its access", v.without.isBlank())
            assertEquals("${v.id} names a parameter twice", v.params.size, v.params.map { it.name }.toSet().size)
            v.ability?.let { assertNotNull("${v.id} needs an ability that does not exist: $it", Abilities.byId(it)) }
            assertEquals("verb ids are written lower case", v.id.lowercase(), v.id)
        }
    }

    @Test
    fun `an empty query matches nothing rather than everything`() {
        // "click" with nothing to look for pressing the first thing on screen would
        // be the worst possible reading of a typo.
        assertFalse(NodeQuery().matches(NodeFacts(text = "Delete account", clickable = true)))
    }

    @Test
    fun `a query matches by text, description and the tail of an id`() {
        val node = NodeFacts(text = "Select all", desc = "Select every item", viewId = "com.app:id/select_all")
        assertTrue(NodeQuery(text = "select").matches(node))
        assertTrue(NodeQuery(id = "select_all").matches(node))
        assertTrue(NodeQuery(desc = "every").matches(node))
        assertFalse(NodeQuery(text = "select", id = "other").matches(node))
    }

    @Test
    fun `a sweep starts a selection once and then taps`() {
        val items = (1..3).map { NodeFacts(text = "post $it", row = it) }
        val seen = mutableSetOf<String>()
        val first = Sweep.plan(Sweep.Mode.LONGPRESS, items, seen, started = false)
        assertEquals(listOf(Sweep.Act.LONG_CLICK, Sweep.Act.CLICK, Sweep.Act.CLICK), first.map { it.act })
    }

    @Test
    fun `an item scrolled back into view is not done twice, which would undo it`() {
        val seen = mutableSetOf<String>()
        val page1 = (1..3).map { NodeFacts(text = "post $it", row = it, top = it * 100) }
        Sweep.plan(Sweep.Mode.LONGPRESS, page1, seen, started = false)
        // After scrolling, post 3 is at the top of the screen and posts 4-5 are new.
        val page2 = (3..5).map { NodeFacts(text = "post $it", row = it, top = (it - 3) * 100) }
        val steps = Sweep.plan(Sweep.Mode.LONGPRESS, page2, seen, started = true)
        assertEquals(listOf(1, 2), steps.map { it.index })
        assertTrue(steps.all { it.act == Sweep.Act.CLICK })
    }

    @Test
    fun `ticking leaves ticked boxes alone`() {
        val boxes = listOf(
            NodeFacts(text = "a", checkable = true, checked = true),
            NodeFacts(text = "b", checkable = true, checked = false),
            NodeFacts(text = "c", checkable = false)
        )
        val steps = Sweep.plan(Sweep.Mode.CHECKBOXES, boxes, mutableSetOf(), started = false)
        assertEquals(listOf(1), steps.map { it.index })
    }

    @Test
    fun `picture-only rows on one screen are still told apart`() {
        val rows = listOf(NodeFacts(top = 0), NodeFacts(top = 200), NodeFacts(top = 400))
        val steps = Sweep.plan(Sweep.Mode.TAP, rows, mutableSetOf(), started = false)
        assertEquals(3, steps.size)
    }

    private class Recorder : PerformerHost {
        override val context: Context get() = ApplicationProvider.getApplicationContext()
        val notices = mutableListOf<String>()
        val keys = mutableListOf<Int>()
        override fun sendKey(keyCode: Int) {
            keys += keyCode
        }
        override fun nearbyText(): String = ""
        override fun commit(text: String) = Unit
        override fun copy(text: String, label: String) = Unit
        override fun offerToAi(text: String) = Unit
        override fun notice(text: String, actionLabel: String?, action: (() -> Unit)?) {
            notices += text
        }
        override fun record(state: String, name: String) {
            notices += "record $state $name"
        }
        override fun play(name: String, times: Int) {
            notices += "play $name $times"
        }
    }

    @Test
    fun `without accessibility every action that needs it still answers`() {
        // The service is not running in a test, which is exactly the case under test:
        // a key pressed without the access it needs must fall back or explain.
        Verbs.ALL.filter { it.needsAccessibility }.forEach { spec ->
            val host = Recorder()
            Performer(host).run(Command(spec.id))
            assertTrue(
                "'${spec.id}' did nothing visible without accessibility",
                host.notices.isNotEmpty() || host.keys.isNotEmpty()
            )
        }
    }

    @Test
    fun `back without accessibility is still the Back key`() {
        val host = Recorder()
        Performer(host).run(Command("back"))
        assertEquals(listOf(android.view.KeyEvent.KEYCODE_BACK), host.keys)
    }

    @Test
    fun `no listed action falls through to the unwired branch`() {
        Verbs.ALL.filterNot { it.needsAccessibility }.forEach { spec ->
            val host = Recorder()
            Performer(host).run(Command(spec.id))
            assertTrue(
                "'${spec.id}' is listed but not wired up",
                host.notices.none { it.contains("not wired") }
            )
        }
    }

    @Test
    fun `an unknown verb says so`() {
        val host = Recorder()
        Performer(host).run(Command("teleport"))
        assertTrue(host.notices.single().contains("teleport"))
    }
}
