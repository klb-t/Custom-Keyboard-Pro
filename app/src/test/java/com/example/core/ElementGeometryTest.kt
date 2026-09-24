package com.example.core

import com.example.core.layout.ElementGeometry
import com.example.core.layout.LayoutJson
import com.example.core.layout.NormRect
import com.example.core.layout.WorkbenchLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where floating pieces land. The failures these guard against were all seen on a
 * phone: a numeric block lying across the keyboard in landscape, an Escape key
 * stretched into a bar, a piece that moved when the phone turned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ElementGeometryTest {

    private val numblock = NormRect(0.58f, 0.26f, 0.99f, 0.56f)
    private val escape = NormRect(0.02f, 0.05f, 0.17f, 0.115f)

    private fun near(expected: Float, actual: Float, tolerance: Float = 0.5f) =
        assertEquals(expected, actual, tolerance)

    @Test
    fun `in the orientation it was drawn for, a piece lands exactly where it was drawn`() {
        val box = ElementGeometry.resolve(numblock, null, 400f, 800f)
        near(0.58f * 400f, box.left)
        near(0.26f * 800f, box.top)
        near(0.99f * 400f, box.right)
        near(0.56f * 800f, box.bottom)
    }

    @Test
    fun `turning the phone keeps a key's shape instead of stretching it into a bar`() {
        val portrait = ElementGeometry.resolve(escape, null, 400f, 800f)
        val landscape = ElementGeometry.resolve(escape, null, 800f, 400f)
        near(portrait.width, landscape.width)
        near(portrait.height, landscape.height)
    }

    @Test
    fun `a corner stays a corner in either orientation`() {
        val landscape = ElementGeometry.resolve(escape, null, 800f, 400f)
        assertTrue("left edge drifted: ${landscape.left}", landscape.left < 0.05f * 800f)
        assertTrue("top edge drifted: ${landscape.top}", landscape.top < 0.08f * 400f)
    }

    @Test
    fun `a piece that does not fit above the docked panel shrinks instead of covering it`() {
        // Landscape: the docked panel takes the bottom 250 of 400.
        val ceiling = 150f
        val box = ElementGeometry.resolve(numblock, null, 800f, 400f, ceiling = ceiling)
        assertTrue("overlaps the keyboard: bottom ${box.bottom} > $ceiling", box.bottom <= ceiling + 0.5f)
        // Same shape as drawn, just smaller.
        val drawn = ElementGeometry.resolve(numblock, null, 400f, 800f)
        near(drawn.width / drawn.height, box.width / box.height, 0.01f)
    }

    @Test
    fun `a piece allowed over the panel is left its full size`() {
        val box = ElementGeometry.resolve(numblock, null, 800f, 400f, ceiling = null)
        near(0.41f * 400f, box.width)
    }

    @Test
    fun `below the smallest useful size it overlaps rather than vanishing`() {
        val box = ElementGeometry.resolve(numblock, null, 800f, 400f, ceiling = 10f)
        val full = 0.30f * 800f
        assertTrue("shrunk past the floor: ${box.height}", box.height >= full * ElementGeometry.MIN_FIT - 0.5f)
        assertTrue(box.top >= 0f)
    }

    @Test
    fun `a drag moves the piece by exactly the drag`() {
        val start = ElementGeometry.resolve(numblock, null, 400f, 800f)
        val pose = ElementGeometry.dragged(start, -50f, 30f, 400f, 800f, scale = 1f)
        val moved = ElementGeometry.resolve(numblock, pose, 400f, 800f)
        near(start.left - 50f, moved.left)
        near(start.top + 30f, moved.top)
    }

    @Test
    fun `a piece dragged into a corner is still in that corner after turning the phone`() {
        val start = ElementGeometry.resolve(numblock, null, 400f, 800f)
        val pose = ElementGeometry.dragged(start, 10_000f, 10_000f, 400f, 800f, scale = 1f)
        val turned = ElementGeometry.resolve(numblock, pose, 800f, 400f)
        near(800f, turned.right)
        near(400f, turned.bottom)
    }

    @Test
    fun `dragging past the docked panel stores no movement that does not show`() {
        val ceiling = 500f
        val start = ElementGeometry.resolve(numblock, null, 400f, 800f, ceiling = ceiling)
        val pushed = ElementGeometry.dragged(start, 0f, 1_000f, 400f, 800f, 1f, ceiling)
        val atBottom = ElementGeometry.resolve(numblock, pushed, 400f, 800f, ceiling)
        near(ceiling, atBottom.bottom)
        // One step back up moves it at once.
        val back = ElementGeometry.dragged(atBottom, 0f, -20f, 400f, 800f, 1f, ceiling)
        val raised = ElementGeometry.resolve(numblock, back, 400f, 800f, ceiling)
        near(ceiling - 20f, raised.bottom)
    }

    @Test
    fun `resizing follows the finger and stays within bounds`() {
        val start = ElementGeometry.resolve(numblock, null, 400f, 800f)
        val pose = ElementGeometry.resized(ElementGeometry.poseOf(numblock), start, start.width * 0.5f)
        near(1.5f, pose.scale, 0.01f)
        val tiny = ElementGeometry.resized(ElementGeometry.poseOf(numblock), start, -10_000f)
        near(ElementGeometry.MIN_SCALE, tiny.scale, 0.001f)
    }

    @Test
    fun `stored poses survive a round trip and junk is ignored`() {
        val poses = mapOf(
            ElementGeometry.key("workbench", "numblock") to ElementGeometry.Pose(0.2f, 0.7f, 1.3f)
        )
        assertEquals(poses, ElementGeometry.parse(ElementGeometry.write(poses)))
        assertEquals(emptyMap<String, ElementGeometry.Pose>(), ElementGeometry.parse("not json"))
        assertEquals(emptyMap<String, ElementGeometry.Pose>(), ElementGeometry.parse(""))
    }

    @Test
    fun `an element that may cover the panel says so after a round trip`() {
        val original = WorkbenchLayout.WORKBENCH.copy(
            elements = WorkbenchLayout.WORKBENCH.elements.map {
                if (it.id == "escape") it.copy(overlapsPanel = true) else it
            }
        )
        val reparsed = LayoutJson.parse(LayoutJson.writeString(original))
        assertEquals(true, reparsed.elements.first { it.id == "escape" }.overlapsPanel)
        assertEquals(false, reparsed.elements.first { it.id == "numblock" }.overlapsPanel)
    }
}
