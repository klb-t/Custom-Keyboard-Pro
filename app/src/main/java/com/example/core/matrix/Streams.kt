package com.example.core.matrix

import com.example.core.engine.WireScope
import com.example.core.io.Command
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/** One reading of a stream: when, in milliseconds, and its values. */
class Sample(val tMs: Long, val v: FloatArray)

/**
 * One stage of shaping a stream — an instance of a transform in [Transforms], with
 * its parameters. Stages keep state (a calibration, a filter's memory), so each
 * stream has its own.
 */
interface Stage {
    /** The transform this is an instance of, for the stream's provenance. */
    val transform: String
    val line: String

    /** The next reading, or null to drop this one. */
    fun process(s: Sample): Sample?

    /** Forget what was learnt — a calibration, a filter's memory. */
    fun reset() {}
}

/**
 * The stages a stream can go through, each written the way a verb line is:
 * "tilt wheel", "range -45 45", "deadzone 0.05", "curve 1.5", "smooth".
 */
object Stages {

    data class Spec(val id: String, val transform: String, val help: String, val params: List<String>)

    val ALL = listOf(
        Spec("tilt", "tilt", "Acceleration into angles in degrees: roll (left/right), pitch (forward/back), wheel (turned like a steering wheel, screen facing you). Several give several values.", listOf("axes")),
        Spec("calibrate", "shape", "Whatever it reads over the first ms becomes zero; angles wrap around. \"recenter\" does it again.", listOf("ms")),
        Spec("range", "shape", "From min–max onto -1–1 (or onto to_min–to_max), clamped.", listOf("min", "max", "to_min", "to_max")),
        Spec("deadzone", "shape", "Around zero, zero; outside, rescaled so there is no jump.", listOf("size")),
        Spec("curve", "shape", "Gentle near the middle and quick at the ends (above 1), or the other way (below 1).", listOf("power")),
        Spec("smooth", "shape", "A One Euro filter: steady when still, quick when moving.", listOf("min_cutoff", "beta")),
        Spec("invert", "shape", "The other way round.", emptyList()),
        Spec("clamp", "shape", "Kept between min and max.", listOf("min", "max")),
        Spec("rate", "shape", "At most this many readings a second.", listOf("hz"))
    )

    fun spec(id: String): Spec? = ALL.firstOrNull { it.id == id }

    /** A stage from its line; null for anything that is not one, never a guess. */
    fun parse(line: String): Stage? {
        val c = Command.parse(line) ?: return null
        val spec = spec(c.verb) ?: return null
        fun arg(i: Int): String? = c.named[spec.params.getOrNull(i) ?: ""] ?: c.positional.getOrNull(i)
        fun num(i: Int): Float? = arg(i)?.replace(',', '.')?.toFloatOrNull()
        return when (c.verb) {
            "tilt" -> {
                val axes = (c.named["axes"]?.split(',') ?: c.positional).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    .ifEmpty { listOf("roll") }
                if (axes.any { it !in Tilt.AXES }) null else Tilt(axes, line)
            }
            "calibrate" -> Calibrate((num(0) ?: 300f).toLong(), line)
            "range" -> {
                val lo = num(0) ?: return null
                val hi = num(1) ?: return null
                if (lo == hi) null else Range(lo, hi, num(2) ?: -1f, num(3) ?: 1f, line)
            }
            "deadzone" -> DeadZone((num(0) ?: 0.05f).coerceIn(0f, 0.95f), line)
            "curve" -> Curve((num(0) ?: 1.5f).coerceIn(0.1f, 10f), line)
            "smooth" -> Smooth((num(0) ?: 1f).coerceAtLeast(0.01f), (num(1) ?: 0.01f).coerceAtLeast(0f), line)
            "invert" -> Invert(line)
            "clamp" -> Clamp(num(0) ?: -1f, num(1) ?: 1f, line)
            "rate" -> Rate((num(0) ?: 60f).coerceIn(0.1f, 1000f), line)
            else -> null
        }
    }

    private fun each(s: Sample, f: (Int, Float) -> Float) = Sample(s.tMs, FloatArray(s.v.size) { f(it, s.v[it]) })

    /**
     * Angles out of acceleration, taking gravity for "down" — which holds while the
     * phone is not being flung about, and is written into the transform's assumptions.
     * Right turns and right tilts are positive; forward is positive.
     */
    class Tilt(val axes: List<String>, override val line: String) : Stage {
        override val transform = "tilt"

