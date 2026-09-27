package com.example.ime

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.example.core.config.SettingsStore
import com.example.io.TileActions

/** Shortcuts without an editor; long-press opens the same expert settings as the app. */
class KeyboardTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        SettingsStore.init(this)
        qsTile?.apply {
            state = if (liveKeyboard?.shortcutSessionActive == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = when {
                !isCurrentKeyboard() -> "Select IO Matrix first"
                liveKeyboard?.shortcutSessionActive == true -> "Tap to hide · hold for options"
                SettingsStore.current.keyboardKeepVisible -> "Keep active · hold for options"
                else -> "Hold for options"
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        SettingsStore.init(this)
        if (!isCurrentKeyboard()) {
            Toast.makeText(this, "Make IO Matrix the current keyboard first", Toast.LENGTH_LONG).show()
            TileActions.openAndCollapse(this,
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        TileActions.run(this, if (liveKeyboard?.shortcutSessionActive == true) TileActions.HIDE_KEYBOARD else TileActions.SHOW_KEYBOARD)
    }

    private fun isCurrentKeyboard(): Boolean {
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return ComponentName.unflattenFromString(current) == ComponentName(this, CustomKeyboardIme::class.java)
    }
}
