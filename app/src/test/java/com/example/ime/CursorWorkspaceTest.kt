package com.example.ime

import android.graphics.Matrix
import android.graphics.RectF
import android.view.inputmethod.CursorAnchorInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CursorWorkspaceTest {
    @Test fun `editor local position must be transformed into screen coordinates`() {
        val matrix = Matrix().apply { setScale(2f, 2f); postTranslate(40f, 600f) }
        val info = CursorAnchorInfo.Builder().setMatrix(matrix).setInsertionMarkerLocation(20f, 10f, 20f, 30f, 1).build()
        val bounds = CursorWorkspace.focus(info, 200f)!!
        assertEquals(620f, bounds.top, 0.1f)
        assertEquals(660f, bounds.bottom, 0.1f)
        assertEquals(80f, bounds.centerX(), 0.1f)
    }
    @Test fun `floating panel clears the whole field and never goes under navigation bars`() {
        val panel = RectF(0f, 500f, 300f, 800f)
        val editor = RectF(10f, 700f, 290f, 740f)
        val work = RectF(0f, 24f, 400f, 850f)
        assertEquals(112f, CursorWorkspace.shift(panel, editor, work, 12f)!!, 0.1f)
        val moved = RectF(panel).apply { offset(0f, -112f) }
        assertFalse(RectF.intersects(moved, editor))
        assertTrue(moved.top >= work.top && moved.bottom <= work.bottom)
    }
    @Test fun `can move below a field and reports impossible placement without pushing offscreen`() {
        val work = RectF(0f, 20f, 400f, 900f)
        assertEquals(-230f, CursorWorkspace.shift(RectF(0f, 30f, 400f, 330f), RectF(0f, 80f, 400f, 250f), work, 10f)!!, 0.1f)
        assertNull(CursorWorkspace.shift(RectF(0f, 30f, 400f, 830f), RectF(0f, 80f, 400f, 250f), work, 10f))
        assertEquals(0f, CursorWorkspace.shift(RectF(0f, 500f, 100f, 800f), RectF(200f, 600f, 300f, 640f), work, 10f)!!, 0f)
    }
    @Test fun `a cursor escape cannot cover another keyboard element`() {
        val panel = RectF(0f, 400f, 300f, 600f)
        val focus = RectF(0f, 500f, 300f, 550f)
        val workspace = RectF(0f, 20f, 400f, 1000f)
        val above = RectF(0f, 200f, 300f, 410f)
        assertEquals(-160f, CursorWorkspace.shift(panel, focus, workspace, 10f, listOf(above))!!, 0.1f)
        val below = RectF(0f, 650f, 300f, 900f)
        assertNull(CursorWorkspace.shift(panel, focus, workspace, 10f, listOf(above, below)))
    }

}
