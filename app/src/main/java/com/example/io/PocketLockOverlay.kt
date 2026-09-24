package com.example.io

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.example.core.config.SettingsStore
import com.example.core.io.HoldGesture
import com.example.core.io.KeySequence
import com.example.core.io.PocketMode
import com.example.util.AppLogger

/**
 * The pocket lock itself: a window over everything that takes every touch, keys
 * filtered while it is up, and the ways out. See [com.example.core.io.PocketMode]
 * for what can and cannot be locked, and why.
 *
 * Nothing about it survives the service: if accessibility is switched off, or the
 * service dies, the window goes with it — a lock that could outlive its own key is
 * the one failure this must never have.
 */
class PocketLockOverlay(private val service: IoAccessibilityService) : SensorEventListener {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sensors = service.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val main = Handler(Looper.getMainLooper())

    private var view: LockView? = null
    private var mode: PocketMode = PocketMode.TOUCH
    private var keys = KeySequence(emptyList())
    private var lockedPackage: String? = null
    private var restoring = 0L

    /** The proximity sensor says something is right over the screen: a pocket. */
    @Volatile
    private var covered = false

    val locked: Boolean get() = view != null

    fun toggle(mode: PocketMode) = if (locked) unlock("toggled off") else lock(mode)

    fun lock(requested: PocketMode) {
        if (locked) return
        val s = SettingsStore.current
        mode = requested
        keys = KeySequence(KeySequence.parse(s.pocketUnlockKeys), 3000)
        lockedPackage = service.foregroundPackage
        val (w, h) = service.screenSize()

        val v = LockView(service, requested == PocketMode.SCREEN)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (requested == PocketMode.SCREEN && s.pocketKeepAwake) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (requested == PocketMode.SCREEN) {
                // The lowest the panel goes. Released with the window.
                screenBrightness = 0.01f
            }
        }
        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            AppLogger.e(TAG, "could not put the lock up", e)
            return
        }
        view = v
        filterKeys(s.pocketBlockKeys)
        watchProximity(true)
        AppLogger.d(TAG, "locked ($requested) over $lockedPackage")
    }

    fun unlock(reason: String) {
        val v = view ?: return
        view = null
        runCatching { wm.removeView(v) }
        filterKeys(false)
        watchProximity(false)
        lockedPackage = null
        AppLogger.d(TAG, "unlocked: $reason")
    }

    /** Hardware keys while locked. True means taken. */
    fun onKey(event: KeyEvent): Boolean {
        if (!locked) return false
        val name = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "up"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "down"
            else -> KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_").lowercase()
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (keys.press(name, SystemClock.uptimeMillis())) {
                if (covered && SettingsStore.current.pocketIgnoreWhenCovered) {
                    view?.hint("Covered — take it out of the pocket to unlock")
                } else {
                    unlock("key sequence")
                }
                return true
            }
            view?.hint(hintText())
        }
        // Media keys from a headset stay working: they are how you pause a talking
        // app without taking the phone out, which is the point of pocketing it.
        return when (event.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> false
            else -> true
        }
    }

    /**
     * Something else came to the front while locked — most likely a navigation gesture
     * got past the window. The locked app is brought back, at most every two seconds
     * so two apps can never end up fighting over the screen.
     */
    fun onForeground(pkg: String) {
        if (!locked || !SettingsStore.current.pocketRestoreApp) return
        val original = lockedPackage ?: return
        if (pkg == original || pkg == service.packageName || pkg == "com.android.systemui") return
        val now = SystemClock.uptimeMillis()
        if (now - restoring < 2000) return
        restoring = now
        val intent = service.packageManager.getLaunchIntentForPackage(original) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { service.startActivity(intent) }
            .onFailure { AppLogger.e(TAG, "could not bring $original back", it) }
    }

    private fun hintText(): String {
        val s = SettingsStore.current
        val ways = mutableListOf<String>()
        if (s.pocketUnlockKeys.isNotBlank()) ways += "volume ${KeySequence.parse(s.pocketUnlockKeys).joinToString(" ") { if (it == "up") "+" else if (it == "down") "−" else it }}"
        if (s.pocketUnlockFingers > 0) ways += "hold ${s.pocketUnlockFingers} fingers ${s.pocketUnlockHoldMs / 1000.0}s"
        return "Locked — to unlock: " + ways.joinToString(", or ")
    }

    /** Key filtering is asked for only while locked; the rest of the time keys are none of our business. */
    private fun filterKeys(on: Boolean) {
        val info = service.serviceInfo ?: return
        info.flags = if (on) info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        else info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        runCatching { service.serviceInfo = info }
    }

    private fun watchProximity(on: Boolean) {
        val sm = sensors ?: return
        if (on) {
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        } else {
            sm.unregisterListener(this)
            covered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_PROXIMITY) return
        covered = e.values[0] < e.sensor.maximumRange && e.values[0] < 3f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** The window: takes every touch, watches for the hold that unlocks, shows a hint. */
    @SuppressLint("ViewConstructor")
    private inner class LockView(context: Context, private val black: Boolean) : View(context) {
        private val s = SettingsStore.current
        private val hold = HoldGesture(
            fingers = s.pocketUnlockFingers.coerceAtLeast(1),
            holdMs = s.pocketUnlockHoldMs,
            slopPx = 40f * context.resources.displayMetrics.density
        )
        private var lastCount = 0
        private var lastX = 0f
        private var lastY = 0f
        private var hint: String? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            textSize = 15f * context.resources.displayMetrics.scaledDensity
            textAlign = Paint.Align.CENTER
        }
        private val recheck = Runnable { evaluate() }

        init {
            setBackgroundColor(if (black) Color.BLACK else Color.TRANSPARENT)
        }

        fun hint(text: String) {
            hint = text
            invalidate()
            main.removeCallbacks(clearHint)
            main.postDelayed(clearHint, 2500)
        }

        private val clearHint = Runnable {
            hint = null
            invalidate()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val fingers = s.pocketUnlockFingers
            if (fingers > 0) {
                val up = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
                lastCount = if (up) 0 else event.pointerCount -
                    (if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) 1 else 0)
                var sx = 0f
                var sy = 0f
                for (i in 0 until event.pointerCount) {
                    sx += event.getX(i)
                    sy += event.getY(i)
                }
                lastX = sx / event.pointerCount
                lastY = sy / event.pointerCount
                evaluate()
                // A finger held perfectly still sends nothing, so the hold is also
                // checked on a timer rather than only when something moves.
                main.removeCallbacks(recheck)
                if (lastCount >= fingers) main.postDelayed(recheck, s.pocketUnlockHoldMs + 30)
            }
            // Not while covered: fabric brushing the glass would light the text up
            // again and again, in a pocket, for nobody.
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !covered) hint(hintText())
            return true
        }

        private fun evaluate() {
            if (!locked) return
            if (hold.update(lastCount, lastX, lastY, SystemClock.uptimeMillis())) {
                if (covered && s.pocketIgnoreWhenCovered) {
                    hint("Covered — take it out of the pocket to unlock")
                } else {
                    unlock("hold")
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val text = hint ?: return
            if (!black) {
                // Readable over whatever the app is showing.
                canvas.drawColor(Color.argb(110, 0, 0, 0))
            }
            canvas.drawText(text, width / 2f, height / 2f, paint)
        }

        override fun onDetachedFromWindow() {
            main.removeCallbacks(recheck)
            main.removeCallbacks(clearHint)
            super.onDetachedFromWindow()
        }
    }

    companion object {
        private const val TAG = "IO.pocket"
    }
}
