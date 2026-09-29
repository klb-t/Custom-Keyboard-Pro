package com.example.io.capture

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.example.core.capture.CaptureDriver
import com.example.core.capture.CaptureOptions
import com.example.core.capture.CaptureSession
import com.example.core.capture.ConversationArchive
import com.example.io.IoAccessibilityService
import com.example.io.TileActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The exported link can open consent only. No intent extra can start capture or request output. */
class ConversationCaptureEntryActivity : Activity() {
    private var openPanel = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (IoAccessibilityService.instance == null) {
            AlertDialog.Builder(this).setTitle("Capture conversation")
                .setMessage("Enable IO Matrix accessibility to read exposed text and optionally scroll/expand a conversation. It is not needed for typing. Return to the conversation and start this action again afterwards.")
                .setPositiveButton("Accessibility settings") { _, _ -> startActivity(IoAccessibilityService.settingsIntent()); finish() }
                .setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            openPanel = true
            finish()
        }
    }
    override fun onStop() {
        super.onStop()
        if (openPanel && isFinishing) {
            openPanel = false
            Handler(Looper.getMainLooper()).postDelayed({
                IoAccessibilityService.instance?.let { ConversationCaptureUi.open(it) }
            }, 500)
        }
    }
}

class ConversationCaptureTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = "Capture conversation"; updateTile() }
    }
    override fun onClick() {
        super.onClick()
        if (isLocked) { unlockAndRun { launch() }; return }
        launch()
    }
    private fun launch() = TileActions.openAndCollapse(this,
        Intent(this, ConversationCaptureEntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 7411)
}

