package com.example

import android.app.Application
import com.example.util.AppLogger

/**
 * Installs the crash log before anything else in the process can fail.
 *
 * This exists because of a bug that left no trace. The microphone permission flow
 * was reported as crashing, and the diagnostics showed nothing at all: no crash
 * entry, and a single service start, meaning the process had never even died. Both
 * halves of that were misleading, and both came from the same mistake — the crash
 * handler was installed by whichever component happened to start first.
 *
 * Anything that failed *before* that point was invisible: a field initialiser on an
 * Activity (which runs before its `onCreate`), a failure inside the framework's own
 * startup, anything in a content provider. Installing it here closes the hole, since
 * `Application.onCreate` runs before any activity, service or receiver in the process.
 *
 * It does nothing else on purpose. An Application subclass is a tempting place to put
 * initialisation, and every line put here runs before the keyboard can draw — so the
 * stores stay where they are, initialised by the component that needs them.
 */
class KeyboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.d("App", "process started")
    }
}
