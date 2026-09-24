package com.example.io

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.core.io.NodeFacts
import com.example.core.io.NodeQuery
import com.example.core.io.Sweep
import com.example.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The part of the keyboard that can see and touch the rest of the phone.
 *
 * An input method reaches exactly one thing: the field it is typing into. Everything
 * past that — pressing Back, reading the post being replied to, ticking forty items
 * in a feed, a pointer for a remote desktop — goes through here, and only once the
 * user has switched it on in the system's accessibility settings. Nothing in the
 * keyboard depends on it: every verb that uses it has its own answer for when it is
 * off (see [com.example.core.io.VerbSpec.without]).
 *
 * Deliberately passive. It listens to nothing but which app is in front, keeps no
 * record of what it has seen, and reads the screen only when a verb asks — the whole
 * of what it could do is not a reason to do any of it unasked.
 */
class IoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "IO.access"

        @Volatile
        var instance: IoAccessibilityService? = null
            private set

        private val _running = MutableStateFlow(false)

        /** Bound and usable right now. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /**
         * Switched on in system settings — which can be true a moment before the
         * system has bound it, and is the question a settings screen is asking.
         */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, IoAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val main = Handler(Looper.getMainLooper())

    /** The app in front, as last announced. Ours and the keyboard's are never it. */
    @Volatile
    var foregroundPackage: String? = null
        private set

    val pointer: PointerOverlay by lazy { PointerOverlay(this) }

    val pocket: PocketLockOverlay by lazy { PocketLockOverlay(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The service can start before anything else in the process has read the
        // settings — at boot, with the keyboard not yet opened.
        com.example.core.config.SettingsStore.init(this)
        instance = this
        _running.value = true
        AppLogger.d(TAG, "connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        if (instance === this) instance = null
        _running.value = false
        runCatching { pointer.hide() }
        // A lock that outlived its service would have no way out. It goes first.
        runCatching { pocket.unlock("service stopped") }
        AppLogger.d(TAG, "disconnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        // The shade is not "the app in front": locking from a quick settings tile
        // must lock over the app underneath it.
        if (pkg != packageName && pkg != "com.android.systemui") {
            foregroundPackage = pkg
            pocket.onForeground(pkg)
        }
    }

    /** Only asked for while the pocket lock is up; see [PocketLockOverlay]. */
    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        val e = event ?: return false
        return runCatching { pocket.onKey(e) }.getOrDefault(false)
    }

    override fun onInterrupt() = Unit

    // ------------------------------------------------------------------
    // The screen
    // ------------------------------------------------------------------

    /** Screen size in pixels, the whole display, which is what gestures are placed in. */
    fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

    /**
     * The roots worth reading: the app in front, not the keyboard and not us.
     *
     * The active window is the answer almost always. The list of windows is the
     * fallback for the moments it is not — a dialog, split screen, the shade.
     */
    private fun roots(): List<AccessibilityNodeInfo> {
        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() != packageName) return listOf(active)
        return runCatching {
            windows.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    it.type == AccessibilityWindowInfo.TYPE_SYSTEM
            }.mapNotNull { it.root }.filter { it.packageName?.toString() != packageName }
        }.getOrDefault(emptyList())
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null || depth > 64) return
        visit(node)
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, visit)
    }

    private fun facts(n: AccessibilityNodeInfo): NodeFacts {
        val r = Rect().also { n.getBoundsInScreen(it) }
        return NodeFacts(
            text = n.text?.toString(),
            desc = n.contentDescription?.toString(),
            viewId = n.viewIdResourceName,
            className = n.className?.toString(),
            clickable = n.isClickable,
            longClickable = n.isLongClickable,
            checkable = n.isCheckable,
            checked = n.isChecked,
            row = n.collectionItemInfo?.rowIndex,
            top = r.top,
            left = r.left
        )
    }

    /** Every piece of text on screen, top to bottom, without repeating itself. */
    fun screenText(maxChars: Int = 20_000): String {
        val lines = LinkedHashSet<String>()
        roots().forEach { root ->
            walk(root) { n ->
                if (!n.isVisibleToUser) return@walk
                n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += it }
                    ?: n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += it }
            }
        }
        val out = StringBuilder()
        for (line in lines) {
            if (out.length + line.length + 1 > maxChars) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }

    private fun find(query: NodeQuery): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        roots().forEach { root ->
            walk(root) { n -> if (n.isVisibleToUser && query.matches(facts(n))) found += n }
        }
        return found
    }

    /** The nearest node that will actually take the action: labels are rarely buttons. */
    private fun actionable(n: AccessibilityNodeInfo, long: Boolean): AccessibilityNodeInfo? {
        var at: AccessibilityNodeInfo? = n
        var hops = 0
        while (at != null && hops < 8) {
            if (if (long) at.isLongClickable else at.isClickable) return at
            at = at.parent
            hops++
        }
        return null
    }

    /**
     * Presses the [NodeQuery.index]th match. Falls back to touching its middle when
     * nothing up its tree admits to being pressable — some apps draw buttons that
     * the tree never marks as such, and a finger works on them anyway.
     */
    fun press(query: NodeQuery, long: Boolean): Boolean {
        val target = find(query).getOrNull(query.index) ?: return false
        return act(target, long)
    }

    private fun act(target: AccessibilityNodeInfo, long: Boolean): Boolean {
        actionable(target, long)?.let { node ->
            val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            if (node.performAction(action)) return true
        }
        val r = Rect().also { target.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        return if (long) press(r.exactCenterX(), r.exactCenterY(), 650) else press(r.exactCenterX(), r.exactCenterY(), 60)
    }

    /** The biggest scrollable thing on screen: the feed, the page, the list. */
    private fun mainScrollable(): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        roots().forEach { root ->
            walk(root) { n ->
                if (!n.isScrollable || !n.isVisibleToUser) return@walk
                val r = Rect().also { n.getBoundsInScreen(it) }
                val area = r.width() * r.height()
                if (area > bestArea) {
                    bestArea = area
                    best = n
                }
            }
        }
        return best
    }

    /** Scrolls the main list; a swipe when the list will not take the action. */
    fun scroll(direction: String): Boolean {
        val forward = direction == "down" || direction == "right"
        val node = mainScrollable()
        if (node != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.performAction(action)) return true
        }
        val (w, h) = screenSize()
        return when (direction) {
            "up" -> swipe(w / 2f, h * 0.35f, w / 2f, h * 0.75f, 280)
            "left" -> swipe(w * 0.25f, h / 2f, w * 0.8f, h / 2f, 280)
            "right" -> swipe(w * 0.8f, h / 2f, w * 0.25f, h / 2f, 280)
            else -> swipe(w / 2f, h * 0.75f, w / 2f, h * 0.35f, 280)
        }
    }

    // ------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------

    fun press(x: Float, y: Float, ms: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return gesture(path, ms)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return gesture(path, ms)
    }

    private fun gesture(path: Path, ms: Long, done: ((Boolean) -> Unit)? = null): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, ms.coerceIn(1, 59_000))
        val description = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(description, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                done?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                done?.invoke(false)
            }
        }, null)
    }

    // ------------------------------------------------------------------
    // Many items
    // ------------------------------------------------------------------

    /**
     * Goes down the list doing [mode] to every item, for [pages] screens.
     *
     * Paced rather than instant: apps animate selection and load the next page after
     * a scroll, and a sweep that outruns them presses things that are not there yet.
     * The count reported is of presses that the app accepted.
     */
    fun sweep(mode: Sweep.Mode, pages: Int, query: NodeQuery, done: (Int) -> Unit) {
        val seen = mutableSetOf<String>()
        var count = 0
        var started = false
        var page = 0

        fun candidates(): List<AccessibilityNodeInfo> = when (mode) {
            Sweep.Mode.MATCH -> find(query)
            Sweep.Mode.CHECKBOXES -> {
                val out = mutableListOf<AccessibilityNodeInfo>()
                roots().forEach { r -> walk(r) { n -> if (n.isVisibleToUser && n.isCheckable) out += n } }
                out
            }
            else -> {
                val list = mainScrollable()
                if (list == null) emptyList()
                else (0 until list.childCount).mapNotNull { list.getChild(it) }.filter { it.isVisibleToUser }
            }
        }

        fun runPage() {
            val nodes = candidates()
            val steps = Sweep.plan(mode, nodes.map { facts(it) }, seen, started)
            steps.forEach { step ->
                if (act(nodes[step.index], step.act == Sweep.Act.LONG_CLICK)) {
                    count++
                    started = true
                }
            }
            page++
            if (page >= pages.coerceIn(1, 200)) {
                main.postDelayed({ done(count) }, 150)
                return
            }
            if (!scroll("down")) {
                main.postDelayed({ done(count) }, 150)
                return
            }
            main.postDelayed({ runPage() }, 700)
        }

        runPage()
    }
}
