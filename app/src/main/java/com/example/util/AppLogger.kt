package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.mutableStateListOf

object AppLogger {
    val logs = mutableStateListOf<String>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("app_logs_store", Context.MODE_PRIVATE)
        val savedLogs = prefs?.getString("saved_logs", "") ?: ""
        if (savedLogs.isNotEmpty()) {
            savedLogs.split("|||").forEach { if (it.isNotBlank()) logs.add(it) }
        }

        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val crashMsg = "FATAL CRASH: ${throwable.stackTraceToString()}"
            Log.e("AppLogger", crashMsg)
            saveSync("CRASH", crashMsg, sync = true)
            oldHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveSync(tag: String, msg: String, sync: Boolean = false) {
        val entry = "$tag: $msg"
        logs.add(0, entry)
        if (logs.size > 200) logs.removeLast()
        val editor = prefs?.edit()?.putString("saved_logs", logs.joinToString("|||"))
        if (sync) {
            editor?.commit()
        } else {
            editor?.apply()
        }
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        handler.post {
            try {
                saveSync(tag, msg)
            } catch (e: Exception) {
            }
        }
    }

    fun e(tag: String, msg: String, err: Throwable? = null) {
        Log.e(tag, msg, err)
        handler.post {
            try {
                saveSync(tag, "$msg - ${err?.stackTraceToString() ?: "(no err)"}")
            } catch (ex: Exception) {
            }
        }
    }
}
