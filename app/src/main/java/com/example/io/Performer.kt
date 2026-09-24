package com.example.io

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.KeyEvent
import com.example.core.io.Command
import com.example.core.io.NodeQuery
import com.example.core.io.Sweep
import com.example.core.io.VerbSpec
import com.example.core.io.Verbs
import com.example.core.config.knob
import com.example.util.AppLogger
import kotlin.math.roundToInt

/** What the performer needs from whoever is running it — the keyboard, for now. */
interface PerformerHost {
    val context: Context

    /** A key sent to the app being typed in: the fallback for several verbs. */
    fun sendKey(keyCode: Int)

    /** The selection, or the text around the cursor. */
    fun nearbyText(): String

    fun commit(text: String)

    fun copy(text: String, label: String)

    /** Hands text to the AI as context for the next task, rather than as its input. */
    fun offerToAi(text: String)

    /** Tells the user something, with an optional thing to tap. */
    fun notice(text: String, actionLabel: String? = null, action: (() -> Unit)? = null)

    /** Starts, stops or toggles recording a macro called [name]. */
    fun record(state: String, name: String)

    /** Plays the macro called [name], [times] times. */
    fun play(name: String, times: Int)

    /** Puts the keyboard away, for actions that are about the app rather than the field. */
    fun dismissKeyboard()

    /**
     * Text to read aloud from [source] (selection, field, before, sentence, word,
     * clipboard, auto), and where it starts in the field when it came from there.
     */
    fun readable(source: String): Readable? = null
}

/** Something to read, and its position in the field when it has one. */
data class Readable(val text: String, val origin: Int? = null)

/**
 * Carries out a [Command]: the only place a verb touches the platform.
 *
 * Every verb is answered, one way or another. Without the access it needs, a verb
 * uses its fallback when it has one — Back is still the Back key, scrolling is still
 * Page Down — and otherwise says what it needs and offers the screen that grants it.
 * A key that silently does nothing is the one outcome not on the list.
 */
class Performer(private val host: PerformerHost) {

    private val context: Context get() = host.context

