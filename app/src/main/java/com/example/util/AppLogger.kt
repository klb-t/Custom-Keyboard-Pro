package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray

/**
 * A log the user can read without a computer.
 *
 * Logcat needs adb, and adb needs a PC — so a crash on a phone with nothing but the
 * app and GitHub is invisible unless the app keeps its own record. This is that
 * record: every entry is timestamped and tagged with where it came from, kept in
 * memory for the Diagnostics screen and mirrored to [SharedPreferences] so a crash
 * that kills the process is still readable the next time anything in this app runs —
 * the settings activity included, since it shares the keyboard service's process only
 * until a crash kills it, and reads the same file fresh afterwards.
 *
 * The uncaught-exception handler below is the reason this exists at all: without it,
 * a crash during the keyboard's first frame looks like the keyboard silently refusing
 * to open — the system just falls back to whatever showed it, and there is no dialog,
 * no toast, nothing, because the process is gone before anything drew.
 */
object AppLogger {
    val logs = mutableStateListOf<String>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var prefs: SharedPreferences? = null
    private const val MAX_ENTRIES = 400
    private const val KEY = "saved_logs"

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("app_logs_store", Context.MODE_PRIVATE)
        val saved = prefs?.getString(KEY, "") ?: ""
        // Entries can contain anything, a stack trace especially, so they are kept as
        // a JSON array rather than joined on a delimiter that log text could collide with.
        if (saved.isNotEmpty()) {
            try {
                val array = JSONArray(saved)
                for (i in 0 until array.length()) logs.add(array.getString(i))
            } catch (malformed: Exception) {
                Log.w("AppLogger", "could not parse the saved log file, starting fresh", malformed)
            }
        }

        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val crashMsg = "on thread '${thread.name}': ${throwable.stackTraceToString()}"
            Log.e("AppLogger", "FATAL CRASH $crashMsg")
            try {
                // Synchronous: the process may not survive long enough for a posted
                // write to run, so this cannot go through the handler.post() path d()/e() use.
                saveSync("CRASH", crashMsg, sync = true)
            } catch (loggingFailure: Throwable) {
                // A crash inside the crash handler must never suppress the real one.
                Log.e("AppLogger", "failed to persist the crash log", loggingFailure)
            }
            oldHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun stamp(): String {
        val now = java.util.Calendar.getInstance()
        return "%02d:%02d:%02d.%03d".format(
            now.get(java.util.Calendar.HOUR_OF_DAY),
            now.get(java.util.Calendar.MINUTE),
            now.get(java.util.Calendar.SECOND),
            now.get(java.util.Calendar.MILLISECOND)
        )
    }

    /**
     * [sync] blocks for the write to actually land ([SharedPreferences.Editor.commit])
     * instead of merely scheduling it ([SharedPreferences.Editor.apply]). Routine
     * logging uses `apply()` so it never costs a disk-bound frame; the crash handler
     * above passes `sync = true` because the process may not survive long enough for
     * a merely-posted write to run.
     */
    private fun saveSync(tag: String, msg: String, sync: Boolean = false) {
        val entry = "${stamp()} $tag: $msg"
        logs.add(0, entry)
        while (logs.size > MAX_ENTRIES) logs.removeAt(logs.lastIndex)
        val json = JSONArray().apply { logs.forEach { put(it) } }
        val editor = prefs?.edit()?.putString(KEY, json.toString())
        if (sync) editor?.commit() else editor?.apply()
    }

    /**
     * A named step inside a longer operation, e.g. `"IME.onCreate"`, `"IME.onCreate > SettingsStore.init"`.
     * The `>` in [tag] is convention, not enforced — it is what makes the Diagnostics
     * list read as a hierarchy instead of a flat stream when a crash cuts a sequence
     * of these short partway through, which is exactly the case that matters.
     */
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

    /** Everything currently held, newest first, ready to copy or share as one block. */
    fun exportText(): String =
        if (logs.isEmpty()) "(no log entries yet)" else logs.joinToString("\n\n")

    fun clear() {
        logs.clear()
        prefs?.edit()?.putString(KEY, "")?.apply()
    }
}