        override fun process(s: Sample): Sample? {
            if (s.v.size < 3) return null
            val (x, y, z) = Triple(s.v[0], s.v[1], s.v[2])
            val g = sqrt(x * x + y * y + z * z)
            if (g < 1e-3f) return null
            val deg = 180f / PI.toFloat()
            return Sample(s.tMs, FloatArray(axes.size) { i ->
                when (axes[i]) {
                    "roll" -> asin((-x / g).coerceIn(-1f, 1f)) * deg
                    "pitch" -> asin((-y / g).coerceIn(-1f, 1f)) * deg
                    else -> atan2(-x, y) * deg
                }
            })
        }

        companion object {
            val AXES = setOf("roll", "pitch", "wheel")
        }
    }

    /** Whatever is read at the start is zero from then on; differences wrap at ±180 for angles. */
    class Calibrate(val ms: Long, override val line: String) : Stage {
        override val transform = "shape"
        private var since = -1L
        private var sum: DoubleArray? = null
        private var count = 0
        private var center: FloatArray? = null

        override fun process(s: Sample): Sample? {
            val c = center
            if (c != null && c.size == s.v.size) return each(s) { i, v -> wrap(v - c[i]) }
            if (since < 0) {
                since = s.tMs
                sum = DoubleArray(s.v.size)
                count = 0
            }
            val acc = sum!!
            if (acc.size != s.v.size) {
                reset()
                return null
            }
            s.v.forEachIndexed { i, v -> acc[i] += v.toDouble() }
            count++
            if (s.tMs - since >= ms) center = FloatArray(acc.size) { (acc[it] / count).toFloat() }
            // Nothing leaves while it is still learning where zero is.
            return null
        }

        override fun reset() {
            since = -1L
            sum = null
            count = 0
            center = null
        }

        /** Degrees past half a turn come round the other side; smaller values are unaffected. */
        private fun wrap(d: Float): Float = when {
            d > 180f -> d - 360f
            d < -180f -> d + 360f
            else -> d
        }
    }

    class Range(val lo: Float, val hi: Float, val toLo: Float, val toHi: Float, override val line: String) : Stage {
        override val transform = "shape"
        override fun process(s: Sample) = each(s) { _, v ->
            val f = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
            toLo + f * (toHi - toLo)
        }
    }

    class DeadZone(val size: Float, override val line: String) : Stage {
        override val transform = "shape"
        override fun process(s: Sample) = each(s) { _, v ->
            val a = abs(v)
            if (a <= size) 0f else sign(v) * ((a - size) / (1f - size)).coerceAtMost(1f)
        }
    }

    class Curve(val power: Float, override val line: String) : Stage {
        override val transform = "shape"
        override fun process(s: Sample) = each(s) { _, v -> sign(v) * abs(v).pow(power) }
    }

    class Invert(override val line: String) : Stage {
        override val transform = "shape"
        override fun process(s: Sample) = each(s) { _, v -> -v }
    }

    class Clamp(val lo: Float, val hi: Float, override val line: String) : Stage {
        override val transform = "shape"
        override fun process(s: Sample) = each(s) { _, v -> v.coerceIn(minOf(lo, hi), maxOf(lo, hi)) }
    }

    /** At most [hz] readings a second: the rest are dropped, the newest always wins the next slot. */
    class Rate(val hz: Float, override val line: String) : Stage {
        override val transform = "shape"
        private var last = Long.MIN_VALUE
        override fun process(s: Sample): Sample? {
            val gap = (1000f / hz).toLong()
            if (last != Long.MIN_VALUE && s.tMs - last < gap) return null
            last = s.tMs
            return s
        }

        override fun reset() {
            last = Long.MIN_VALUE
        }
    }

    /**
     * The One Euro filter (Casiez, Roussel, Vogel 2012): a low-pass whose cutoff rises
     * with speed, so a still hand does not jitter and a quick one does not lag.
     */
    class Smooth(val minCutoff: Float, val beta: Float, override val line: String) : Stage {
        override val transform = "shape"
        private var prev: FloatArray? = null
        private var dPrev: FloatArray? = null
        private var tPrev = 0L

        private fun alpha(cutoff: Float, dt: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * cutoff)
            return 1f / (1f + tau / dt)
        }

