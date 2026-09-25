package com.example.core

import com.example.core.engine.EngineState
import com.example.core.engine.WireScope
import com.example.core.matrix.Sample
import com.example.core.matrix.Sink
import com.example.core.matrix.Stages
import com.example.core.matrix.Stream
import com.example.core.matrix.StreamSource
import com.example.core.matrix.StreamState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Streams: acceleration shaped into a steering axis, and where the values go. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamsTest {

    private val g = 9.81f

    /** What the accelerometer reads with the phone upright, screen facing you, turned clockwise by [deg]. */
    private fun turned(deg: Double, t: Long = 0) =
        Sample(t, floatArrayOf((-sin(deg * PI / 180) * g).toFloat(), (cos(deg * PI / 180) * g).toFloat(), 0f))

    private fun stage(line: String) = Stages.parse(line)!!

    @Test
    fun `turning the phone like a wheel reads as the angle turned, right positive`() {
        val tilt = stage("tilt wheel")
        for (deg in listOf(-150.0, -30.0, 0.0, 20.0, 90.0, 170.0)) {
            assertEquals("$deg", deg.toFloat(), tilt.process(turned(deg))!!.v[0], 0.01f)
        }
    }

    @Test
    fun `tilting a phone lying flat reads as roll and pitch`() {
        val tilt = stage("tilt roll pitch")
        // Flat, face up: gravity's reaction along +z.
        assertArrayEquals(floatArrayOf(0f, 0f), tilt.process(Sample(0, floatArrayOf(0f, 0f, g)))!!.v, 0.01f)
        // Right edge dipped 30°: the x axis points partly down.
        val right = tilt.process(Sample(0, floatArrayOf((-sin(PI / 6) * g).toFloat(), 0f, (cos(PI / 6) * g).toFloat())))!!
        assertEquals(30f, right.v[0], 0.01f)
        assertEquals(0f, right.v[1], 0.01f)
    }

    @Test
    fun `nothing is a tilt without three axes, and free fall is no angle`() {
        assertNull(stage("tilt").process(Sample(0, floatArrayOf(1f, 2f))))
        assertNull(stage("tilt").process(Sample(0, floatArrayOf(0f, 0f, 0f))))
        assertNull(Stages.parse("tilt sideways"))
    }

    @Test
    fun `calibration makes where it started zero, and angles wrap round`() {
        val cal = stage("calibrate 100")
        // Held in landscape: the wheel angle is -90 at rest.
        assertNull(cal.process(Sample(0, floatArrayOf(-90f))))
        assertNull(cal.process(Sample(50, floatArrayOf(-92f))))
        assertNull(cal.process(Sample(100, floatArrayOf(-88f))))
        assertEquals(10f, cal.process(Sample(120, floatArrayOf(-80f)))!!.v[0], 1e-4f)
        // From 175° on past the half turn to -175° is ten degrees further on, not 350 back.
        assertEquals(10f, stage("calibrate 0").also { it.process(Sample(0, floatArrayOf(175f))) }.process(Sample(1, floatArrayOf(-175f)))!!.v[0], 1e-4f)
        cal.reset()
        assertNull(cal.process(Sample(200, floatArrayOf(0f))))
    }

    @Test
    fun `range, dead zone and curve shape an axis without jumps`() {
        val range = stage("range -45 45")
        assertEquals(0f, range.process(Sample(0, floatArrayOf(0f)))!!.v[0], 1e-6f)
        assertEquals(1f, range.process(Sample(0, floatArrayOf(90f)))!!.v[0], 1e-6f)
        assertEquals(-0.5f, range.process(Sample(0, floatArrayOf(-22.5f)))!!.v[0], 1e-6f)
        val dz = stage("deadzone 0.1")
        assertEquals(0f, dz.process(Sample(0, floatArrayOf(0.09f)))!!.v[0], 1e-6f)
        // Just outside the dead zone is just above zero: no step.
        assertEquals(0.0111f, dz.process(Sample(0, floatArrayOf(0.11f)))!!.v[0], 1e-3f)
        assertEquals(-1f, dz.process(Sample(0, floatArrayOf(-1f)))!!.v[0], 1e-6f)
        val curve = stage("curve 2")
        assertEquals(0.25f, curve.process(Sample(0, floatArrayOf(0.5f)))!!.v[0], 1e-6f)
        assertEquals(-0.25f, curve.process(Sample(0, floatArrayOf(-0.5f)))!!.v[0], 1e-6f)
        assertEquals(-3f, stage("invert").process(Sample(0, floatArrayOf(3f)))!!.v[0], 0f)
        assertEquals(1f, stage("range 0 1 0 1").process(Sample(0, floatArrayOf(5f)))!!.v[0], 0f)
    }

    @Test
    fun `smoothing steadies a jittering hand and follows a real move`() {
        val smooth = stage("smooth 1 0.01")
        var out = 0f
        for (i in 0 until 50) out = smooth.process(Sample(i * 20L, floatArrayOf(if (i % 2 == 0) 0.02f else -0.02f)))!!.v[0]
        assertTrue("jitter came through: $out", kotlin.math.abs(out) < 0.01f)
        for (i in 50 until 150) out = smooth.process(Sample(i * 20L, floatArrayOf(1f)))!!.v[0]
        assertEquals(1f, out, 0.05f)
    }

    @Test
    fun `a rate limit keeps the newest reading per slot`() {
        val rate = stage("rate 10")
        assertNotNull(rate.process(Sample(0, floatArrayOf(1f))))
        assertNull(rate.process(Sample(50, floatArrayOf(2f))))
        assertNotNull(rate.process(Sample(100, floatArrayOf(3f))))
    }

    @Test
    fun `a whole steering stream, from gravity to an axis`() {
        val stream = Stream(
            "steer", "acceleration",
            via = listOf("tilt wheel", "calibrate 0", "range -45 45", "deadzone 0.05", "curve 1"),
            to = listOf("udp 192.168.1.20:26760")
        )
        val state = StreamState(stream)
        assertNull(state.feed(turned(-90.0, 0)))
        assertEquals(0f, state.feed(turned(-90.0, 10))!!.v[0], 1e-4f)
        // Turned 45° right of where it was held: full right.
        assertEquals(1f, state.feed(turned(-45.0, 20))!!.v[0], 1e-4f)
        assertEquals(StreamSource.ACCELERATION, stream.source)
        assertTrue(stream.leavesDevice)
        assertTrue(stream.describe(), stream.describe().contains("assuming the phone is not accelerating"))
        assertTrue(stream.describe().endsWith("(leaves the phone)"))
    }

    @Test
    fun `sinks are read from plain lines and refuse what they cannot use`() {
        assertEquals(Sink.Udp("192.168.1.20", 26760, "udp 192.168.1.20:26760"), Sink.parse("udp 192.168.1.20:26760"))
        assertNull(Sink.parse("udp nowhere"))
        assertNull(Sink.parse("udp host:99999"))
        assertEquals("volume_set {v}", (Sink.parse("do volume_set {v}") as Sink.Action).template)
        assertEquals(0.7f, (Sink.parse("events 0.7") as Sink.Events).level, 0f)
        assertNull(Sink.parse("email someone"))
    }

    @Test
    fun `values go into lines and actions the way they are written`() {
        assertEquals("io steer 0.1250 -1.0000", Sink.udpLine("steer", floatArrayOf(0.125f, -1f)))
        assertEquals("volume_set 0.500", Sink.fill("volume_set {v}", floatArrayOf(0.5f)))
        assertEquals("tap 0.250 0.750", Sink.fill("tap {v1} {v2}", floatArrayOf(0.25f, 0.75f)))
        assertEquals("brightness 40", Sink.fill("brightness {pct}", floatArrayOf(0.4f)))
    }

    @Test
    fun `crossing a level is an event, once, and the line does not chatter`() {
        val stream = Stream("steer", "acceleration", emptyList(), listOf("events 0.5 0.1"))
        val state = StreamState(stream)
        val sink = stream.sinks()!!.single() as Sink.Events
        assertEquals(emptyList<String>(), state.events(sink, 0.2f))
        assertEquals(listOf("stream:steer:high"), state.events(sink, 0.6f))
        assertEquals(emptyList<String>(), state.events(sink, 0.45f))
        assertEquals(emptyList<String>(), state.events(sink, 0.55f))
        assertEquals(listOf("stream:steer:center"), state.events(sink, 0.3f))
        assertEquals(listOf("stream:steer:low"), state.events(sink, -0.9f))
    }

    @Test
    fun `streams are written down and read back, and a bad one is refused whole`() {
        val streams = listOf(
            Stream("steer", "acceleration", listOf("tilt wheel", "calibrate", "range -45 45"), listOf("udp 10.0.0.2:26760", "events 0.5"), scope = WireScope.CLOSED),
            Stream("fader", "control:vol", emptyList(), listOf("do volume_set {v}"), enabled = false, apps = listOf("com.example.game"))
        )
        assertEquals(streams, Stream.parseAll(Stream.write(streams)))
        assertTrue(Stream.parseAll("[{\"from\":\"acceleration\",\"via\":[\"tilt sideways\"],\"to\":[\"udp a:1\"]}]").isEmpty())
        assertTrue(Stream.parseAll("[{\"from\":\"smell\",\"to\":[\"udp a:1\"]}]").isEmpty())
        assertTrue(Stream.parseAll("not json").isEmpty())
        assertEquals("vol", Stream.parseAll("[{\"from\":\"control:vol\",\"to\":\"do volume_set {v}\"}]").single().id)
    }

    @Test
    fun `a stream is live where and when it says`() {
        val s = Stream("x", "acceleration", emptyList(), listOf("events 0.5"), scope = WireScope.CLOSED, apps = listOf("game"))
        assertTrue(s.liveIn(EngineState(keyboardOpen = false, pkg = "game")))
        assertFalse(s.liveIn(EngineState(keyboardOpen = true, pkg = "game")))
        assertFalse(s.liveIn(EngineState(keyboardOpen = false, pkg = "other")))
    }
}
