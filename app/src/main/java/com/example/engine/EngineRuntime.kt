package com.example.engine

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import com.example.core.config.Knobs
import com.example.core.config.SettingsStore
import com.example.core.config.knob
import com.example.core.config.knobLong
import com.example.core.engine.EngineState
import com.example.core.engine.InputSource
import com.example.core.engine.Inputs
import com.example.core.engine.Phrases
import com.example.core.engine.VolumeGestures
import com.example.core.engine.Wire
import com.example.core.io.Macros
import com.example.core.layout.KeyAction
import com.example.core.layout.LayoutJson
import com.example.core.matrix.NetLines
import com.example.core.matrix.NetMessage
import com.example.core.matrix.Sample
import com.example.core.matrix.Sink
import com.example.core.matrix.StreamSource
import com.example.io.Performer
import com.example.io.PerformerHost
import com.example.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The engine, once per process: every input it can hear, wherever it is heard, and
 * where the resulting actions go.
 *
 * It used to live inside the keyboard, which meant it stopped hearing anything the
 * moment the keyboard closed — exactly when a phone is in a pocket or on a desk. Now
 * the keyboard and the accessibility service both report to it, it listens whenever
 * either is alive, and it sends each action where it can be carried out: to the
 * keyboard when it is open (so text and cursor keys work), to the outputs engine
 * directly when it is not.
 */
object EngineRuntime {

    private const val TAG = "Engine"

    private var context: Context? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var state = EngineState()
        private set

    /** Carries out a key action with the keyboard open; null while it is closed. */
    private var keyboard: ((KeyAction) -> Unit)? = null

    private var serviceUp = false

    /** Asks the accessibility service to route hardware keys here, or to stop. */
    var keyFilter: ((Boolean) -> Unit)? = null

    private var hub: SensorHub? = null
    private var voice: VoiceTrigger? = null
    private var systemReceiver: BroadcastReceiver? = null
    private var volume = VolumeGestures()
    private var wiresSource: String? = null
    private var wiresCache: List<Wire> = emptyList()

    /** Screen text handed to the AI, shared by whichever side read it. */
    var screenContext: Triple<Long, String?, String>? = null

    fun wires(): List<Wire> {
        val s = SettingsStore.current
        val key = s.engineWiresJson + "\u0000" + s.pocketUnlockPhrase + "\u0000" + s.pocketLockPhrase +
            "\u0000" + s.pocketLockPhraseApps.joinToString(",")
        if (key != wiresSource) {
            wiresSource = key
            wiresCache = Inputs.parse(s.engineWiresJson) + shorthandWires(s)
        }
        return wiresCache
    }

