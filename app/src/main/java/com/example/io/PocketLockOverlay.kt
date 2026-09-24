package com.example.io

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
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.example.core.config.Knobs
import com.example.core.config.SettingsStore
import com.example.core.config.knobFloat
import com.example.core.config.knobLong
import com.example.core.io.HoldGesture
import com.example.core.io.KeySequence
import com.example.core.io.PocketMode
import com.example.util.AppLogger

/**
 * What a pocket lock needs from whatever is allowed to put a window over other apps.
 *
 * Two things are: the accessibility service, and the "display over other apps"
 * permission. They differ in what else they can do, and those differences are all
 * here rather than scattered through the lock: which window type, how hardware keys
 * reach it, and whether it can tell which app is in front.
 */
interface LockHost {
    val context: Context
    val windowType: Int

    /** Keys arrive by filtering (accessibility) rather than by window focus (overlay). */
    val filtersKeys: Boolean
    fun wantKeys(on: Boolean) = Unit

    /** The app in front, when this host can know it. */
    val foregroundPackage: String? get() = null

    fun screenSize(): Pair<Int, Int>
}

/**
 * The pocket lock itself: a window over everything that takes every touch, keys held
 * while it is up, and the ways out. See [com.example.core.io.PocketMode] for what can
 * and cannot be locked, and why.
 *
 * Nothing about it can outlive its host: if accessibility is switched off, the window
 * goes with it — a lock that outlived its own key is the one failure this must never
 * have. Every number it uses is a knob.
 */
class PocketLockOverlay(private val host: LockHost) : SensorEventListener {

    private val ctx = host.context
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sensors = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val main = Handler(Looper.getMainLooper())

    private var view: LockView? = null
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
        keys = KeySequence(KeySequence.parse(s.pocketUnlockKeys), s.knobLong(Knobs.POCKET_SEQUENCE_MS))
        lockedPackage = host.foregroundPackage
        val (w, h) = host.screenSize()
        val keysWanted = s.pocketBlockKeys || s.pocketUnlockKeys.isNotBlank()