internal object ConversationCaptureUi {
    private var panel: CapturePanel? = null
    var result: ConversationArchive? = null
    fun open(service: IoAccessibilityService) {
        if (result != null && panel == null) {
            runCatching { service.startActivity(Intent(service, ConversationCaptureReviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Toast.makeText(service, "A previous capture remains in memory; review could not be opened", Toast.LENGTH_LONG).show() }
            return
        }
        if (panel != null) {
            Toast.makeText(service, "Capture controls are already open", Toast.LENGTH_SHORT).show()
            return
        }
        val next = CapturePanel(service) { panel = null }
        runCatching { next.show(); panel = next }.onFailure {
            next.dispose()
            Toast.makeText(service, "Cannot show capture controls. Return to the conversation and try again.", Toast.LENGTH_LONG).show()
        }
    }
}

/** A visible, non-focusable, draggable control; polling exists only between Start and Stop. */
private class CapturePanel(private val service: IoAccessibilityService, private val closed: () -> Unit) {
    private val context = ContextThemeWrapper(service, android.R.style.Theme_Material_Light)
    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val source = AccessibilityCaptureSource(service)
    private val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setBackgroundColor(0xfffafafa.toInt())
        elevation = dp(8).toFloat()
    }
    private var safeTop = dp(32)
    private var safeBottom = dp(48)
    private var safeLeft = 0
    private var safeRight = 0
    private val shell = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = (service.screenSize().second - safeTop - safeBottom - dp(16)).coerceAtLeast(dp(80))
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
        }
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
            super.onConfigurationChanged(newConfig)
            post { clampPosition(); requestLayout() }
        }
    }.apply { addView(box) }
    private val params = WindowManager.LayoutParams(
        minOf(dp(360), service.screenSize().first - dp(16)), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(40) }
    private var attached = false
    private var session: CaptureSession? = null
    private var started = 0L
    private var status: TextView? = null
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    private fun message(text: String) = Toast.makeText(service, text, Toast.LENGTH_LONG).show()
    private fun button(label: String, action: () -> Unit): Button = Button(context).apply { text = label; setOnClickListener { action() } }
    private fun line(text: String): TextView = TextView(context).apply { this.text = text; textSize = 14f; setTextColor(0xff111111.toInt()) }
    private fun row(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }
    private fun handle(text: String): TextView = line(text).apply {
        var initialY = 0f
        var offset = 0
        setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { initialY = e.rawY; offset = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    // Stay above the navigation area; default placement is away from the composer.
                    params.y = (offset + e.rawY - initialY).toInt().coerceIn(safeTop + dp(8), maxOf(safeTop + dp(8), service.screenSize().second - shell.height - safeBottom - dp(8)))
                    if (attached) runCatching { wm.updateViewLayout(shell, params) }
                    true
                }
                MotionEvent.ACTION_UP -> { view.performClick(); true }
                else -> false
            }
        }
    }
    private fun clampPosition() {
        val (w, h) = service.screenSize()
        val width = minOf(dp(360), (w - safeLeft - safeRight - dp(16)).coerceAtLeast(dp(80)))
        val y = params.y.coerceIn(safeTop + dp(8), maxOf(safeTop + dp(8), h - safeBottom - shell.height - dp(8)))
        val x = (safeLeft - safeRight) / 2
        if (params.width == width && params.y == y && params.x == x) return
        params.width = width; params.y = y; params.x = x
        if (attached) runCatching { wm.updateViewLayout(shell, params) }
    }
    fun show() {
        shell.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                safeTop = b.top; safeBottom = b.bottom; safeLeft = b.left; safeRight = b.right
            } else {
                @Suppress("DEPRECATION")
                safeTop = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                safeBottom = insets.systemWindowInsetBottom
                @Suppress("DEPRECATION")
                safeLeft = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                safeRight = insets.systemWindowInsetRight
            }
            shell.post { clampPosition() }
            insets
        }
        shell.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> shell.post { clampPosition() } }
        box.addView(handle("IO Matrix · Capture conversation ↕"))
        box.addView(line("After Start, read exposed text in this app and window. Optional automatic scrolling and safe detail expansion. No network, OCR or background monitoring. Passwords and editable drafts are excluded. Completeness is unverified. Stay in this conversation/tab until stopped."))
        val auto = CheckBox(context).apply { text = "Scroll automatically"; isChecked = true }
        val beginning = CheckBox(context).apply { text = "Try to reach the beginning first"; isChecked = true }
        val expand = CheckBox(context).apply { text = "Expand recognized collapsed details"; isChecked = true }
        listOf(auto, beginning, expand).forEach { box.addView(it) }
        val limits = listOf(50, 200, 500)
        val limit = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, limits.map { "Limit: $it captured views" })
            setSelection(1)
        }
        box.addView(limit)
        box.addView(button("Try system selection of focused text") {
            runCatching { source.selectFocused(source.bind()) }.fold(
                onSuccess = { message(if (it) "Selection requested in the focused text node" else "This text does not expose system selection. Capture, then select text in the review instead.") },
                onFailure = { message("Return to the conversation first. No content was copied.") }
            )
        })
        box.addView(row(button("Start") {
            start(CaptureOptions(autoScroll = auto.isChecked, seekStart = beginning.isChecked,
                expandDetails = expand.isChecked, maxFrames = limits[limit.selectedItemPosition]))
        }, button("Cancel") { dispose() }))
        wm.addView(shell, params); attached = true; shell.requestApplyInsets()
    }
    private fun start(options: CaptureOptions) {
        try {
            val target = source.bind()
            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            val archive = ConversationArchive(target.pkg, target.window, date, options)
            val driver = object : CaptureDriver {
                override fun frame(elapsed: Long, phase: String) = source.frame(target, elapsed, phase)
                override fun scroll(forward: Boolean) = source.scroll(target, forward)
                override fun expand(attempted: MutableSet<String>) = source.expandOne(target, attempted)
            }
            session = CaptureSession(archive, driver)
            started = SystemClock.elapsedRealtime()
            box.removeAllViews()
            status = handle("Starting… ↕").also { box.addView(it) }
            box.addView(row(button("Stop & review") { session?.stop(); review() }, button("Discard…") { confirmDiscard() }))
            main.post(tick)
        } catch (_: Exception) { message("No readable application window. Close the shade/dialog and return to the conversation, then press Start.") }
    }
    private val tick = object : Runnable {
        override fun run() {
            val s = session ?: return
            s.step(SystemClock.elapsedRealtime() - started)
            status?.text = "${s.state} · ${s.archive.frames.size} views · ${s.archive.expanded} expanded ↕"
            if (s.state == CaptureSession.State.FINISHED) {
                status?.text = "Stopped: ${s.archive.reason}\n${s.archive.frames.size} views · Review before copying ↕"
                if (IoAccessibilityService.instance !== service) { ConversationCaptureUi.result = s.archive; dispose() }
            } else main.postDelayed(this, s.archive.options.settleMillis)
        }
    }
    private fun confirmDiscard() {
        main.removeCallbacks(tick)
        box.removeAllViews()
        box.addView(line("Discard this capture? It has not been saved or copied."))
        box.addView(row(button("Keep & review") { session?.stop(); review() }, button("Discard") { session?.stop("discarded"); dispose() }))
    }
    private fun review() {
        val archive = session?.archive ?: return
        main.removeCallbacks(tick)
        ConversationCaptureUi.result = archive
        try {
            service.startActivity(Intent(service, ConversationCaptureReviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            dispose()
        } catch (_: Exception) { message("Cannot open review right now. The capture is still in memory; try Stop & review again.") }
    }
    fun dispose() {
        main.removeCallbacksAndMessages(null)
        if (attached) runCatching { wm.removeView(shell) }
        attached = false
        closed()
    }
}
