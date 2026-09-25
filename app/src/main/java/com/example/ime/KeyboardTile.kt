package com.example.ime

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.Toast
import com.example.core.config.Knobs
import com.example.core.config.SettingsStore
import com.example.core.config.knobLong
import com.example.io.IoAccessibilityService

/**
 * The keyboard from the quick settings shade, with no text field — for shortcuts,
 * arrows and keys sent to whatever app is in front. See
 * [CustomKeyboardIme.showWithoutField] for what can and cannot be promised.
 *
 * It needs this keyboard to be the one selected: Android runs only the current
 * keyboard, and no app can show a different one. When it is not, the tile says so
 * and opens the keyboard settings.
 */
class KeyboardTile : TileService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onClick() {
        super.onClick()
        SettingsStore.init(this)
        val ime = liveKeyboard
        if (ime == null || !isCurrentKeyboard()) {
            Toast.makeText(this, "Make IO Matrix the current keyboard first", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }
        val service = IoAccessibilityService.instance
        if (service != null && Build.VERSION.SDK_INT >= 31) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else if (Build.VERSION.SDK_INT < 31) {
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        }
        main.postDelayed({ liveKeyboard?.showWithoutField() }, SettingsStore.current.knobLong(Knobs.POCKET_SHADE_DELAY_MS))
    }

    private fun isCurrentKeyboard(): Boolean {
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return current.startsWith("$packageName/")
    }
}
