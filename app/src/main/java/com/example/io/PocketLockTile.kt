package com.example.io

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.core.config.SettingsStore

/**
 * The pocket lock from the quick settings shade — the way to start it with no
 * keyboard open, which is exactly when a talking app is running.
 *
 * The shade is closed first and the lock goes up a moment later, over whatever app
 * the shade was pulled down on. Without the accessibility service, a tap goes to the
 * screen that switches it on.
 */
class PocketLockTile : TileService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        SettingsStore.init(this)
        val service = IoAccessibilityService.instance
        if (service == null) {
            openAccessibilitySettings()
            return
        }
        if (service.pocket.locked) {
            service.pocket.unlock("tile")
            refresh()
            return
        }
        if (Build.VERSION.SDK_INT >= 31) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        }
        // After the shade has gone, so the lock is over the app and not over the shade.
        main.postDelayed({
            IoAccessibilityService.instance?.pocket?.lock(SettingsStore.current.pocketMode)
            refresh()
        }, 450)
    }

    private fun openAccessibilitySettings() {
        val intent = IoAccessibilityService.settingsIntent()
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val service = IoAccessibilityService.instance
        tile.state = when {
            service == null -> Tile.STATE_INACTIVE
            service.pocket.locked -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (service == null) "Needs accessibility" else null
        }
        tile.updateTile()
    }
}