        val v = LockView(ctx, requested == PocketMode.SCREEN)
        // Without key filtering, the window has to take focus to hear the volume keys
        // at all — so it does, and only then.
        val focusable = !host.filtersKeys && keysWanted
        val flags = (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (requested == PocketMode.SCREEN && s.pocketKeepAwake) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)
        val lp = WindowManager.LayoutParams(w, h, host.windowType, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (requested == PocketMode.SCREEN) {
                // Released with the window.
                screenBrightness = s.knobFloat(Knobs.POCKET_BRIGHTNESS)
            }
        }
        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            AppLogger.e(TAG, "could not put the lock up", e)
            return
        }
        view = v
        if (focusable) v.requestFocus()
        if (host.filtersKeys) host.wantKeys(keysWanted)
        watchProximity(true)
        AppLogger.d(TAG, "locked ($requested) over $lockedPackage")
        com.example.engine.EngineRuntime.lockChanged(true)
    }

    fun unlock(reason: String) {
        val v = view ?: return
        view = null
        runCatching { wm.removeView(v) }
        if (host.filtersKeys) host.wantKeys(false)
        watchProximity(false)
        lockedPackage = null
        AppLogger.d(TAG, "unlocked: $reason")
        com.example.engine.EngineRuntime.lockChanged(false)
    }

    /** Hardware keys while locked. True means taken. */
    fun onKey(event: KeyEvent): Boolean {
        if (!locked) return false
        val s = SettingsStore.current
        val name = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "up"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "down"
            else -> KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_").lowercase()
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (keys.press(name, SystemClock.uptimeMillis())) {
                if (covered && s.pocketIgnoreWhenCovered) {
                    view?.hint("Covered — take it out of the pocket to unlock")
                } else {
                    unlock("key sequence")
                }
                return true
            }
            if (!covered) view?.hint(hintText())
        }
        // Media keys from a headset stay working: they are how you pause a talking
        // app without taking the phone out, which is the point of pocketing it.
        return when (event.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> false
            else -> s.pocketBlockKeys
        }
    }

    /**
     * Something else came to the front while locked — most likely a navigation gesture
     * got past the window. The locked app is brought back, at most once per knob-set
     * interval, so two apps can never end up fighting over the screen.
     */
    fun onForeground(pkg: String) {
        val s = SettingsStore.current
        if (!locked || !s.pocketRestoreApp) return
        val original = lockedPackage ?: return
        if (pkg == original || pkg == ctx.packageName || pkg == "com.android.systemui") return
        val now = SystemClock.uptimeMillis()
        if (now - restoring < s.knobLong(Knobs.POCKET_RESTORE_GAP_MS)) return
        restoring = now
        val intent = ctx.packageManager.getLaunchIntentForPackage(original) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { ctx.startActivity(intent) }
            .onFailure { AppLogger.e(TAG, "could not bring $original back", it) }
    }

    private fun hintText(): String {
        val s = SettingsStore.current
        val ways = mutableListOf<String>()
        if (s.pocketUnlockKeys.isNotBlank()) {
            ways += "volume " + KeySequence.parse(s.pocketUnlockKeys)
                .joinToString(" ") { if (it == "up") "+" else if (it == "down") "−" else it }
        }
        if (s.pocketUnlockFingers > 0) ways += "hold ${s.pocketUnlockFingers} fingers ${s.pocketUnlockHoldMs / 1000.0}s"
        if (ways.isEmpty()) ways += "a wire you set up (a phrase, a key)"
        return "Locked — to unlock: " + ways.joinToString(", or ")
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
        val near = SettingsStore.current.knobFloat(Knobs.PROXIMITY_NEAR_CM)
        covered = e.values[0] < e.sensor.maximumRange && e.values[0] < near
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** The window: takes every touch, watches for the hold that unlocks, shows a hint. */
    @SuppressLint("ViewConstructor")
    private inner class LockView(context: Context, private val black: Boolean) : View(context) {
        private val s = SettingsStore.current
        private val hold = HoldGesture(
            fingers = s.pocketUnlockFingers.coerceAtLeast(1),
            holdMs = s.pocketUnlockHoldMs,
            slopPx = s.knobFloat(Knobs.POCKET_HOLD_SLOP_DP) * context.resources.displayMetrics.density
        )
        private var lastCount = 0
        private var lastX = 0f
        private var lastY = 0f
        private var hint: String? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            textSize = 15f * context.resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
        }
        private val recheck = Runnable { evaluate() }
        private val clearHint = Runnable {
            hint = null
            invalidate()
        }

        init {
            setBackgroundColor(if (black) Color.BLACK else Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        fun hint(text: String) {
            val ms = s.knobLong(Knobs.POCKET_HINT_MS)
            if (ms <= 0) return
            hint = text
            invalidate()
            main.removeCallbacks(clearHint)
            main.postDelayed(clearHint, ms)
        }

        /** Keys by focus, for the host that cannot filter them. */
        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            if (!host.filtersKeys && onKey(event)) true else super.dispatchKeyEvent(event)

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

/**
 * Whichever pocket lock can run: the accessibility service's if it is on, otherwise
 * one drawn with the "display over other apps" permission.
 *
 * The second does less — it cannot see which app is in front, so it cannot bring one
 * back past a navigation gesture, and it hears the volume keys only by taking focus —
 * but it needs a permission many people find easier to give. With neither, the caller
 * tells the user what each would give and offers both screens.
 */
object PocketLocks {

    private var overlay: PocketLockOverlay? = null

    val locked: Boolean
        get() = IoAccessibilityService.instance?.pocket?.locked == true || overlay?.locked == true

    fun canOverlay(context: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    fun available(context: Context): Boolean = IoAccessibilityService.instance != null || canOverlay(context)

    /** Locks, or returns false when neither way is granted. */
    fun lock(context: Context, mode: PocketMode): Boolean {
        IoAccessibilityService.instance?.let {
            it.pocket.lock(mode)
            return true
        }
        if (canOverlay(context)) {
            val o = overlay ?: PocketLockOverlay(overlayHost(context.applicationContext)).also { overlay = it }
            o.lock(mode)
            return true
        }
        return false
    }

    fun unlock(reason: String) {
        IoAccessibilityService.instance?.pocket?.unlock(reason)
        overlay?.unlock(reason)
    }

    fun toggle(context: Context, mode: PocketMode): Boolean =
        if (locked) {
            unlock("toggled off")
            true
        } else lock(context, mode)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun overlayHost(app: Context) = object : LockHost {
        override val context: Context = app

        @Suppress("DEPRECATION")
        override val windowType: Int =
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE

        override val filtersKeys: Boolean = false

        override fun screenSize(): Pair<Int, Int> {
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.maximumWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val m = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(m)
                m.widthPixels to m.heightPixels
            }
        }
    }
}
