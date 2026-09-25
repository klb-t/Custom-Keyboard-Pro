package com.example.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.example.core.config.Knobs
import com.example.core.config.SettingsStore
import com.example.core.config.knobFloat
import com.example.core.config.knobInt
import com.example.core.config.knobLong
import com.example.core.engine.InputSource
import com.example.core.engine.MotionGestures
import com.example.core.engine.ProximityGesture

/**
 * Listens to the sensors the live wires need, and to nothing else.
 *
 * A sensor nobody wired is never switched on — the battery a keyboard costs should
 * not grow because a feature exists. Thresholds come from the knobs, read when
 * listening starts, so a changed setting applies from the next time it does.
 */
class SensorHub(
    context: Context,
    private val onInput: (String) -> Unit,
    /** Every accelerometer reading, for streams: milliseconds, and x, y, z. */
    private val onAcceleration: ((Long, FloatArray) -> Unit)? = null
) : SensorEventListener {

    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val main = Handler(Looper.getMainLooper())
    private var motion: MotionGestures? = null
    private var proximity: ProximityGesture? = null
    private var listening: Set<InputSource> = emptySet()
    private var inPocket = false

    private val pocketCheck = Runnable {
        if (proximity?.covered == true && !inPocket) {
            inPocket = true
            onInput("in_pocket")
        }
    }

    fun listen(sources: Set<InputSource>) {
        val wanted = sources.intersect(setOf(InputSource.MOTION, InputSource.PROXIMITY))
        if (wanted == listening) return
        stop()
        val sm = manager ?: return
        val s = SettingsStore.current
        if (InputSource.MOTION in wanted) {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                motion = MotionGestures(
                    shakeThreshold = s.knobFloat(Knobs.SHAKE_FORCE),
                    shakeJolts = s.knobInt(Knobs.SHAKE_JOLTS),
                    shakeWindowMs = s.knobLong(Knobs.SHAKE_WINDOW_MS),
                    tiltDegrees = s.knobFloat(Knobs.TILT_DEGREES),
                    holdMs = s.knobLong(Knobs.TILT_HOLD_MS),
                    refractoryMs = s.knobLong(Knobs.GESTURE_REFRACTORY_MS)
                )
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        if (InputSource.PROXIMITY in wanted) {
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
                proximity = ProximityGesture(nearCm = s.knobFloat(Knobs.PROXIMITY_NEAR_CM))
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        listening = wanted
    }

    fun stop() {
        manager?.unregisterListener(this)
        main.removeCallbacks(pocketCheck)
        motion = null
        proximity = null
        listening = emptySet()
        inPocket = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        val atMs = e.timestamp / 1_000_000L
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                onAcceleration?.invoke(atMs, floatArrayOf(e.values[0], e.values[1], e.values[2]))
                motion?.reading(e.values[0], e.values[1], e.values[2], atMs)?.let(onInput)
            }
            Sensor.TYPE_PROXIMITY -> {
                val p = proximity ?: return
                val fired = p.reading(e.values[0], e.sensor.maximumRange, atMs)
                // "In a pocket" is being covered and staying covered; a sensor reports
                // only changes, so staying is checked on a timer.
                main.removeCallbacks(pocketCheck)
                if (p.covered) {
                    main.postDelayed(pocketCheck, SettingsStore.current.knobLong(Knobs.POCKET_DETECT_MS))
                } else if (inPocket) {
                    inPocket = false
                    onInput("out_of_pocket")
                }
                fired?.let(onInput)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
