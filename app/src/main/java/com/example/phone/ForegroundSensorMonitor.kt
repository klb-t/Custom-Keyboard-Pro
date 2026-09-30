package com.example.phone

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.core.phone.SensorSessionPolicy
import com.example.core.phone.SensorSessionBoundary

/** Activity-owned local reader. Closing/pausing the owner must call stop. */
class ForegroundSensorMonitor(context: Context, private val report: (String) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var selected: SensorEntry? = null
    private var policy: SensorSessionPolicy? = null
    private var boundary: SensorSessionBoundary? = null
    private var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var frames = 0L
    val active: Boolean get() = policy != null
    private var trigger: TriggerEventListener? = null
    fun start(entry: SensorEntry, hz: Int, seconds: Int): Boolean {
        stop()
        val sm = manager ?: return fail("Sensor service unavailable.")
        if (!entry.maySample) return fail(entry.samplePolicy)
        entry.permission?.let { if (application.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED) return fail("Missing $it. Grant it for this selected sensor, then press Start again.") }
        val started = SystemClock.elapsedRealtimeNanos()
        val bounded = SensorSessionPolicy(hz, seconds, started / 1_000_000L)
        boundary = SensorSessionBoundary(started)
        policy = bounded; selected = entry; frames = 0L; accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        val accepted = try {
            when (entry.sensor.reportingMode) {
                Sensor.REPORTING_MODE_ONE_SHOT -> {
                    // Android automatically cancels the listener after delivery. A new
                    // identity per arm prevents a queued old event canceling a new arm.
                    val callback = object : TriggerEventListener() {
                        override fun onTrigger(event: TriggerEvent?) {
                            val observed = event ?: return
                            if (policy !== bounded || trigger !== this || observed.sensor !== entry.sensor) return
                            if (boundary?.owns(observed.timestamp, SystemClock.elapsedRealtimeNanos()) != true) return
                            val shown = bounded.deliver(SystemClock.elapsedRealtime(), observed.values, observed.timestamp / 1_000_000L) ?: return
                            report("One-shot sensor fired: ${shown.joinToString()}\nThis session is complete. Press Start to arm it again.")
                            stop()
                        }
                    }
                    trigger = callback
                    sm.requestTriggerSensor(callback, entry.sensor)
                }
                Sensor.REPORTING_MODE_CONTINUOUS, Sensor.REPORTING_MODE_ON_CHANGE -> sm.registerListener(this, entry.sensor, maxOf(1_000_000 / hz, entry.sensor.minDelay), 0, main)
                else -> false
            }
        } catch (_: SecurityException) { stop(); return fail("Android refused this sensor. Its inventory entry does not establish sampling access.") }
        catch (_: Exception) { stop(); return fail("Sensor registration failed; no measurement is verified.") }
        if (!accepted) { stop(); return fail("Android did not accept sensor registration.") }
        report("Registered ${entry.sensor.name}. Waiting for a sample; registration is not a measurement.\nForeground only · requested delivery ≤$hz/s · duration ${seconds}s. Hardware may report more slowly or only on change.")
        fun tick() {
            if (policy !== bounded) return
            if (bounded.expired(SystemClock.elapsedRealtime())) {
                val count = frames; stop()
                report("Session ended after ${seconds}s · $count displayed samples. ${if (count == 0L) "No event was observed; this does not prove the sensor is absent." else "Last values remain visible above only until this message replaced them."}")
            } else main.postDelayed({ tick() }, 250)
        }
        main.postDelayed({ tick() }, 250)
        return true
    }
    fun stop() {
        manager?.unregisterListener(this)
        val callback = trigger
        if (callback != null) selected?.sensor?.let { runCatching { manager?.cancelTriggerSensor(callback, it) } }
        trigger = null
        selected = null; policy = null; boundary = null
        main.removeCallbacksAndMessages(null)
    }
    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        val entry = selected ?: return
        if (e.sensor !== entry.sensor) return
        if (boundary?.owns(e.timestamp, SystemClock.elapsedRealtimeNanos()) != true) return
        val p = policy ?: return
        val values = p.deliver(SystemClock.elapsedRealtime(), e.values, e.timestamp / 1_000_000L) ?: return
        frames++
        report("${entry.sensor.name}\n${values.mapIndexed { index, value -> "[$index] $value" }.joinToString(" · ")}\nAccuracy $accuracy · event timestamp ${e.timestamp} ns\nDisplayed $frames · received ${p.events} · ≤${p.hz}/s. Local, not recorded or uploaded.")
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { if (sensor === selected?.sensor) this.accuracy = accuracy }
    private fun fail(text: String): Boolean { report(text); return false }
}