        override fun process(s: Sample): Sample {
            val p = prev
            if (p == null || p.size != s.v.size) {
                prev = s.v.copyOf()
                dPrev = FloatArray(s.v.size)
                tPrev = s.tMs
                return s
            }
            val dt = ((s.tMs - tPrev) / 1000f).coerceAtLeast(1e-3f)
            tPrev = s.tMs
            val d = dPrev!!
            val out = FloatArray(s.v.size) { i ->
                val dx = (s.v[i] - p[i]) / dt
                val aD = alpha(1f, dt)
                d[i] = aD * dx + (1 - aD) * d[i]
                val cutoff = minCutoff + beta * abs(d[i])
                val a = alpha(cutoff, dt)
                a * s.v[i] + (1 - a) * p[i]
            }
            prev = out
            return Sample(s.tMs, out)
        }

        override fun reset() {
            prev = null
            dPrev = null
        }
    }
}

/** Where a stream's values go. */
sealed interface Sink {
    val line: String

    /** Sent as a line of text in a UDP datagram: "io <stream> <values…>". Leaves the phone. */
    data class Udp(val host: String, val port: Int, override val line: String) : Sink

    /**
     * Any action a key can do, with {v} (and {v1}, {v2}…) put in, and {pct} as the
     * first value times a hundred — throttled to real changes.
     */
    data class Action(val template: String, override val line: String) : Sink

    /**
     * Engine inputs when the first value crosses a level: "stream:<id>:high" above
     * [level], "stream:<id>:low" below -[level], "stream:<id>:center" back inside — so
     * wires can listen to a tilt held past a point as they listen to a shake.
     */
    data class Events(val level: Float, val hysteresis: Float, override val line: String) : Sink

    companion object {
        /** "udp 192.168.1.20:26760", "do volume_set {v}", "events 0.5". Null for anything else. */
        fun parse(line: String): Sink? {
            val t = line.trim()
            val verb = t.substringBefore(' ').lowercase()
            val rest = t.substringAfter(' ', "").trim()
            return when (verb) {
                "udp" -> {
                    val host = rest.substringBeforeLast(':').trim()
                    val port = rest.substringAfterLast(':', "").trim().toIntOrNull()
                    if (host.isBlank() || port == null || port !in 1..65535) null else Udp(host, port, t)
                }
                "do" -> rest.takeIf { it.isNotBlank() }?.let { Action(it, t) }
                "events" -> {
                    val parts = rest.split(Regex("\\s+")).mapNotNull { it.replace(',', '.').toFloatOrNull() }
                    Events(parts.getOrNull(0) ?: 0.5f, parts.getOrNull(1) ?: 0.1f, t)
                }
                else -> null
            }
        }

        /** The UDP line for a reading: "io steer 0.125". */
        fun udpLine(stream: String, v: FloatArray): String =
            "io " + stream + " " + v.joinToString(" ") { "%.4f".format(java.util.Locale.ROOT, it) }

        /** The action with the values put in. */
        fun fill(template: String, v: FloatArray): String {
            var out = template
            for (i in v.indices.reversed()) out = out.replace("{v${i + 1}}", "%.3f".format(java.util.Locale.ROOT, v[i]))
            val first = v.firstOrNull() ?: 0f
            return out.replace("{v}", "%.3f".format(java.util.Locale.ROOT, first))
                .replace("{pct}", (first * 100f).coerceIn(-100f, 100f).roundToInt().toString())
        }
    }
}

/** Where a stream reads, and what that needs switched on. */
enum class StreamSource { ACCELERATION, CONTROL, NETWORK }

/**
 * A stream: readings from one source, shaped stage by stage, sent wherever it says —
 * continuously, as values, not reduced to events. The engine's wires are the event
 * case of the same thing: a wire is a stream whose source is an event and whose
 * only stage is "when it happens".
 */
