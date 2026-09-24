package com.example.io

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.core.config.Knobs
import com.example.core.config.SettingsStore
import com.example.core.config.knobInt
import com.example.core.speech.SpeechText
import com.example.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Reading aloud: the keyboard's other direction.
 *
 * One speaker per process, shared by the keyboard, the accessibility service and the
 * engine, so "stop" stops whatever is being read wherever it was started. Built on
 * the phone's own speech engine — no permission, offline where a voice is installed —
 * with what the platform leaves out added here: pause (the engine can only stop, so a
 * pause remembers the word and resumes there), texts of any length (cut at sentence
 * ends), and a callback per word for following along in the field.
 */
object Speaker {

    private const val TAG = "Speaker"

    enum class Phase { IDLE, SPEAKING, PAUSED }

    data class Status(val phase: Phase = Phase.IDLE, val preview: String = "")

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var tts: TextToSpeech? = null
    private var engineInUse: String? = null
    private var ready = false
    private val waiting = mutableListOf<() -> Unit>()

    private var text = ""
    private var chunks: List<SpeechText.Chunk> = emptyList()
    private var chunkIndex = 0
    private var offset = 0
    private var fieldOrigin: Int? = null
    private var afterwards: (() -> Unit)? = null
    private var generation = 0

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    /** A provider's voice, while one is reading; null means the phone's own engine. */
    private var cloud: CloudVoice? = null

    /** Where the word being spoken is in the field, when the text came from it. */
    var onWord: ((start: Int, end: Int) -> Unit)? = null

    /** News for the user; set by whoever can show it. */
    var tell: ((String) -> Unit)? = null

    val isBusy: Boolean get() = _status.value.phase != Phase.IDLE

    /**
     * A media session while reading, so a headset button, a watch or the lock screen
     * can pause and resume it — the controls a phone in a pocket actually has.
     */
    private var session: android.media.session.MediaSession? = null

