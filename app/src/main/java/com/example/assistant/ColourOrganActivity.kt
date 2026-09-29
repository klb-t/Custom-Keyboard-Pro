package com.example.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.core.devices.*
import com.example.core.matrix.Sample
import com.example.core.matrix.Stages
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** One foreground composition: microphone -> RMS -> shared smoothing -> control mapping -> sink.
 * No cloud, background microphone, hidden playback capture, or model calls in the signal path.
 */
class ColourOrganActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var session: Job? = null
    @Volatile private var sink: LightSink? = null
    private var mailbox: LatestLight? = null
    private var foreground = false
    private var generation = 0L
    private lateinit var status: TextView
    private lateinit var host: EditText
    private lateinit var maximum: SeekBar
    private lateinit var gain: SeekBar
    private lateinit var hz: SeekBar
    private lateinit var seconds: EditText
    private fun now() = SystemClock.elapsedRealtime()
    private fun ready() = foreground && !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    private fun label(s: String) = TextView(this).apply { text = s; setTextIsSelectable(true); setPadding(0, 12, 0, 8) }
    private fun button(s: String, run: () -> Unit) = Button(this).apply { text = s; setOnClickListener { run() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(label("Colour organ · explicit foreground session\nCurrent adapter: Yeelight bulbs that advertise LAN control, set_music and set_scene. Other Wi-Fi bulbs need their own adapter, not a different signal engine.\n\nThis is level-reactive colour/brightness, not beat tracking. Audio stays on the phone. Only colour commands go to the selected bulb."))
        host = EditText(this).apply { hint = "Bulb's private IPv4 address (for example 192.168.1.20)"; isSaveEnabled = false; maxLines = 1; filters = arrayOf(android.text.InputFilter.LengthFilter(15)) }
        body.addView(host)
        body.addView(label("Maximum brightness: 1–60% (default 30%)"))
        maximum = SeekBar(this).apply { max = 59; progress = 29 }; body.addView(maximum)
        body.addView(label("Input gain: 0.1–20× (default 3×)"))
        gain = SeekBar(this).apply { max = 199; progress = 29 }; body.addView(gain)
        body.addView(label("Updates per second: 1–20 (default 10)"))
        hz = SeekBar(this).apply { max = 19; progress = 9 }; body.addView(hz)
        seconds = EditText(this).apply { hint = "Duration: 1–600 seconds"; setText("120"); filters = arrayOf(android.text.InputFilter.LengthFilter(3)); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        body.addView(seconds)
        body.addView(button("Start preview (no microphone or network)") { preview() })
        body.addView(button("Start microphone → selected bulb…") { confirm() })
        body.addView(label("Enable LAN control in the bulb's own app first. This unencrypted manufacturer protocol is for a trusted LAN only; IP pinning is not authentication. The phone briefly accepts a music-mode TCP connection from that bulb.\n\nStop or leaving this screen closes recording and sockets. The last colour is left in place: there is no automatic rollback that might overwrite your later changes. Session controls reset when this activity is recreated."))
        status = label("Idle. Opening this panel performs no discovery, recording or light action."); body.addView(status)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(button("Stop") { stop(); status.text = "Stopped. Previously applied light state is left unchanged." })
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(16 + b.left, b.top, 16 + b.right, b.bottom); insets
        }
        setContentView(root); ViewCompat.requestApplyInsets(root)
    }
    private fun policy() = ReactiveLightPolicy((gain.progress + 1) / 10f, maximum.progress + 1, hz.progress + 1, seconds.text.toString().toInt())
    private fun preview() {
        stop()
        val p = runCatching { policy() }.getOrNull() ?: return report("Invalid session parameters.")
        // Synthetic levels, explicitly not captured audio or a physical bulb test.
        status.text = "Synthetic preview only:\n" + listOf(0f, .03f, .1f, .2f, .5f).joinToString("\n") { level ->
            val value = LevelToColour.map(level, p)
            "RMS $level → RGB #${"%06x".format(value.rgb)}, brightness ${value.brightness}%"
        }
    }
    private fun report(value: String) { status.text = value }
    private fun confirm() {
        if (!ready()) return
        val address = host.text.toString().trim()
        val p = runCatching { YeelightProtocol.privateIpv4(address); policy() }.getOrNull()
            ?: return report("Enter a numeric private LAN address and valid duration (1–600 seconds).")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 8820)
            return report("After granting microphone access, press Start again. No recording starts automatically.")
        }
        AlertDialog.Builder(this).setTitle("Record audio and control this bulb?")
            .setMessage("Target: $address\nMaximum brightness: ${p.maximumBrightness}%\nRate: up to ${p.hz}/s\nDuration: ${p.durationSeconds}s\n\nThe bulb may turn on. Only this address is queried; an unsupported response stops setup. Changing lights may be uncomfortable—stop immediately if needed. Stop leaves the last light state unchanged.")
            .setNegativeButton("Cancel", null).setPositiveButton("Start") { _, _ -> if (ready()) start(address, p) }.show()
    }
    private fun decoder() = YeelightReplyDecoder { raw ->
        val o = JSONObject(raw)
        val id = if (o.has("id")) (o.get("id") as? Int) ?: error("Invalid reply ID") else null
        val error = o.has("error")
        val a = o.optJSONArray("result")
        val result = a?.let { require(it.length() <= 16); (0 until it.length()).map { i ->
            val v = it.get(i); require(v is String && v.length <= 128); v
        } }
        YeelightReply(id, result, error)
    }
    private fun start(address: String, p: ReactiveLightPolicy) {
        stop(); val token = ++generation
        val light = YeelightConnection(address, decoder()); sink = light
        val queue = LatestLight(p.hz); mailbox = queue
        val sent = AtomicLong(0); val writeSince = AtomicLong(-1)
        val setupBegan = now(); var connected = false
        status.text = "Checking the selected bulb and its declared operations…"
        session = scope.launch {
            try {
                val identity = withContext(Dispatchers.IO) { light.connect() }
                ensureActive(); check(ready() && token == generation); connected = true
                status.text = "Connected: ${identity.model} (${identity.id}). Starting local microphone. Sent colours are not physical-effect verification."
                coroutineScope {
                    val begun = now()
                    val sender = launch(Dispatchers.IO) {
                        while (isActive) {
                            queue.take(now())?.let { value ->
                                writeSince.set(now())
                                try { light.send(value); sent.incrementAndGet() } finally { writeSince.set(-1) }
                            }
                            delay(10)
                        }
                    }
                    val recorder = launch(Dispatchers.IO) {
                        val sampleRate = 16000
                        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        require(minimum > 0)
                        @Suppress("MissingPermission")
                        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
                        try {
                            ensureActive(); require(audio.state == AudioRecord.STATE_INITIALIZED)
                            val smoothing = Stages.Smooth(1f, .01f, "smooth 1 0.01")
                            val samples = ShortArray(320)
                            ensureActive(); audio.startRecording(); require(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                            var lastAudio = now()
                            while (isActive && now() - begun < p.durationSeconds * 1000L) {
                                val count = audio.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                                require(count >= 0) { "Microphone read refused" }
                                if (count > 0) {
                                    val t = now(); lastAudio = t
                                    val filtered = smoothing.process(Sample(t, floatArrayOf(PcmEnvelope.rms(samples, count))))
                                    queue.offer(t, LevelToColour.map(filtered.v[0].coerceAtLeast(0f), p))
                                } else { check(now() - lastAudio < 2500) { "No microphone frames" }; delay(5) }
                            }
                        } finally { runCatching { audio.stop() }; audio.release() }
                    }
                    while (recorder.isActive) {
                        delay(250)
                        if (!ready() || now() - begun >= p.durationSeconds * 1000L) { recorder.cancel(); break }
                        val at = writeSince.get()
                        check(at < 0 || now() - at < 2000) { "Light write stalled" }
                        if (token == generation) status.text = "Recording locally · ${sent.get()} colour commands sent (unverified effects). Stop leaves last colour in place."
                    }
                    recorder.join(); sender.cancel(); light.close(); sender.join()
                }
                if (token == generation) report("Session ended. ${sent.get()} colour commands sent; effects unverified. Last light state left in place.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == generation) report("Stopped: bulb discovery/protocol, LAN access, microphone or connection failed. No further commands are sent. Check LAN-control support and permissions; last light state is not rolled back.")
            } finally { light.close(); queue.close(); if (sink === light) sink = null }
        }
        // Independent main-thread watchdog can close a socket even if its blocking write stalls.
        fun watchdog() {
            if (token != generation || sink !== light) return
            if (!connected && now() - setupBegan > 10000) { stop(); report("Stopped a stalled light setup. Check LAN access and firmware support."); return }
            val at = writeSince.get()
            if (at >= 0 && now() - at > 2000) { stop(); report("Stopped a stalled light write. The actual final bulb state is unverified."); return }
            main.postDelayed({ watchdog() }, 250)
        }
        main.postDelayed({ watchdog() }, 250)
    }
    private fun stop() {
        generation++; session?.cancel(); session = null
        mailbox?.close(); mailbox = null; sink?.close(); sink = null
        // The bounded non-blocking audio loop observes cancellation and releases AudioRecord in finally.
        main.removeCallbacksAndMessages(null)
    }
    override fun onResume() { super.onResume(); foreground = true }
    override fun onPause() { foreground = false; stop(); super.onPause() }
    override fun onStop() { foreground = false; stop(); super.onStop() }
    override fun onDestroy() { stop(); scope.cancel(); super.onDestroy() }
}
