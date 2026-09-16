package com.example.ime

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.core.config.Settings
import com.example.core.layout.KeyAction
import com.example.core.layout.KeyDef

/**
 * Haptic and audible key feedback.
 *
 * Haptics are on by default and sound is off, which is what most people end up
 * choosing anyway; both are fully adjustable, including the vibration length and
 * strength, because how a keyboard feels is the setting people are most particular
 * about and the one most keyboards expose the least.
 */
class FeedbackController(private val context: Context, private val settings: () -> Settings) {

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            null
        }
    }

    private val audio: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    fun onKeyPress(key: KeyDef?) {
        val s = settings()
        if (s.hapticEnabled) vibrate(s)
        if (s.soundEnabled) playSound(key, s)
    }

    private fun vibrate(s: Settings) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val duration = s.hapticMs.coerceIn(1, 200).toLong()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitude = s.hapticAmplitude.coerceIn(1, 255)
                v.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(duration)
            }
        } catch (e: Exception) {
            // A device that refuses to vibrate is not a reason to drop the keystroke.
        }
    }

    private fun playSound(key: KeyDef?, s: Settings) {
        val manager = audio ?: return
        val effect = when (key?.tapAction) {
            is KeyAction.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
            is KeyAction.Backspace -> AudioManager.FX_KEYPRESS_DELETE
            is KeyAction.Enter -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        try {
            manager.playSoundEffect(effect, s.soundVolume.coerceIn(0f, 1f))
        } catch (e: Exception) {
            // Same.
        }
    }
}
