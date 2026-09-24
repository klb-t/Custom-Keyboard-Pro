package com.example.io

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.example.core.config.Settings
import com.example.core.discovery.AiCapability
import com.example.core.discovery.CallEngine
import com.example.core.discovery.CallInput
import com.example.core.discovery.ProviderCatalog
import com.example.core.discovery.ProviderProfiles
import com.example.core.discovery.ProviderSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reading aloud with a provider's voice instead of the phone's.
 *
 * Any provider in the catalogue that can "speech" will do — the request is described
 * there, so this file names none of them. Text goes in pieces: while one plays the
 * next is already being fetched, so a long text starts speaking after the first
 * sentence rather than after all of it. Unlike the phone's engine, a player can
 * really pause, so it does.
 *
 * What it costs, said where it is chosen: the text leaves the phone. Nothing from a
 * private field is ever read aloud, by either voice.
 */
class CloudVoice(
    private val context: Context,
    private val scope: CoroutineScope,
    private val provider: ProviderSpec,
    private val key: String,
    private val settings: Settings
) {
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var generation = 0

    /**
     * Plays [pieces] from [start]. [onPiece] says which one is now playing (for
     * resuming later), [onDone] runs after the last, [onError] gets the failure and
     * the piece it happened on — so the phone's own voice can carry on from there.
     */
    fun play(
        pieces: List<String>,
        start: Int,
        onPiece: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable, Int) -> Unit
    ) {
        stop()
        val gen = ++generation
        job = scope.launch {
            var index = start
            try {
                var next: Deferred<File>? = if (index < pieces.size) fetchAsync(pieces[index], gen, index) else null
                while (index < pieces.size) {
                    val file = next!!.await()
                    next = if (index + 1 < pieces.size) fetchAsync(pieces[index + 1], gen, index + 1) else null
                    onPiece(index)
                    playFile(file)
                    file.delete()
                    index++
                }
                if (gen == generation) onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (gen == generation) onError(e, index)
            }
        }
    }

    private fun fetchAsync(text: String, gen: Int, index: Int): Deferred<File> = scope.async(Dispatchers.IO) {
        val result = CallEngine.run(
            provider = provider,
            capability = AiCapability.SPEECH,
            input = CallInput(
                model = settings.ttsModel,
                prompt = text,
                language = settings.ttsLanguage,
                params = mapOf("voice" to settings.ttsCloudVoice)
            ),
            apiKey = key
        ).getOrThrow()
        val bytes = result.bytes ?: error("${provider.label} sent no audio back")
        File(context.cacheDir, "voice_${gen}_$index.audio").apply { writeBytes(bytes) }
    }

    private suspend fun playFile(file: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            player = mp
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(file.path)
                mp.setOnCompletionListener {
                    it.release()
                    if (player === it) player = null
                    if (cont.isActive) cont.resume(Unit)
                }
                mp.setOnErrorListener { p, what, extra ->
                    p.release()
                    if (player === p) player = null
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("the audio could not be played ($what/$extra)"))
                    true
                }
                mp.prepare()
                mp.start()
            } catch (e: Exception) {
                mp.release()
                player = null
                if (cont.isActive) cont.resumeWithException(e)
            }
            cont.invokeOnCancellation {
                runCatching { mp.stop() }
                runCatching { mp.release() }
                if (player === mp) player = null
            }
        }
    }

    fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    fun resume() {
        runCatching { player?.start() }
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    companion object {
        /**
         * The provider to read with, or null for the phone's own voice: the chosen one if
         * it can speak and has a key; blank means the phone's voice.
         */
        fun providerFor(settings: Settings): Pair<ProviderSpec, String>? {
            if (settings.ttsProvider.isBlank()) return null
            val spec = ProviderCatalog.byId(settings.ttsProvider, settings)?.takeIf { it.can(AiCapability.SPEECH) }
                ?: return null
            val key = ProviderProfiles.keyFor(spec.id, settings)
            if (spec.needsKey && key.isBlank()) return null
            return spec to key
        }
    }
}