    private fun publish(phase: Phase) {
        val ctx = context ?: return
        if (phase == Phase.IDLE) {
            session?.isActive = false
            return
        }
        val s = session ?: android.media.session.MediaSession(ctx, "keyboard-reading").also { made ->
            made.setCallback(object : android.media.session.MediaSession.Callback() {
                override fun onPlay() { resume(ctx) }
                override fun onPause() { pause() }
                override fun onStop() { stop() }
            })
            session = made
        }
        val state = if (phase == Phase.SPEAKING) android.media.session.PlaybackState.STATE_PLAYING
        else android.media.session.PlaybackState.STATE_PAUSED
        s.setPlaybackState(
            android.media.session.PlaybackState.Builder()
                .setActions(
                    android.media.session.PlaybackState.ACTION_PLAY or
                        android.media.session.PlaybackState.ACTION_PAUSE or
                        android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                        android.media.session.PlaybackState.ACTION_STOP
                )
                .setState(state, android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        s.isActive = true
    }

    private fun setPhase(phase: Phase, preview: String = _status.value.preview) {
        _status.value = Status(phase, if (phase == Phase.IDLE) "" else preview)
        runCatching { publish(phase) }
    }

    private fun ensure(ctx: Context, then: () -> Unit) {
        context = ctx.applicationContext
        val wanted = SettingsStore.current.ttsEngine.takeIf { it.isNotBlank() }
        val existing = tts
        if (existing != null && wanted == engineInUse) {
            if (ready) then() else waiting += then
            return
        }
        existing?.shutdown()
        ready = false
        waiting.clear()
        waiting += then
        engineInUse = wanted
        tts = TextToSpeech(ctx.applicationContext, { status ->
            main.post {
                ready = status == TextToSpeech.SUCCESS
                if (!ready) {
                    tell?.invoke("Reading aloud: the phone's speech engine did not start")
                    waiting.clear()
                    return@post
                }
                tts?.setOnUtteranceProgressListener(progress)
                val pending = waiting.toList()
                waiting.clear()
                pending.forEach { it() }
            }
        }, wanted)
    }

    /** Settings are applied at every start, so a change needs no restart. */
    private fun configure(t: TextToSpeech) {
        val s = SettingsStore.current
        t.setSpeechRate(s.ttsRate.coerceIn(0.1f, 6f))
        t.setPitch(s.ttsPitch.coerceIn(0.25f, 4f))
        val usage = when (s.ttsUsage.uppercase()) {
            "ACCESSIBILITY" -> AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
            "ASSISTANT" -> if (Build.VERSION.SDK_INT >= 26) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_MEDIA
            "NOTIFICATION" -> AudioAttributes.USAGE_NOTIFICATION
            else -> AudioAttributes.USAGE_MEDIA
        }
        t.setAudioAttributes(
            AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        val voiceName = s.ttsVoice.takeIf { it.isNotBlank() }
        val voice = voiceName?.let { name -> runCatching { t.voices?.firstOrNull { it.name == name } }.getOrNull() }
        if (voice != null) {
            t.setVoice(voice)
        } else {
            val tag = s.ttsLanguage.ifBlank { currentLanguage ?: Locale.getDefault().toLanguageTag() }
            val result = t.setLanguage(Locale.forLanguageTag(tag))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tell?.invoke("No voice for “$tag” is installed — add one in the phone's text-to-speech settings")
            }
        }
    }

    /** The language of the text, when the caller knows it — the keyboard's layout, usually. */
    var currentLanguage: String? = null

    /**
     * Reads [what] from the beginning. [origin] is where it starts in the field, when
     * it came from one, so the words can be followed there. [then] runs when it has
     * all been read — how a page keeps going after its first screen.
     */
    fun speak(ctx: Context, what: String, origin: Int? = null, then: (() -> Unit)? = null) {
        if (what.isBlank()) return
        CloudVoice.providerFor(SettingsStore.current)?.let { (provider, key) ->
            speakWithProvider(ctx, what, then, CloudVoice(ctx.applicationContext, scope, provider, key, SettingsStore.current))
            return
        }
        ensure(ctx) {
            val t = tts ?: return@ensure
            configure(t)
            generation++
            text = what
            chunks = SpeechText.chunks(what, SettingsStore.current.knobInt(Knobs.READ_CHUNK_CHARS))
            chunkIndex = 0
            offset = 0
            fieldOrigin = origin
            afterwards = then
            speakFrom(t, 0, 0)
        }
    }

    /**
     * The same text through a provider's voice. If the provider fails part-way, the
     * phone's own voice carries on from the piece that failed — a reading that stops
     * dead because a network dropped is worse than one that changes voice.
     */
    private fun speakWithProvider(ctx: Context, what: String, then: (() -> Unit)?, voice: CloudVoice) {
        stopEverything()
        context = ctx.applicationContext
        generation++
        text = what
        chunks = SpeechText.chunks(what, SettingsStore.current.knobInt(Knobs.READ_CHUNK_CHARS))
        chunkIndex = 0
        offset = 0
        fieldOrigin = null
        afterwards = then
        cloud = voice
        setPhase(Phase.SPEAKING, what.take(60))
        voice.play(
            chunks.map { it.text }, 0,
            onPiece = { i ->
                chunkIndex = i
                offset = chunks.getOrNull(i)?.start ?: 0
            },
            onDone = {
                cloud = null
                finish()
            },
            onError = { e, i ->
                cloud = null
                AppLogger.e(TAG, "provider voice failed", e)
                tell?.invoke("The provider's voice failed (${e.message ?: "no reason"}) — the phone's voice carries on")
                ensure(ctx) {
                    val t = tts ?: return@ensure
                    configure(t)
                    speakFrom(t, i, chunks.getOrNull(i)?.start ?: 0)
                }
            }
        )
    }

    private fun stopEverything() {
        cloud?.stop()
        cloud = null
        tts?.stop()
    }

    /** A few words said at once, dropping anything queued — for echoing what is typed. */
    fun say(ctx: Context, what: String) {
        if (what.isBlank() || isBusy) return
        ensure(ctx) {
            val t = tts ?: return@ensure
            configure(t)
            t.speak(what, TextToSpeech.QUEUE_FLUSH, null, "echo")
        }
    }

    private fun speakFrom(t: TextToSpeech, index: Int, at: Int) {
        if (index >= chunks.size) {
            finish()
            return
        }
        val gen = generation
        setPhase(Phase.SPEAKING, text.take(60))
        for (i in index until chunks.size) {
            val c = chunks[i]
            val from = if (i == index) (at - c.start).coerceIn(0, c.text.length) else 0
            val part = c.text.substring(from)
            val params = Bundle()
            t.speak(part, if (i == index) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, "r$gen:$i:$from")
        }
    }

    private fun finish() {
        setPhase(Phase.IDLE)
        val next = afterwards
        afterwards = null
        next?.invoke()
    }

    fun stop() {
        generation++
        afterwards = null
        stopEverything()
        setPhase(Phase.IDLE)
    }

    fun pause() {
        if (_status.value.phase != Phase.SPEAKING) return
        cloud?.let {
            // A player can really pause; only the phone's engine needs the workaround.
            it.pause()
            setPhase(Phase.PAUSED)
            return
        }
        generation++
        tts?.stop()
        setPhase(Phase.PAUSED)
    }

    fun resume(ctx: Context) {
        if (_status.value.phase != Phase.PAUSED) return
        cloud?.let {
            it.resume()
            setPhase(Phase.SPEAKING)
            return
        }
        ensure(ctx) {
            val t = tts ?: return@ensure
            configure(t)
            generation++
            // From the start of the word that was being said, never mid-word.
            val at = SpeechText.wordStart(text, offset)
            val index = chunks.indexOfLast { it.start <= at }.coerceAtLeast(0)
            speakFrom(t, index, at)
        }
    }

    /** Pause when reading, resume when paused; false when there is nothing to toggle. */
    fun toggle(ctx: Context): Boolean = when (_status.value.phase) {
        Phase.SPEAKING -> { pause(); true }
        Phase.PAUSED -> { resume(ctx); true }
        Phase.IDLE -> false
    }

    /** Installed engines, by package — for the settings list. */
    fun engines(): List<String> = runCatching { tts?.engines?.map { it.name } }.getOrNull().orEmpty()

    /** Installed voices, by name — for the settings list. */
    fun voices(): List<String> =
        runCatching { tts?.voices?.map { it.name }?.sorted() }.getOrNull().orEmpty()

    /** Starts the engine early so the settings screen can list engines and voices. */
    fun warmUp(ctx: Context) = ensure(ctx) {}

    private fun parse(id: String?): Triple<Int, Int, Int>? {
        val parts = id?.removePrefix("r")?.split(':') ?: return null
        if (parts.size != 3) return null
        return Triple(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: 0)
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val (gen, i, from) = parse(utteranceId) ?: return
            main.post {
                if (gen != generation) return@post
                chunkIndex = i
                offset = (chunks.getOrNull(i)?.start ?: 0) + from
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val (gen, i, from) = parse(utteranceId) ?: return
            main.post {
                if (gen != generation) return@post
                val base = (chunks.getOrNull(i)?.start ?: 0) + from
                offset = base + start
                fieldOrigin?.let { o -> onWord?.invoke(o + base + start, o + base + end) }
            }
        }

        override fun onDone(utteranceId: String?) {
            val (gen, i, _) = parse(utteranceId) ?: return
            main.post {
                if (gen != generation) return@post
                if (i == chunks.lastIndex) finish()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            val (gen, _, _) = parse(utteranceId) ?: return
            main.post {
                if (gen != generation) return@post
                AppLogger.d(TAG, "engine error on $utteranceId")
                tell?.invoke("Reading aloud stopped: the speech engine reported an error")
                setPhase(Phase.IDLE)
            }
        }
    }
}
