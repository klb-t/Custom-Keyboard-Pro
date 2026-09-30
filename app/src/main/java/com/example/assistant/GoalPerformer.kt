package com.example.assistant

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.example.core.assistant.*
import com.example.core.io.Command
import com.example.io.Performer
import com.example.io.PerformerHost
import com.example.core.phone.PhoneReceipt
import com.example.core.phone.PhoneOutcome
import kotlin.math.roundToInt

/** Reuses the existing performer; does not expose editors, macros or arbitrary model-selected code. */
class GoalPerformer(private val activity: Activity) : PerformerHost {
    override val context: Context get() = activity
    private var notice = ""
    private var phoneResult: PhoneReceipt? = null
    override fun phoneReceipt(receipt: PhoneReceipt) { phoneResult = receipt }
    override fun sendKey(keyCode: Int) { error("This host has no target editor") }
    override fun nearbyText() = ""
    override fun commit(text: String) { error("No editor write is authorized") }
    override fun copy(text: String, label: String) { error("No clipboard write is authorized") }
    override fun offerToAi(text: String) { error("Screen context is not authorized") }
    override fun notice(text: String, actionLabel: String?, action: (() -> Unit)?) { notice = text.take(1000) }
    override fun record(state: String, name: String) { error("Macro recording is not authorized") }
    override fun play(name: String, times: Int) { error("Macro playback needs a bounded host adapter") }
    override fun dismissKeyboard() {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
    }
    fun run(step: GoalStep): GoalSession.Receipt {
        val action = GoalCatalogue.actions()[step.action]
        require(action != null && action.runnable && action.invalidArguments(step.arguments).isEmpty())
        notice = ""
        phoneResult = null
        val command = Command(step.action, named = step.arguments.toMap())
        Performer(this).run(command)
        phoneResult?.let { receipt ->
            check(receipt.command == command) { "Phone result belongs to a different operation." }
            val status = when (receipt.outcome) {
                PhoneOutcome.VERIFIED, PhoneOutcome.OBSERVED -> GoalSession.State.VERIFIED
                PhoneOutcome.OPENED, PhoneOutcome.REQUESTED -> GoalSession.State.DISPATCHED_UNVERIFIED
                PhoneOutcome.NEEDS_ACCESS, PhoneOutcome.UNAVAILABLE, PhoneOutcome.FAILED -> GoalSession.State.FAILED
            }
            return GoalSession.Receipt(status, receipt.detail)
        }
        val verified = when (step.action) {
            "volume_set" -> {
                val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val expected = (step.arguments.getValue("level").toFloat() * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).roundToInt()
                audio.getStreamVolume(AudioManager.STREAM_MUSIC) == expected
            }
            "brightness" -> {
                val expected = (step.arguments.getValue("level").toFloat() * 255).roundToInt().coerceIn(1, 255)
                Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1) == expected &&
                    Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1) == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            }
            else -> false
        }
        return if (verified) GoalSession.Receipt(GoalSession.State.VERIFIED, "The requested setting was read back from Android.")
        else GoalSession.Receipt(GoalSession.State.DISPATCHED_UNVERIFIED,
            ("A request was passed to the existing performer; the intended effect has not been verified. " + notice).take(2000))
    }
}
