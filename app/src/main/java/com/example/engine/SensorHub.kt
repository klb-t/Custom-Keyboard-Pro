package com.example.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.core.engine.InputSource
import com.example.core.engine.MotionGestures
import com.example.core.engine.ProximityGesture

/**
 * Listens to the sensors the user's wires need, and to nothing else.
 *
 * A sensor nobody wired is never switched on — the battery a keyboard costs should
 * not grow because a feature exists. And only while the keyboard is open, for now:
 * listening with it closed is the accessibility service's job, and a later step.
 */
class SensorHub(context: Context, private val onInput: (String) -> Unit) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var motion: MotionGestures? = null
    private var proximity: ProximityGesture? = null
    private var listening: Set<InputSource> = emptySet()

    fun listen(sources: Set<InputSource>) {
        val wanted = sources.intersect(setOf(InputSource.MOTION, InputSource.PROXIMITY))
        if (wanted == listening) return
        stop()
        val sm = manager ?: return
        if (InputSource.MOTION in wanted) {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                motion = MotionGestures()
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        if (InputSource.PROXIMITY in wanted) {
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
                proximity = ProximityGesture()
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        listening = wanted
    }

    fun stop() {
        manager?.unregisterListener(this)
        motion = null
        proximity = null
        listening = emptySet()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        val atMs = e.timestamp / 1_000_000L
        val fired = when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> motion?.reading(e.values[0], e.values[1], e.values[2], atMs)
            Sensor.TYPE_PROXIMITY -> proximity?.reading(e.values[0], e.sensor.maximumRange, atMs)
            else -> null
        }
        fired?.let(onInput)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