data class Stream(
    val id: String,
    /** "acceleration", "control:<element id>" or "net:<channel>". */
    val from: String,
    val via: List<String>,
    val to: List<String>,
    val enabled: Boolean = true,
    val scope: WireScope = WireScope.ANY,
    val apps: List<String> = emptyList()
) {
    val source: StreamSource?
        get() = when {
            from == "acceleration" -> StreamSource.ACCELERATION
            from.startsWith("control:") -> StreamSource.CONTROL
            from.startsWith("net:") -> StreamSource.NETWORK
            else -> null
        }

    /** The stages, freshly made — each stream keeps its own state. Null when any line is not a stage. */
    fun stages(): List<Stage>? = via.map { Stages.parse(it) ?: return null }

    fun sinks(): List<Sink>? = to.map { Sink.parse(it) ?: return null }

    fun liveIn(state: com.example.core.engine.EngineState): Boolean =
        enabled && (apps.isEmpty() || state.pkg in apps) && when (scope) {
            WireScope.ANY -> true
            WireScope.KEYBOARD -> state.keyboardOpen
            WireScope.CLOSED -> !state.keyboardOpen
            WireScope.LOCKED -> state.locked
            WireScope.UNLOCKED -> !state.locked
        }

    /** Whether anything it does sends values off the phone. */
    val leavesDevice: Boolean get() = sinks().orEmpty().any { it is Sink.Udp }

    /**
     * The stream's provenance, once for the whole stream rather than per reading: a
     * hundred readings a second each carrying their history would cost more than the
     * readings.
     */
    fun describe(): String = buildString {
        append(from)
        via.forEach { line ->
            append(" → ").append(line)
            val spec = Stages.parse(line)?.let { Transforms.byId(it.transform) }
            if (spec != null && spec.id == "tilt") append(" [assuming ").append(spec.assumptions.first()).append(']')
        }
        to.forEach { append(" ⇒ ").append(it) }
        if (leavesDevice) append(" (leaves the phone)")
    }

    companion object {
        fun parseAll(raw: String): List<Stream> {
            if (raw.isBlank()) return emptyList()
            val array = runCatching { JSONArray(raw.trim()) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { parse(it) } }
        }

        private fun strings(o: JSONObject, key: String): List<String> =
            o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: o.optString(key).takeIf { it.isNotBlank() }?.let { listOf(it) }
                ?: emptyList()

        /** A stream as written; null when it names no source or no destination, or any stage or sink cannot be read. */
        fun parse(o: JSONObject): Stream? {
            val from = o.optString("from").trim().lowercase()
            val s = Stream(
                id = o.optString("id").trim().ifBlank { from.substringAfter(':') },
                from = from,
                via = strings(o, "via").map { it.trim() }.filter { it.isNotEmpty() },
                to = strings(o, "to").map { it.trim() }.filter { it.isNotEmpty() },
                enabled = o.optBoolean("enabled", true),
                scope = WireScope.parse(o.optString("when")),
                apps = strings(o, "in").filter { it.isNotBlank() }
            )
            if (s.id.isBlank() || s.source == null || s.to.isEmpty() || s.stages() == null || s.sinks() == null) return null
            return s
        }

        fun write(streams: List<Stream>): String = JSONArray().apply {
            streams.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("from", s.from)
                    put("via", JSONArray(s.via))
                    put("to", JSONArray(s.to))
                    if (!s.enabled) put("enabled", false)
                    if (s.scope != WireScope.ANY) put("when", s.scope.name.lowercase())
                    if (s.apps.isNotEmpty()) put("in", JSONArray(s.apps))
                })
            }
        }.toString()
    }
}

/**
 * The running side of a stream, apart from any platform: its stages with their state,
 * and what came out last — so the sinks can be told only about real changes.
 */
class StreamState(val stream: Stream) {
    val stages: List<Stage> = stream.stages().orEmpty()
    val sinks: List<Sink> = stream.sinks().orEmpty()
    var last: FloatArray? = null
        private set

    /** Which side of the event levels the first value was on: 1 high, -1 low, 0 centre. */
    private val zone = mutableMapOf<Sink.Events, Int>()

    /** A reading through every stage; null when a stage held it back. */
    fun feed(s: Sample): Sample? {
        var cur: Sample = s
        for (stage in stages) cur = stage.process(cur) ?: return null
        last = cur.v
        return cur
    }

    fun reset() {
        stages.forEach { it.reset() }
        zone.clear()
        last = null
    }

    /** The events a value crossing the levels fires, with hysteresis so a value on the line does not chatter. */
    fun events(sink: Sink.Events, v: Float): List<String> {
        val was = zone[sink] ?: 0
        val now = when {
            v >= sink.level -> 1
            v <= -sink.level -> -1
            abs(v) < sink.level - sink.hysteresis -> 0
            else -> was
        }
        if (now == was) return emptyList()
        zone[sink] = now
        return listOf("stream:${stream.id}:" + when (now) { 1 -> "high"; -1 -> "low"; else -> "center" })
    }
}
