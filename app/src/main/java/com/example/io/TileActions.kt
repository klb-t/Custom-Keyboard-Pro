package com.example.io

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.view.WindowManager
import android.widget.Toast
import com.example.core.config.Knobs
import com.example.core.config.SettingsStore
import com.example.core.config.knobLong
import com.example.ime.liveKeyboard

/** All tile effects run after the shade has been dismissed, never underneath it. */
object TileActions {
    const val SHOW_KEYBOARD = "com.example.tile.SHOW_KEYBOARD"
    const val HIDE_KEYBOARD = "com.example.tile.HIDE_KEYBOARD"
    const val LOCK = "com.example.tile.LOCK"
    const val UNLOCK = "com.example.tile.UNLOCK"
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    fun run(tile: TileService, action: String) {
        if (tile.isLocked) {
            tile.unlockAndRun { run(tile, action) }
            return
        }
        val request = ++generation
        val dismissed = Build.VERSION.SDK_INT >= 31 &&
            IoAccessibilityService.instance?.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            ) == true
        if (dismissed) {
            afterCollapse(tile.applicationContext, action, request)
        } else {
            // The supported fallback needs no accessibility permission, hidden API,
            // CLOSE_SYSTEM_DIALOGS broadcast, editor or artificial input connection.
            val intent = Intent(tile, TileActionActivity::class.java)
                .setAction(action)
                .putExtra("request", request)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            openAndCollapse(tile, intent, action.hashCode())
        }
    }

    fun openAndCollapse(tile: TileService, intent: Intent, requestCode: Int = 0) {
        if (Build.VERSION.SDK_INT >= 34) {
            tile.startActivityAndCollapse(PendingIntent.getActivity(
                tile, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        } else {
            openAndCollapseLegacy(tile, intent)
        }
    }

    // PendingIntent overload exists only from API 34. The Intent overload is the
    // supported public API on 24–33; never call it on the modern branch above.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openAndCollapseLegacy(tile: TileService, intent: Intent) {
        check(Build.VERSION.SDK_INT < 34)
        tile.startActivityAndCollapse(intent)
    }

    internal fun afterCollapse(context: Context, action: String?, request: Int) {
        SettingsStore.init(context)
        main.postDelayed({
            if (request != generation) return@postDelayed
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (keyguard?.isKeyguardLocked == true) return@postDelayed
            when (action) {
                SHOW_KEYBOARD -> {
                    val ime = liveKeyboard
                    if (ime == null) {
                        Toast.makeText(context, "Open a text field once to start IO Matrix, then try the tile again", Toast.LENGTH_LONG).show()
                    } else {
                        ime.showWithoutField()
                    }
                }
                HIDE_KEYBOARD -> liveKeyboard?.hideKeyboard()
                LOCK -> if (PocketLocks.available(context)) {
                    // The host supplies the exact failure (permission, unsupported
                    // touch passthrough or missing escape), without a second toast.
                    PocketLocks.lock(context, SettingsStore.current.pocketMode)
                } else {
                    Toast.makeText(context, "Lock permission is no longer available. Hold the tile to review settings.", Toast.LENGTH_LONG).show()
                }
                UNLOCK -> PocketLocks.unlock("tile")
            }
        }, SettingsStore.current.knobLong(Knobs.POCKET_SHADE_DELAY_MS))
    }
}

/**
 * A transparent, non-focusable bridge used only when the OS cannot close the shade
 * through accessibility. It finishes before the action and never owns an editor.
 */
class TileActionActivity : Activity() {
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }

    override fun onResume() {
        super.onResume()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onStop() {
        super.onStop()
        if (!completed && isFinishing) {
            completed = true
            TileActions.afterCollapse(applicationContext, intent.action, intent.getIntExtra("request", -1))
        }
    }
}