    /**
     * Settings that are really wires, written out as wires so there is one mechanism:
     * the spoken phrases of the pocket lock.
     */
    private fun shorthandWires(s: com.example.core.config.Settings): List<Wire> = buildList {
        if (s.pocketUnlockPhrase.isNotBlank()) {
            add(
                Wire(
                    on = "voice", action = "do:pocket_lock off", phrase = s.pocketUnlockPhrase,
                    scope = com.example.core.engine.WireScope.LOCKED
                )
            )
        }
        if (s.pocketLockPhrase.isNotBlank()) {
            add(
                Wire(
                    on = "voice", action = "do:pocket_lock on", phrase = s.pocketLockPhrase,
                    scope = com.example.core.engine.WireScope.UNLOCKED, apps = s.pocketLockPhraseApps
                )
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching = false

    private fun attach(ctx: Context) {
        if (context == null) context = ctx.applicationContext
        SettingsStore.init(ctx)
        com.example.io.Speaker.tell = { tell(it) }
        if (!watching) {
            watching = true
            volume = VolumeGestures(
                SettingsStore.current.knobLong(Knobs.VOLUME_DOUBLE_MS),
                SettingsStore.current.knobLong(Knobs.VOLUME_LONG_MS)
            )
            // Wires and knobs edited while the engine runs apply at once, whichever
            // side — keyboard or accessibility — happens to be alive.
            scope.launch {
                SettingsStore.state
                    .map {
                        listOf(
                            it.engineWiresJson, it.engineStreamsJson, it.knobs, it.pocketUnlockPhrase, it.pocketLockPhrase,
                            it.pocketLockPhraseApps, it.netListenPort, it.netToken, it.netAllowCommands
                        )
                    }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { settingsChanged() }
            }
        }
    }

    // ------------------------------------------------------------------
    // Who is alive
    // ------------------------------------------------------------------

    fun keyboardShown(ctx: Context, pkg: String?, perform: (KeyAction) -> Unit) {
        attach(ctx)
        keyboard = perform
        state = state.copy(keyboardOpen = true, pkg = pkg)
        refresh()
        fire("keyboard_shown")
    }

    fun keyboardHidden() {
        fire("keyboard_hidden")
        keyboard = null
        state = state.copy(keyboardOpen = false)
        refresh()
    }

    /** The keyboard service itself is gone; it can no longer hear or carry anything out. */
    fun keyboardDestroyed() {
        keyboard = null
        state = state.copy(keyboardOpen = false)
        refresh()
    }

    fun serviceConnected(ctx: Context) {
        attach(ctx)
        serviceUp = true
        refresh()
    }

    fun serviceDisconnected() {
        serviceUp = false
        refresh()
    }

    fun appInFront(pkg: String) {
        if (state.keyboardOpen) return
        val changed = pkg != state.pkg
        state = state.copy(pkg = pkg)
        if (changed) {
            refresh()
            fire("app_opened")
        }
    }

    fun lockChanged(locked: Boolean) {
        state = state.copy(locked = locked)
        refresh()
        fire(if (locked) "pocket_locked" else "pocket_unlocked")
    }

    /** Settings changed: wires, knobs. Picked up on the next refresh. */
    fun settingsChanged() {
        volume = VolumeGestures(
            SettingsStore.current.knobLong(Knobs.VOLUME_DOUBLE_MS),
            SettingsStore.current.knobLong(Knobs.VOLUME_LONG_MS)
        )
        hub?.stop()
        refresh()
    }

    // ------------------------------------------------------------------
    // Listening to what the live wires need
    // ------------------------------------------------------------------

    private fun alive(): Boolean = state.keyboardOpen || serviceUp || keyboard != null

    private fun refresh() {
        val ctx = context ?: return
        val needed = if (alive()) Inputs.sourcesNeeded(wires(), state) + StreamRunner.sourcesNeeded(state) else emptySet()

        val sensors = hub ?: SensorHub(
            ctx,
            onInput = { fire(it) },
            onAcceleration = { t, v -> StreamRunner.feed("acceleration", Sample(t, v), state) }
        ).also { hub = it }
        sensors.listen(needed)

        if (InputSource.VOICE in needed) {
            val v = voice ?: VoiceTrigger(ctx, onHeard = { heard(it) }, onProblem = { tell(it) }).also { voice = it }
            v.start()
        } else {
            voice?.stop()
        }

        if (InputSource.SYSTEM in needed) registerSystem(ctx) else unregisterSystem(ctx)

        // The network is listened to only with a port, a token, and something that
        // reads from it — never because the feature exists.
        val s = SettingsStore.current
        val netWanted = alive() && s.netListenPort in 1..65535 && NetLines.tokenUsable(s.netToken) && (
            s.netAllowCommands ||
                wires().any { it.enabled && it.on.startsWith("net:") } ||
                StreamRunner.states().any { it.stream.enabled && it.stream.source == StreamSource.NETWORK }
            )
        if (netWanted) {
            (net ?: NetListener({ netReceived(it) }, { tell(it) }).also { net = it })
                .start(s.netListenPort, s.netToken, s.netAllowCommands)
        } else {
            net?.stop()
        }

        // With the keyboard open, it gets the keys itself; closed, only the
        // accessibility service can, and only while a wire wants them.
        val keysWanted = !state.keyboardOpen && Inputs.volumeInputsLive(wires(), state).isNotEmpty()
        keyFilter?.invoke(keysWanted)
    }

    private fun registerSystem(ctx: Context) {
        if (systemReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> fire("screen_on")
                    Intent.ACTION_SCREEN_OFF -> fire("screen_off")
                    Intent.ACTION_POWER_CONNECTED -> fire("power_connected")
                    Intent.ACTION_POWER_DISCONNECTED -> fire("power_disconnected")
                    Intent.ACTION_HEADSET_PLUG -> {
                        // Sticky: the first delivery is the current state, not news.
                        if (isInitialStickyBroadcast) return
                        fire(if (intent.getIntExtra("state", 0) == 1) "headset_in" else "headset_out")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(r, filter)
        }
        systemReceiver = r
    }

    private fun unregisterSystem(ctx: Context) {
        val r = systemReceiver ?: return
        runCatching { ctx.unregisterReceiver(r) }
        systemReceiver = null
    }

    // ------------------------------------------------------------------
    // Inputs arriving
    // ------------------------------------------------------------------

    private fun heard(text: String) {
        val exact = SettingsStore.current.knob(Knobs.VOICE_MATCH)
        val matching = Inputs.firing(wires(), "voice", state)
            .filter { w -> w.phrase?.let { Phrases.matches(it, text, exact) } == true }
        if (matching.isNotEmpty()) {
            AppLogger.d(TAG, "voice matched ${matching.size} wire(s)")
            matching.forEach { run(it, "voice") }
        }
    }

    private var net: NetListener? = null

    /** A line from the network, already checked against the token. */
    private fun netReceived(message: NetMessage) {
        when (message) {
            is NetMessage.Values -> {
                val input = "net:" + message.channel.lowercase()
                StreamRunner.feed(input, Sample(SystemClock.uptimeMillis(), message.values), state)
                fireWith(input) { Sink.fill(it, message.values) }
            }
            is NetMessage.Do -> LayoutJson.parseAction("do:" + message.line)?.let { dispatch(it) }
        }
    }

    /** A floating control moved: its values go to the streams that read it. */
    fun controlMoved(id: String, values: FloatArray) {
        StreamRunner.feed("control:$id", Sample(SystemClock.uptimeMillis(), values), state)
    }

    /** Streams start their calibration again: all of them, or the one called [id]. */
    fun recenter(id: String?) {
        StreamRunner.recenter(id)
        tell(if (id.isNullOrBlank()) "Streams recentred" else "Stream “$id” recentred")
    }

    /**
     * An input that carries a value — a control being moved. Each wire's action gets
     * the value put in by [fill] before it runs.
     */
    fun fireWith(input: String, fill: (String) -> String) {
        val live = Inputs.firing(wires(), input, state)
        live.forEach { run(it.copy(action = fill(it.action)), input) }
    }

    fun fire(input: String) {
        if (input == "voice") return
        val live = Inputs.firing(wires(), input, state)
        if (live.isEmpty()) return
        AppLogger.d(TAG, "$input → ${live.size} wire(s)")
        live.forEach { run(it, input) }
    }

    private fun run(wire: Wire, input: String) {
        val action = wire.parsed
        if (action == null) {
            tell("A wire on “$input” has an action that could not be read: ${wire.action}")
            return
        }
        dispatch(action)
    }

    /** Where an action goes: the keyboard if it is open, the outputs engine otherwise. */
    fun dispatch(action: KeyAction) {
        val kb = keyboard
        if (kb != null && state.keyboardOpen) {
            runCatching { kb(action) }.onFailure { AppLogger.e(TAG, "action failed", it) }
            return
        }
        when (action) {
            is KeyAction.Do -> background().run(action.command)
            is KeyAction.Macro -> action.steps.forEach { dispatch(it) }
            is KeyAction.None -> Unit
            else -> tell("That action needs the keyboard open: ${action.javaClass.simpleName}")
        }
    }

    /**
     * A hardware volume key, from the keyboard or from the accessibility service.
     * True when it was ours to take.
     */
    fun onVolumeKey(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "up"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "down"
            else -> return false
        }
        val wired = Inputs.volumeInputsLive(wires(), state)
        if (wired.isEmpty()) return false
        val now = SystemClock.uptimeMillis()
        val out = when (event.action) {
            KeyEvent.ACTION_DOWN -> volume.onDown(key, event.repeatCount > 0, now, wired)
            KeyEvent.ACTION_UP -> volume.onUp(key, now, wired)
            else -> return false
        }
        apply(out)
        return out.consume
    }

    private fun apply(out: VolumeGestures.Out) {
        out.fire.forEach { fire(it) }
        out.passVolume.forEach { stepVolume(it) }
        out.checkAt?.let { at ->
            main.postDelayed({
                apply(volume.due(SystemClock.uptimeMillis(), Inputs.volumeInputsLive(wires(), state)))
            }, (at - SystemClock.uptimeMillis()).coerceAtLeast(0) + 5)
        }
    }

    /** A press held back to see if it was a double turned out single: do what it would have. */
    private fun stepVolume(key: String) {
        val audio = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audio.adjustSuggestedStreamVolume(
            if (key == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.USE_DEFAULT_STREAM_TYPE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    // ------------------------------------------------------------------
    // Macros, played from wherever
    // ------------------------------------------------------------------

    private var playing = 0

    val isPlaying: Boolean get() = playing > 0

    fun playMacro(name: String, times: Int) {
        val steps = Macros.parse(SettingsStore.current.macrosJson)[name]
        if (steps.isNullOrEmpty()) {
            tell("No macro called “$name” yet — record one first")
            return
        }
        if (playing >= 4) return
        playing++
        var index = 0
        var round = 0
        fun next() {
            if (round >= times) {
                playing--
                return
            }
            if (index >= steps.size) {
                index = 0
                round++
                next()
                return
            }
            val step = steps[index++]
            val wait = Macros.waitOf(step)
            if (wait != null) {
                main.postDelayed({ next() }, wait)
            } else {
                runCatching { dispatch(step) }
                next()
            }
        }
        next()
    }

    // ------------------------------------------------------------------
    // Telling the user, with or without a keyboard to show it on
    // ------------------------------------------------------------------

    /** Set by the keyboard while it is open, so news goes to its strip. */
    var noticeSink: ((String, String?, (() -> Unit)?) -> Unit)? = null

    fun tell(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        AppLogger.d(TAG, text)
        val sink = noticeSink
        if (sink != null && state.keyboardOpen) {
            sink(text, actionLabel, action)
            return
        }
        val ctx = context ?: return
        main.post { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
    }

    private var backgroundPerformer: Performer? = null

    private fun background(): Performer {
        backgroundPerformer?.let { return it }
        val ctx = context ?: throw IllegalStateException("engine used before anything attached it")
        return Performer(object : PerformerHost {
            override val context: Context get() = ctx

            override fun sendKey(keyCode: Int) {
                tell("No field to send a key to with the keyboard closed")
            }

            override fun nearbyText(): String = ""

            override fun commit(text: String) {
                copy(text, "Text")
                tell("No field to type into — copied instead")
            }

            override fun copy(text: String, label: String) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
                cm.setPrimaryClip(ClipData.newPlainText(label, text))
            }

            override fun offerToAi(text: String) {
                screenContext = Triple(System.currentTimeMillis(), state.pkg, text)
            }

            override fun notice(text: String, actionLabel: String?, action: (() -> Unit)?) =
                tell(text, actionLabel, action)

            override fun record(state: String, name: String) {
                tell("Recording needs the keyboard open — it records what the keyboard does")
            }

            override fun play(name: String, times: Int) = playMacro(name, times)

            override fun dismissKeyboard() = Unit

            override fun readable(source: String): com.example.io.Readable? {
                // With the keyboard closed there is no field; the clipboard is all that is
                // left, and Android lets only the keyboard in use read it — so this may
                // come back empty, and the caller says so.
                if (source != "clipboard" && source != "auto") return null
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
                val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
                return text?.takeIf { it.isNotBlank() }?.let { com.example.io.Readable(it) }
            }
        }).also { backgroundPerformer = it }
    }
}