    fun run(command: Command) {
        val spec = Verbs.byId(command.verb)
        if (spec == null) {
            host.notice("No such action: “${command.verb}”")
            return
        }
        try {
            perform(spec, command)
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "${spec.id} was refused", e)
            host.notice("${spec.label}: the system refused (${e.message ?: "no reason given"})")
        } catch (e: Exception) {
            AppLogger.e(TAG, "${spec.id} failed", e)
            host.notice("${spec.label} failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun perform(spec: VerbSpec, command: Command) {
        val service = IoAccessibilityService.instance
        if (spec.needsAccessibility && service == null) {
            if (!fallback(spec, command)) askForAccess(spec)
            return
        }
        when (spec.id) {
            "back" -> global(service!!, AccessibilityService.GLOBAL_ACTION_BACK, spec)
            "home" -> global(service!!, AccessibilityService.GLOBAL_ACTION_HOME, spec)
            "recents" -> global(service!!, AccessibilityService.GLOBAL_ACTION_RECENTS, spec)
            "notifications" -> global(service!!, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, spec)
            "quick_settings" -> global(service!!, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, spec)
            "power_menu" -> global(service!!, AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, spec)
            "split_screen" -> global(service!!, AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN, spec)
            "lock" -> requireApi(28, spec) { global(service!!, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, spec) }
            "screenshot" -> requireApi(28, spec) {
                global(service!!, AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, spec)
            }
            "app_drawer" -> requireApi(31, spec) {
                global(service!!, AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS, spec)
            }

            "tap" -> point(service!!, command)?.let { (x, y) -> service.press(x, y, 60) }
            "long_press" -> point(service!!, command)?.let { (x, y) ->
                service.press(
                    x, y,
                    (command.int("ms")?.toLong()
                        ?: com.example.core.config.SettingsStore.current.knob(com.example.core.config.Knobs.POINTER_LONG_MS).toLong())
                )
            }
            "swipe" -> {
                val s = service!!
                val (w, h) = s.screenSize()
                s.swipe(
                    scale(command.float("x1") ?: 0.5f, w), scale(command.float("y1") ?: 0.7f, h),
                    scale(command.float("x2") ?: 0.5f, w), scale(command.float("y2") ?: 0.3f, h),
                    (command.int("ms") ?: 250).toLong()
                )
            }
            "scroll" -> service!!.scroll(direction(command))
            "pointer" -> when (command.arg("state")?.lowercase()) {
                "on", "show" -> service!!.pointer.show()
                "off", "hide" -> service!!.pointer.hide()
                else -> service!!.pointer.toggle()
            }

            "pocket_lock" -> pocketLock(command)

            "read_aloud" -> readAloud(command)
            "read_control" -> when (command.arg("action")?.lowercase() ?: "toggle") {
                "stop" -> Speaker.stop()
                "pause" -> Speaker.pause()
                "resume" -> Speaker.resume(context)
                else -> if (!Speaker.toggle(context)) host.notice("Nothing is being read")
            }

            "click", "long_click" -> {
                val query = NodeQuery.from(command)
                if (query.isEmpty) {
                    host.notice("${spec.label}: say what to look for — text=, id= or desc=")
                } else if (!service!!.press(query, long = spec.id == "long_click")) {
                    host.notice("Nothing on screen matches ${describe(query)}")
                }
            }
            "sweep" -> {
                val mode = Sweep.Mode.parse(command.arg("mode"))
                val query = NodeQuery.from(command)
                if (mode == Sweep.Mode.MATCH && query.isEmpty) {
                    host.notice("Select many: “match” needs text=, id= or desc= to look for")
                    return
                }
                val pages = command.int("pages") ?: 1
                host.notice("Selecting… (${mode.name.lowercase()}, $pages page${if (pages == 1) "" else "s"})")
                service!!.sweep(mode, pages, query) { count ->
                    host.notice(if (count == 0) "Nothing to select here" else "Done: $count")
                }
            }
            "read_screen" -> {
                val text = service!!.screenText()
                if (text.isBlank()) {
                    host.notice("Nothing readable on screen")
                    return
                }
                deliver(text, command.arg("to"))
            }

            "media" -> media(command.arg("action") ?: "play_pause")
            "volume" -> volume(command.arg("direction") ?: "up", command.arg("stream") ?: "music")
            "volume_set" -> volumeSet(command.float("level"), command.arg("stream") ?: "music")
            "brightness" -> brightness(command.arg("level"))
            "set" -> setSetting(command.arg("key"), command.arg("value"))
            "toggle_setting" -> toggleSetting(command.arg("key"))
            "torch" -> torch(command.arg("state") ?: "toggle")
            "vibrate" -> vibrate((command.int("ms") ?: 40).toLong())

            "open" -> open(command.arg("target").orEmpty())
            "search" -> search(command.arg("query")?.takeIf { it.isNotBlank() } ?: host.nearbyText())
            "share" -> share(command.arg("text")?.takeIf { it.isNotBlank() } ?: host.nearbyText())
            "system_settings" -> systemSettings(command.arg("page").orEmpty())

            "record" -> host.record(
                command.arg("state")?.lowercase() ?: "toggle",
                command.arg("name")?.takeIf { it.isNotBlank() } ?: "last"
            )
            "play" -> host.play(
                command.arg("name")?.takeIf { it.isNotBlank() } ?: "last",
                (command.int("times") ?: 1).coerceIn(1, 1000)
            )
            // Only a macro being played waits; the player handles it before it gets here.
            "wait" -> Unit

            else -> host.notice("“${spec.id}” is listed but not wired up yet")
        }
    }

    private fun readAloud(command: Command) {
        val literal = command.arg("text")?.takeIf { it.isNotBlank() }
        val source = command.arg("source")?.lowercase() ?: if (literal != null) "text" else "auto"
        when (source) {
            "text" -> {
                if (literal == null) host.notice("Read aloud: nothing given to read") else Speaker.speak(context, literal)
            }
            "screen", "page" -> {
                val service = IoAccessibilityService.instance
                if (service == null) {
                    askForAccess(Verbs.byId("read_screen") ?: return)
                    return
                }
                if (source == "page") {
                    host.dismissKeyboard()
                    PageReader(service) { host.notice(it) }.start()
                } else {
                    val text = service.screenText()
                    if (text.isBlank()) host.notice("Nothing readable on screen") else Speaker.speak(context, text)
                }
            }
            else -> {
                val what = host.readable(source)
                if (what == null || what.text.isBlank()) {
                    host.notice("Nothing to read here")
                } else {
                    Speaker.speak(context, what.text, what.origin)
                }
            }
        }
    }

    private fun pocketLock(command: Command) {
        val mode = com.example.core.io.PocketMode.parse(command.arg("mode"))
            ?: com.example.core.config.SettingsStore.current.pocketMode
        val state = command.arg("state")?.lowercase()
        if (state == "off" || (state != "on" && PocketLocks.locked)) {
            PocketLocks.unlock("asked")
            return
        }
        if (PocketLocks.locked) return
        if (!PocketLocks.available(context)) {
            // Two ways in, and each gives something the other does not; the user
            // picks. Accessibility first because it does more.
            host.notice(
                "Pocket lock needs accessibility (does everything) or “display over other apps” " +
                    "(no volume-key blocking without focus, no bringing the app back)",
                actionLabel = "Accessibility"
            ) { startSafely(IoAccessibilityService.settingsIntent()) }
            return
        }
        // The keyboard goes first: what is being locked is the app, and a keyboard
        // left open under the lock is a keyboard left in the pocket.
        host.dismissKeyboard()
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ PocketLocks.lock(context, mode) }, 300)
    }

    // ------------------------------------------------------------------
    // Without access
    // ------------------------------------------------------------------

    /** The reduced form each verb's [VerbSpec.without] promises. True when it ran. */
    private fun fallback(spec: VerbSpec, command: Command): Boolean = when (spec.id) {
        "back" -> {
            host.sendKey(KeyEvent.KEYCODE_BACK); true
        }
        "scroll" -> {
            host.sendKey(
                when (direction(command)) {
                    "up" -> KeyEvent.KEYCODE_PAGE_UP
                    "left" -> KeyEvent.KEYCODE_MOVE_HOME
                    "right" -> KeyEvent.KEYCODE_MOVE_END
                    else -> KeyEvent.KEYCODE_PAGE_DOWN
                }
            )
            true
        }
        else -> false
    }

    private fun askForAccess(spec: VerbSpec) {
        val enabled = IoAccessibilityService.isEnabled(context)
        host.notice(
            if (enabled) "${spec.label}: accessibility is on but not running yet — try again in a moment"
            else "${spec.label} needs this app's accessibility service switched on",
            actionLabel = if (enabled) null else "Switch on"
        ) {
            startSafely(IoAccessibilityService.settingsIntent())
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun global(service: IoAccessibilityService, action: Int, spec: VerbSpec) {
        if (!service.performGlobalAction(action)) host.notice("${spec.label}: the system declined")
    }

    private inline fun requireApi(level: Int, spec: VerbSpec, block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= level) block()
        else host.notice("${spec.label} needs Android API $level or newer")
    }

    /** 0–1 is a fraction of the screen; anything larger is already pixels. */
    private fun scale(v: Float, size: Int): Float = if (v in 0f..1f) v * size else v

    private fun point(service: IoAccessibilityService, command: Command): Pair<Float, Float>? {
        val x = command.float("x")
        val y = command.float("y")
        if (x == null || y == null) {
            host.notice("Say where: x and y, as 0–1 or pixels")
            return null
        }
        val (w, h) = service.screenSize()
        return scale(x, w) to scale(y, h)
    }

    private fun direction(command: Command): String =
        command.arg("direction")?.lowercase()?.takeIf { it in setOf("up", "down", "left", "right") } ?: "down"

    private fun describe(q: NodeQuery): String = listOfNotNull(
        q.text?.let { "text “$it”" }, q.id?.let { "id “$it”" }, q.desc?.let { "description “$it”" }
    ).joinToString(", ")

    private fun deliver(text: String, to: String?) {
        when (to?.lowercase()) {
            "field", "type" -> host.commit(text)
            "ai" -> {
                host.offerToAi(text)
                host.notice("The AI will see what is on screen (${text.length} characters)")
            }
            else -> {
                host.copy(text, "On screen")
                host.notice("Copied what is on screen (${text.length} characters)")
            }
        }
    }

    private fun media(action: String) {
        val code = when (action.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "rewind" -> KeyEvent.KEYCODE_MEDIA_REWIND
            "forward", "fast_forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private fun volume(direction: String, stream: String) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = when (stream.lowercase()) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            "system" -> AudioManager.STREAM_SYSTEM
            else -> AudioManager.STREAM_MUSIC
        }
        val d = when (direction.lowercase()) {
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_MUTE
            "unmute" -> AudioManager.ADJUST_UNMUTE
            "toggle", "toggle_mute" -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> AudioManager.ADJUST_RAISE
        }
        audio.adjustStreamVolume(s, d, AudioManager.FLAG_SHOW_UI)
    }

    /** 0–1 is a share of the range; anything above 1 is a percentage. */
    private fun level(raw: Float): Float = (if (raw > 1f) raw / 100f else raw).coerceIn(0f, 1f)

    private fun streamOf(name: String): Int = when (name.lowercase()) {
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "call", "voice" -> AudioManager.STREAM_VOICE_CALL
        "system" -> AudioManager.STREAM_SYSTEM
        else -> AudioManager.STREAM_MUSIC
    }

    private fun volumeSet(raw: Float?, stream: String) {
        if (raw == null) {
            host.notice("Set the volume: say to what, 0 to 1")
            return
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = streamOf(stream)
        val max = audio.getStreamMaxVolume(s)
        // No volume panel: a fader being dragged would otherwise flash it on every step.
        audio.setStreamVolume(s, (level(raw) * max).roundToInt(), 0)
    }

    private fun brightness(raw: String?) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(context)) {
            host.notice("Brightness needs “modify system settings” for this app", "Allow") {
                startSafely(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                )
            }
            return
        }
        val resolver = context.contentResolver
        if (raw?.trim()?.lowercase() == "auto") {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return
        }
        val value = raw?.replace(',', '.')?.toFloatOrNull()
        if (value == null) {
            host.notice("Brightness: say how bright, 0 to 1, or auto")
            return
        }
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (level(value) * 255).roundToInt().coerceIn(1, 255))
    }

    /** Any of the keyboard's own settings, through the same checks the settings screen uses. */
    private fun setSetting(key: String?, value: String?) {
        if (key.isNullOrBlank() || value == null) {
            host.notice("Change a setting: say which (key) and to what (value)")
            return
        }
        val spec = com.example.core.config.SettingsSchema.spec(key)
        if (spec == null) {
            host.notice("No setting called “$key”")
            return
        }
        val before = com.example.core.config.SettingsStore.current
        com.example.core.config.SettingsStore.setByKey(key, value)
        if (com.example.core.config.SettingsStore.current == before &&
            com.example.core.config.SettingsSchema.valueOf(before, key)?.toString() != value
        ) {
            host.notice("“$value” is not a value “${spec.label}” can take")
        }
    }

    private fun toggleSetting(key: String?) {
        val spec = key?.let { com.example.core.config.SettingsSchema.spec(it) }
        if (spec == null || spec.kind != com.example.core.config.SettingKind.BOOL) {
            host.notice("Flip a setting: “${key.orEmpty()}” is not an on/off setting")
            return
        }
        val now = com.example.core.config.SettingsSchema.valueOf(com.example.core.config.SettingsStore.current, spec.key) == true
        com.example.core.config.SettingsStore.setByKey(spec.key, !now)
        host.notice("${spec.label}: ${if (!now) "on" else "off"}")
    }

    private fun torch(state: String) {
        val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) {
            host.notice("This phone has no flash to use as a torch")
            return
        }
        Torch.watch(camera)
        val on = when (state.lowercase()) {
            "on" -> true
            "off" -> false
            else -> !Torch.isOn(id)
        }
        camera.setTorchMode(id, on)
    }

    private fun vibrate(ms: Long) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(1, 5000), VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(ms.coerceIn(1, 5000))
        }
    }

    private fun open(target: String) {
        val t = target.trim()
        if (t.isEmpty()) {
            host.notice("Open: say what — a link or an app's package name")
            return
        }
        val looksLikeLink = "://" in t || t.startsWith("www.") || t.startsWith("mailto:") ||
            t.startsWith("tel:") || t.startsWith("geo:")
        if (looksLikeLink) {
            val uri = Uri.parse(if (t.startsWith("www.")) "https://$t" else t)
            startSafely(Intent(Intent.ACTION_VIEW, uri))
            return
        }
        val launch = context.packageManager.getLaunchIntentForPackage(t)
        if (launch == null) {
            host.notice("No app called “$t” here")
            return
        }
        startSafely(launch)
    }

    private fun search(query: String) {
        if (query.isBlank()) {
            host.notice("Search: nothing selected and no words given")
            return
        }
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (!startSafely(intent, quiet = true)) {
            startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        }
    }

    private fun share(text: String) {
        if (text.isBlank()) {
            host.notice("Share: nothing selected and no text given")
            return
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startSafely(Intent.createChooser(send, null))
    }

    private fun systemSettings(page: String) {
        val action = when (page.lowercase().trim()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "keyboard", "input" -> Settings.ACTION_INPUT_METHOD_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "network", "wireless" -> Settings.ACTION_WIRELESS_SETTINGS
            "notifications" -> "android.settings.NOTIFICATION_SETTINGS"
            "date", "time" -> Settings.ACTION_DATE_SETTINGS
            "language", "locale" -> Settings.ACTION_LOCALE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "data", "mobile" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "privacy" -> Settings.ACTION_PRIVACY_SETTINGS
            "" -> Settings.ACTION_SETTINGS
            // Any other settings screen, by the action name Android gives it — so a
            // screen nobody listed here is still one line away.
            else -> if ('.' in page) page.trim() else Settings.ACTION_SETTINGS
        }
        if (!startSafely(Intent(action), quiet = true)) startSafely(Intent(Settings.ACTION_SETTINGS))
    }

    private fun startSafely(intent: Intent, quiet: Boolean = false): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        if (!quiet) host.notice("Nothing on this phone can open that")
        false
    } catch (e: SecurityException) {
        if (!quiet) host.notice("The system would not open that")
        false
    }

    /** The torch's state as the system reports it, since another app can flip it too. */
    private object Torch {
        private val on = mutableMapOf<String, Boolean>()
        private var watching = false

        fun isOn(id: String): Boolean = on[id] == true

        fun watch(camera: CameraManager) {
            if (watching) return
            watching = true
            camera.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    on[cameraId] = enabled
                }
            }, Handler(Looper.getMainLooper()))
        }
    }

    companion object {
        private const val TAG = "IO.perform"
    }
}
