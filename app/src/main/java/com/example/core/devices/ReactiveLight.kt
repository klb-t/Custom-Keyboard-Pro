package com.example.core.devices

import kotlin.math.*

/** Provider-independent effect; not a claim that Wi-Fi specifies a bulb protocol. */
data class LightValue(val rgb: Int, val brightness: Int) {
    init { require(rgb in 0..0xffffff && brightness in 1..100) }
}
data class LightIdentity(val id: String, val model: String, val methods: Set<String>)
interface LightSink : AutoCloseable {
    fun connect(): LightIdentity
    /** Returning means bytes were written, not that a physical effect was measured. */
    fun send(value: LightValue)
}

data class ReactiveLightPolicy(val gain: Float = 3f, val maximumBrightness: Int = 30,
                               val hz: Int = 10, val durationSeconds: Int = 120) {
    init {
        require(gain.isFinite() && gain in 0.1f..20f)
        require(maximumBrightness in 1..60 && hz in 1..20 && durationSeconds in 1..600)
    }
}

/** RMS over a mono PCM16 frame. Waveform/phase/frequency are discarded; this is not beat detection. */
object PcmEnvelope {
    fun rms(samples: ShortArray, count: Int = samples.size): Float {
        require(count in 1..minOf(samples.size, 48_000))
        var sum = 0.0
        for (i in 0 until count) { val v = samples[i] / 32768.0; sum += v * v }
        return sqrt(sum / count).toFloat()
    }
}

/** Control value -> colour/brightness. A deterministic presentation policy, not an inference about music. */
object LevelToColour {
    fun map(level: Float, policy: ReactiveLightPolicy): LightValue {
        require(level.isFinite() && level >= 0)
        val v = (level * policy.gain).coerceIn(0f, 1f)
        val hue = 240f * (1f - v)
        val x = (1f - abs((hue / 60f) % 2f - 1f))
        val channels = when ((hue / 60f).toInt()) {
            0 -> floatArrayOf(1f, x, 0f)
            1 -> floatArrayOf(x, 1f, 0f)
            2 -> floatArrayOf(0f, 1f, x)
            3 -> floatArrayOf(0f, x, 1f)
            else -> floatArrayOf(x, 0f, 1f)
        }
        val rgb = (channels[0].times(255).roundToInt() shl 16) or
            (channels[1].times(255).roundToInt() shl 8) or channels[2].times(255).roundToInt()
        return LightValue(rgb, (1 + v * (policy.maximumBrightness - 1)).roundToInt())
    }
}

/** One unsent frame, never a growing queue of outdated colours. Clock is monotonic milliseconds. */
class LatestLight(private val hz: Int, private val maxAgeMillis: Long = 300) {
    init { require(hz in 1..20 && maxAgeMillis in 1..5000) }
    private var pending: Pair<Long, LightValue>? = null
    private var lastOffer = -1L
    private var sentAt = -1L
    private var lastValue: LightValue? = null
    private var closed = false
    @Synchronized fun offer(at: Long, value: LightValue): Boolean {
        if (closed || at < 0 || at < lastOffer) return false
        lastOffer = at; pending = at to value; return true
    }
    @Synchronized fun take(now: Long): LightValue? {
        if (closed) return null
        val (at, value) = pending ?: return null
        if (now < at || now - at > maxAgeMillis) { pending = null; return null }
        if (sentAt >= 0 && now - sentAt < 1000L / hz) return null
        pending = null
        if (lastValue == value) return null
        sentAt = now; lastValue = value
        return value
    }
    @Synchronized fun close() { closed = true; pending = null }
}
