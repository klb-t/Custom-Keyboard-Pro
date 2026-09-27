package com.example.io

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.MainActivity
import com.example.core.config.SettingsStore

/** Tap toggles the configured lock; Android's long-press opens its expert settings. */
class PocketLockTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        SettingsStore.init(this)
        qsTile?.apply {
            state = if (PocketLocks.locked) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) {
                subtitle = if (PocketLocks.available(this@PocketLockTile)) "Hold for lock options" else "Choose a permission"
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        SettingsStore.init(this)
        if (!PocketLocks.locked && !PocketLocks.available(this)) {
            // Offer both supported hosts, rather than unnecessarily requiring
            // accessibility when an ordinary overlay would meet the user's needs.
            TileActions.openAndCollapse(this,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_PERMISSIONS))
            return
        }
        TileActions.run(this, if (PocketLocks.locked) TileActions.UNLOCK else TileActions.LOCK)
    }
}
