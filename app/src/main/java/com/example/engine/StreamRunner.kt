package com.example.engine

import com.example.core.config.SettingsStore
import com.example.core.engine.EngineState
import com.example.core.engine.InputSource
import com.example.core.layout.LayoutJson
import com.example.core.matrix.Sample
import com.example.core.matrix.Sink
import com.example.core.matrix.Stream
import com.example.core.matrix.StreamSource
import com.example.core.matrix.StreamState
import kotlin.math.abs

/**
 * Runs the streams the user wrote: readings in, through each stream's stages, out to
 * its sinks — UDP, an action, or engine events.
 *
 * Streams are read from the settings when they change; each keeps its own stage
 * state (a calibration, a filter's memory) until then or until "recenter". Nothing
 * here listens to anything by itself: the engine switches a sensor on only while a
 * live stream or wire needs it.
 */
object StreamRunner {

    private var source: String? = null
    private var states: List<StreamState> = emptyList()
    private val sender = UdpSender { EngineRuntime.tell(it) }

    /** What each action sink sent last, so only real changes go out, and not too often. */
    private val lastAction = HashMap<String, Pair<Long, FloatArray>>()
    private const val ACTION_GAP_MS = 33L
    private const val ACTION_CHANGE = 0.01f

    fun states(): List<StreamState> {
        val raw = SettingsStore.current.engineStreamsJson
        if (raw != source) {
            source = raw
            states = Stream.parseAll(raw).map { StreamState(it) }
            lastAction.clear()
        }
        return states
    }

    /** The sources the live streams need switched on. */
    fun sourcesNeeded(state: EngineState): Set<InputSource> =
        states().filter { it.stream.liveIn(state) }.mapNotNull {
            when (it.stream.source) {
                StreamSource.ACCELERATION -> InputSource.MOTION
                else -> null
            }
        }.toSet()

    /** A reading from [from] — "acceleration", "control:<id>", "net:<channel>". */
    fun feed(from: String, sample: Sample, state: EngineState) {
        states().forEach { st ->
            if (st.stream.from != from || !st.stream.liveIn(state)) return@forEach
            val out = st.feed(sample) ?: return@forEach
            st.sinks.forEach { sink -> emit(st, sink, out) }
        }
    }

    private fun emit(st: StreamState, sink: Sink, out: Sample) {
        when (sink) {
            is Sink.Udp -> sender.send(sink, Sink.udpLine(st.stream.id, out.v))
            is Sink.Events -> out.v.firstOrNull()?.let { v -> st.events(sink, v).forEach { EngineRuntime.fire(it) } }
            is Sink.Action -> {
                val key = st.stream.id + "\u0000" + sink.line
                val last = lastAction[key]
                if (last != null) {
                    val changed = out.v.size != last.second.size || out.v.indices.any { abs(out.v[it] - last.second[it]) >= ACTION_CHANGE }
                    if (!changed || out.tMs - last.first < ACTION_GAP_MS) return
                }
                lastAction[key] = out.tMs to out.v.copyOf()
                val filled = Sink.fill(sink.template, out.v)
                // A verb line unless it is written as a key action ("cursor:left", "do:…").
                val written = if (':' in filled.substringBefore(' ')) filled else "do:$filled"
                LayoutJson.parseAction(written)?.let { EngineRuntime.dispatch(it) }
            }
        }
    }

    /** Forget calibrations and filter memories: all streams, or the one called [id]. */
    fun recenter(id: String?) {
        states().filter { id.isNullOrBlank() || it.stream.id == id }.forEach { it.reset() }
    }

    fun stop() = sender.close()
}
