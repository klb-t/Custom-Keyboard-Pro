package com.example.phone

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.core.io.Command
import com.example.core.phone.*
import com.example.io.Performer
import com.example.io.PerformerHost
import kotlinx.coroutines.*

/** Native contextual toolbox. Every grant/session starts from the selected feature. */
class PhoneToolsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var monitor: ForegroundSensorMonitor
    private var tab = "overview"
    private var contentJob: Job? = null
    private var foreground = false
    private var revision = 0L
    private fun label(value: String) = TextView(this).apply { text = value; setTextIsSelectable(true); setPadding(0, 10, 0, 8) }
    private fun button(value: String, run: () -> Unit) = Button(this).apply { text = value; setOnClickListener { run() } }
    private fun input(value: String, hintValue: String, numeric: Boolean = false) = EditText(this).apply {
        setText(value); hint = hintValue; maxLines = 1; isSaveEnabled = false
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        filters = arrayOf(InputFilter.LengthFilter(if (numeric) 4 else 255))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        tab = intent.getStringExtra("tab")?.takeIf { it in PhoneRequest.tabs } ?: "overview"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(label("IO Matrix · Phone tools"))
        val chooser = Spinner(this)
        val tabs = listOf("overview", "sensors", "apps", "access", "actions")
        chooser.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tabs)
        chooser.setSelection(tabs.indexOf(tab))
        chooser.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (tab != tabs[position]) { tab = tabs[position]; render() }
            }
        }
        root.addView(chooser)
        status = label("Opening a tab grants nothing and starts no capture.").apply {
            maxLines = 8; movementMethod = ScrollingMovementMethod()
        }; root.addView(status)
        root.addView(button("Stop sensor session") { monitor.stop(); status.text = "Stopped. No new sensor samples are collected." })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(16 + safe.left, safe.top, 16 + safe.right, safe.bottom); insets
        }
        monitor = ForegroundSensorMonitor(this) { status.text = it }
        setContentView(root); ViewCompat.requestApplyInsets(root)
    }
    override fun onResume() { super.onResume(); foreground = true; render() }
    override fun onPause() {
        foreground = false
        if (monitor.active) status.text = "Sensor session stopped when this screen was left. Start a new session explicitly."
        monitor.stop(); contentJob?.cancel(); super.onPause()
    }
    override fun onDestroy() { monitor.stop(); scope.cancel(); super.onDestroy() }
    private fun ready() = foreground && !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    private fun render() {
        if (!::body.isInitialized) return
        if (monitor.active) status.text = "Sensor session stopped when its panel was changed."
        monitor.stop(); contentJob?.cancel(); val token = ++revision; body.removeAllViews()
        when (tab) {
            "overview" -> {
                body.addView(label("Diagnostics are local snapshots. The app cannot enumerate or terminate all Android processes, change Secure/Global developer flags or pretend an ADB bridge is connected."))
                listOf("device", "memory", "processes", "usage").forEach { section -> body.addView(button("Inspect $section") { snapshot(section) }) }
                snapshot("device")
            }
            "sensors" -> {
                body.addView(label("All reported sensors are shown, including vendor and multiple instances. Physiological/unknown sensors stay inspect-only until an audited permission/format adapter exists. Values stay on this phone. Leaving this activity stops the reader."))
                contentJob = scope.launch {
                    val entries = withContext(Dispatchers.IO) { runCatching { PhoneInventory.sensors(this@PhoneToolsActivity) } }.getOrElse {
                        if (token == revision) status.text = "Android refused sensor inventory. No sensor was started."
                        return@launch
                    }
                    if (token != revision) return@launch
                    if (entries.isEmpty()) { body.addView(label("SensorManager reported no sensors.")); return@launch }
                    val selected = Spinner(this@PhoneToolsActivity).apply { adapter = ArrayAdapter(this@PhoneToolsActivity, android.R.layout.simple_spinner_dropdown_item, entries.map { "${it.key} · ${it.sensor.name}" }) }
                    selected.setSelection(entries.indexOfFirst { it.key == intent.getStringExtra("sensor") }.coerceAtLeast(0)); body.addView(selected)
                    val description = label(entries[selected.selectedItemPosition].describe()); body.addView(description)
                    selected.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { monitor.stop(); description.text = entries[position].describe() }
                    }
                    val hz = input(intent.getIntExtra("hz", 10).coerceIn(1, 20).toString(), "Requested display rate, 1–20/s", true)
                    val seconds = input(intent.getIntExtra("seconds", 30).coerceIn(1, 300).toString(), "Duration, 1–300 seconds", true)
                    body.addView(label("Maximum UI delivery rate (1–20/s)")); body.addView(hz)
                    body.addView(label("Duration (1–300 seconds)")); body.addView(seconds)
                    body.addView(button("Start selected sensor") {
                        if (!ready()) return@button
                        val entry = entries[selected.selectedItemPosition]
                        if (!entry.maySample) { status.text = entry.samplePolicy; return@button }
                        val rate = hz.text.toString().toIntOrNull(); val duration = seconds.text.toString().toIntOrNull()
                        if (rate == null || rate !in 1..20 || duration == null || duration !in 1..300) { status.text = "Rate must be 1–20/s and duration 1–300s."; return@button }
                        val permission = entry.permission
                        if (permission != null && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(arrayOf(permission), 9130); status.text = "After granting the selected sensor's access, press Start again."; return@button
                        }
                        monitor.start(entry, rate, duration)
                    })
                }
            }
            "apps" -> {
                body.addView(label("Launchable apps visible to this app. Select one to inspect metadata, launch it or open its system management screen. No private app data is read."))
                contentJob = scope.launch {
                    val apps = withContext(Dispatchers.IO) { runCatching { PhoneInventory.apps(this@PhoneToolsActivity) } }.getOrElse {
                        if (token == revision) status.text = "Android refused the visible app query. No app was launched."
                        return@launch
                    }
                    if (token != revision) return@launch
                    if (apps.isEmpty()) { body.addView(label("No launchable apps are visible.")); return@launch }
                    val select = Spinner(this@PhoneToolsActivity).apply { adapter = ArrayAdapter(this@PhoneToolsActivity, android.R.layout.simple_spinner_dropdown_item, apps.map { "${it.label} · ${it.packageName}" }) }; body.addView(select)
                    fun pkg() = apps[select.selectedItemPosition].packageName
                    body.addView(button("Inspect selected app") { run(Command("app_info", named = mapOf("package" to pkg()))) })
                    body.addView(button("Open selected app") { run(Command("open", named = mapOf("target" to pkg()))) })
                    body.addView(button("System app management") { run(Command("app_settings", named = mapOf("package" to pkg()))) })
                }
            }
            "access" -> {
                body.addView(label("Current access is rechecked on returning from system settings. Planned adapters are marked absent even if a grant exists."))
                val states = PhoneInventory.access(this).associateBy { it.id }
                PhoneCatalogue.routes.filter { it.id in PhoneCatalogue.accessIds }.forEach { route ->
                    val state = states[route.id]
                    body.addView(label("${route.label} · ${state?.status ?: "unknown"}\n${route.explanation}"))
                    body.addView(button("Open ${route.label}") { run(Command("special_access", named = mapOf("target" to route.id))) })
                }
            }
            "actions" -> {
                body.addView(label("Actions run only when selected. Settings readback verifies the configured value; OEM policy and app locks can affect the visible result."))
                listOf("on", "off").forEach { state -> body.addView(button("Flashlight $state") {
                    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) status.text = "This phone does not advertise flashlight hardware. No camera permission is requested."
                    else if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.CAMERA), 9131); status.text = "After granting camera permission, choose Flashlight again. No camera image is captured." }
                    else run(Command("torch", named = mapOf("state" to state)))
                }) }
                body.addView(button("Grant Modify system settings for rotation/timeout") { run(Command("special_access", named = mapOf("target" to "write_settings"))) })
                body.addView(button("Automatic rotation") { run(Command("rotation", named = mapOf("state" to "auto"))) })
                listOf("portrait", "landscape", "reverse_portrait", "reverse_landscape", "natural", "quarter_turn", "half_turn", "three_quarter_turn").forEach { orientation -> body.addView(button("Lock rotation: $orientation") { run(Command("rotation", named = mapOf("state" to "locked", "orientation" to orientation))) }) }
                val timeout = input("120", "Screen timeout, 15–1800 seconds", true); body.addView(timeout)
                body.addView(button("Apply screen timeout") { run(Command("screen_timeout", named = mapOf("seconds" to timeout.text.toString()))) })
                listOf("up", "down").forEach { direction -> body.addView(button("Media volume $direction") { run(Command("volume", named = mapOf("direction" to direction, "stream" to "music"))) }) }
                listOf("home", "recents", "notifications", "quick_settings").forEach { action -> body.addView(button(action.replace('_', ' ')) { run(Command(action)) }) }
                listOf("developer", "wireless_debug", "wifi", "bluetooth", "display", "sound", "storage", "location").forEach { page ->
                    val route = PhoneCatalogue.route(page)!!
                    body.addView(label(route.explanation)); body.addView(button("Open ${route.label}") { run(Command("system_settings", named = mapOf("page" to page))) })
                }
            }
        }
    }
    private fun snapshot(section: String) {
        contentJob?.cancel(); val token = revision
        contentJob = scope.launch {
            val receipt = withContext(Dispatchers.IO) { PhoneRuntime.execute(this@PhoneToolsActivity, Command("phone_info", named = mapOf("section" to section))) }
            if (token == revision) status.text = "${receipt.outcome}\n${receipt.detail}"
        }
    }
    private fun run(command: Command) {
        if (!ready()) return
        // Metadata snapshots may involve PackageManager/Binder work; keep them off input/UI.
        if (command.verb == "app_info") {
            contentJob?.cancel(); val token = revision
            contentJob = scope.launch { val receipt = withContext(Dispatchers.IO) { PhoneRuntime.execute(this@PhoneToolsActivity, command) }; if (token == revision) status.text = "${receipt.outcome}\n${receipt.detail}" }
            return
        }
        Performer(object : PerformerHost {
            override val context: Context get() = this@PhoneToolsActivity
            override fun sendKey(keyCode: Int) = Unit
            override fun nearbyText() = ""
            override fun commit(text: String) = Unit
            override fun copy(text: String, label: String) = Unit
            override fun offerToAi(text: String) = Unit
            override fun notice(text: String, actionLabel: String?, action: (() -> Unit)?) { status.text = text }
            override fun record(state: String, name: String) = Unit
            override fun play(name: String, times: Int) = Unit
            override fun dismissKeyboard() = Unit
            override fun phoneReceipt(receipt: PhoneReceipt) { status.text = "${receipt.outcome}\n${receipt.detail}" }
        }).run(command)
    }
}
