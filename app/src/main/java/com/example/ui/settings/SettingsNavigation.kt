package com.example.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.MainActivity

/** Explicit local navigation; requests never include the current editor's contents. */
object SettingsNavigation {
    const val QUERY = "settings_query"
    const val REQUEST = "settings_request"

    fun open(context: Context, route: String = "all", query: String = "", request: String = "") {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_ROUTE, route)
            .putExtra(QUERY, query)
            .putExtra(REQUEST, request)
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, "Could not open settings", Toast.LENGTH_SHORT).show()
        }
    }
}
