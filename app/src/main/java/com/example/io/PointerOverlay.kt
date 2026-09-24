package com.example.io

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.core.config.knob

/**
 * A mouse pointer drawn over everything, moved from the keyboard's trackpad.
 *
 * For the thing a phone does not have: a remote desktop, a web page built for a
 * mouse, a game's menu with targets smaller than a fingertip. The pointer is only a
 * picture — it takes no touches — and a click is a tap placed exactly at its tip, so
 * whatever is under it receives an ordinary touch and needs to know nothing about us.
 *
 * Needs the accessibility service because only it may draw over other apps without
 * a second special permission, and only it may place a touch.
 */
class PointerOverlay(private val service: IoAccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** Where the tip is, in screen pixels. */
    var x: Float = -1f
        private set
    var y: Float = -1f
        private set

    val visible: Boolean get() = view != null

    fun show() {
        if (view != null) return
        val (w, h) = service.screenSize()
        if (x < 0f || y < 0f) {
            x = w / 2f
            y = h / 3f
        }
        val size = (26 * density).toInt()
        val v = PointerView(service)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = this@PointerOverlay.x.toInt()
            this.y = this@PointerOverlay.y.toInt()
        }
        wm.addView(v, lp)
        view = v
        params = lp
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
    }

    fun toggle() = if (visible) hide() else show()

    fun moveBy(dx: Float, dy: Float) {
        if (!visible) show()
        val (w, h) = service.screenSize()
        x = (x + dx).coerceIn(0f, (w - 1).toFloat())
        y = (y + dy).coerceIn(0f, (h - 1).toFloat())
        val lp = params ?: return
        lp.x = x.toInt()
        lp.y = y.toInt()
        view?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    fun click(): Boolean = visible && service.press(x, y, 60)

    fun longClick(): Boolean = visible && service.press(
        x, y,
        com.example.core.config.SettingsStore.current.knob(com.example.core.config.Knobs.POINTER_LONG_MS).toLong()
    )

    /** A two-finger drag on the trackpad: the page under the pointer moves by [dy]. */
    fun scrollBy(dx: Float, dy: Float): Boolean {
        if (!visible) return false
        val (w, h) = service.screenSize()
        val toX = (x + dx).coerceIn(0f, (w - 1).toFloat())
        val toY = (y + dy).coerceIn(0f, (h - 1).toFloat())
        return service.swipe(x, y, toX, toY, 160)
    }

    private class PointerView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2f * context.resources.displayMetrics.density
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = width.toFloat()
            // The tip is the view's own corner, which is the point that gets clicked.
            val arrow = Path().apply {
                moveTo(1f, 1f)
                lineTo(1f, s * 0.78f)
                lineTo(s * 0.26f, s * 0.58f)
                lineTo(s * 0.44f, s * 0.96f)
                lineTo(s * 0.58f, s * 0.9f)
                lineTo(s * 0.41f, s * 0.53f)
                lineTo(s * 0.72f, s * 0.53f)
                close()
            }
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, edge)
        }
    }
}
